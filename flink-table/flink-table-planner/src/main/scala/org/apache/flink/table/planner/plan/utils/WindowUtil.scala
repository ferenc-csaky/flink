/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.utils

import org.apache.flink.configuration.ReadableConfig
import org.apache.flink.table.api.{DataTypes, TableConfig, TableException, ValidationException}
import org.apache.flink.table.api.config.ExecutionConfigOptions
import org.apache.flink.table.planner.JBigDecimal
import org.apache.flink.table.planner.calcite.{FlinkTypeFactory, RexTableArgCall}
import org.apache.flink.table.planner.functions.sql.{FlinkSqlOperatorTable, SqlWindowTableFunction}
import org.apache.flink.table.planner.plan.`trait`.RelWindowProperties
import org.apache.flink.table.planner.plan.logical._
import org.apache.flink.table.planner.plan.metadata.FlinkRelMetadataQuery
import org.apache.flink.table.planner.plan.nodes.logical._
import org.apache.flink.table.planner.plan.utils.AggregateUtil.inferAggAccumulatorNames
import org.apache.flink.table.planner.plan.utils.WindowEmitStrategy.{TABLE_EXEC_EMIT_EARLY_FIRE_ENABLED, TABLE_EXEC_EMIT_LATE_FIRE_ENABLED}
import org.apache.flink.table.planner.typeutils.RowTypeUtils
import org.apache.flink.table.runtime.groupwindow._
import org.apache.flink.table.runtime.operators.window.tvf.common.WindowAssigner
import org.apache.flink.table.runtime.operators.window.tvf.slicing.SliceAssigner
import org.apache.flink.table.runtime.types.LogicalTypeDataTypeConverter.fromDataTypeToLogicalType
import org.apache.flink.table.types.logical.TimestampType
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks.canBeTimeAttributeType

import org.apache.calcite.plan.volcano.RelSubset
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.{BiRel, RelNode, RelVisitor}
import org.apache.calcite.rel.core._
import org.apache.calcite.rex._
import org.apache.calcite.sql.`type`.SqlTypeFamily
import org.apache.calcite.sql.{SqlKind, SqlUtil}
import org.apache.calcite.util.{ImmutableBitSet, Util}

import java.time.Duration
import java.util.Collections

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

/** Utilities for window table-valued functions. */
object WindowUtil {

  /** Returns true if the grouping keys contain window_start and window_end properties. */
  def groupingContainsWindowStartEnd(
      grouping: ImmutableBitSet,
      windowProperties: RelWindowProperties): Boolean = {
    if (windowProperties != null) {
      val windowStarts = windowProperties.getWindowStartColumns
      val windowEnds = windowProperties.getWindowEndColumns
      val hasWindowStart = !windowStarts.intersect(grouping).isEmpty
      val hasWindowEnd = !windowEnds.intersect(grouping).isEmpty
      hasWindowStart && hasWindowEnd
    } else {
      false
    }
  }

  /** Excludes window_start, window_end and window_time properties from grouping keys. */
  def groupingExcludeWindowStartEndTimeColumns(
      grouping: ImmutableBitSet,
      windowProperties: RelWindowProperties)
      : (ImmutableBitSet, ImmutableBitSet, ImmutableBitSet, ImmutableBitSet) = {
    val startColumns = windowProperties.getWindowStartColumns.intersect(grouping)
    val endColumns = windowProperties.getWindowEndColumns.intersect(grouping)
    val timeColumns = windowProperties.getWindowTimeColumns.intersect(grouping)
    val newGrouping = grouping.except(startColumns).except(endColumns).except(timeColumns)
    (startColumns, endColumns, timeColumns, newGrouping)
  }

  /** Returns true if the [[RexNode]] is a window table-valued function call. */
  def isWindowTableFunctionCall(node: RexNode): Boolean = node match {
    case call: RexCall => call.getOperator.isInstanceOf[SqlWindowTableFunction]
    case _ => false
  }

  /** Returns true if expressions in [[Calc]] contain calls on window columns. */
  def calcContainsCallsOnWindowColumns(calc: Calc, fmq: FlinkRelMetadataQuery): Boolean = {
    val calcInput = calc.getInput
    val calcInputWindowColumns = fmq.getRelWindowProperties(calcInput).getWindowColumns
    val calcProgram = calc.getProgram
    val condition = calcProgram.getCondition
    if (condition != null) {
      val predicate = calcProgram.expandLocalRef(condition)
      // condition shouldn't contain window columns
      if (FlinkRexUtil.containsExpectedInputRef(predicate, calcInputWindowColumns)) {
        return true
      }
    }
    // the expressions shouldn't contain calls on window columns
    val callsContainProps = calcProgram.getProjectList.map(calcProgram.expandLocalRef).exists {
      case rex: RexCall => FlinkRexUtil.containsExpectedInputRef(rex, calcInputWindowColumns)
      case _ => false
    }
    callsContainProps
  }

  /**
   * Builds a new RexProgram on the input of window-tvf to exclude window columns, but include time
   * attribute.
   *
   * The return tuple consists of 4 elements: (1) the new RexProgram (2) the field index shifting
   * (3) the new index of time attribute on the new RexProgram (4) whether the time attribute is new
   * added
   */
  def buildNewProgramWithoutWindowColumns(
      rexBuilder: RexBuilder,
      oldProgram: RexProgram,
      inputRowType: RelDataType,
      inputTimeAttributeIndex: Int,
      windowColumns: Array[Int]): (RexProgram, Array[Int], Int, Boolean) = {
    val programBuilder = new RexProgramBuilder(inputRowType, rexBuilder)
    // mapping from original field index to new field index
    var containsTimeAttribute = false
    var newTimeAttributeIndex = -1
    val calcFieldShifting = ArrayBuffer[Int]()
    val visitedProjectNames = new mutable.ArrayBuffer[String]
    oldProgram.getNamedProjects.foreach {
      namedProject =>
        val expr = oldProgram.expandLocalRef(namedProject.left)
        val uniqueName = RowTypeUtils.getUniqueName(namedProject.right, visitedProjectNames)
        // project columns except window columns
        expr match {
          case inputRef: RexInputRef if windowColumns.contains(inputRef.getIndex) =>
            calcFieldShifting += -1

          case _ =>
            try {
              programBuilder.addProject(expr, uniqueName)
              visitedProjectNames += uniqueName
            } catch {
              case e: Throwable =>
                e.printStackTrace()
            }
            val fieldIndex = programBuilder.getProjectList.size() - 1
            calcFieldShifting += fieldIndex
            // check time attribute exists in the calc
            expr match {
              case ref: RexInputRef if ref.getIndex == inputTimeAttributeIndex =>
                containsTimeAttribute = true
                newTimeAttributeIndex = fieldIndex
              case _ => // nothing
            }
        }
    }

    // append time attribute if the calc doesn't refer it
    if (!containsTimeAttribute) {
      val oldTimeAttributeFieldName = inputRowType.getFieldNames.get(inputTimeAttributeIndex)
      val uniqueName = RowTypeUtils.getUniqueName(oldTimeAttributeFieldName, visitedProjectNames)
      programBuilder.addProject(inputTimeAttributeIndex, uniqueName)
      newTimeAttributeIndex = programBuilder.getProjectList.size() - 1
    }

    if (oldProgram.getCondition != null) {
      val condition = oldProgram.expandLocalRef(oldProgram.getCondition)
      programBuilder.addCondition(condition)
    }

    val program = programBuilder.getProgram()
    (program, calcFieldShifting.toArray, newTimeAttributeIndex, !containsTimeAttribute)
  }

  def validateTimeFieldWithTimeAttribute(windowCall: RexCall, inputRowType: RelDataType): Unit = {
    val timeIndex = getTimeAttributeIndex(windowCall)
    val fieldType = inputRowType.getFieldList.get(timeIndex).getType
    if (!FlinkTypeFactory.isTimeIndicatorType(fieldType)) {
      throw new ValidationException(
        s"The window function requires the timecol is a time attribute type, but is $fieldType.")
    }
  }

  /**
   * Converts a [[RexCall]] into [[TimeAttributeWindowingStrategy]], the [[RexCall]] must be a
   * window table-valued function call.
   */
  def convertToWindowingStrategy(
      windowCall: RexCall,
      scanInput: RelNode): TimeAttributeWindowingStrategy = {
    if (!isWindowTableFunctionCall(windowCall)) {
      throw new IllegalArgumentException(
        s"RexCall $windowCall is not a window table-valued " +
          "function, can't convert it into WindowingStrategy")
    }

    val inputRowType = scanInput.getRowType
    val timeIndex = getTimeAttributeIndex(windowCall)
    val fieldType = inputRowType.getFieldList.get(timeIndex).getType
    val timeAttributeType = FlinkTypeFactory.toLogicalType(fieldType)
    if (!canBeTimeAttributeType(timeAttributeType)) {
      throw new ValidationException(
        "The supported time indicator type are TIMESTAMP" +
          " and TIMESTAMP_LTZ, but is " + FlinkTypeFactory.toLogicalType(fieldType) + "")
    }

    val windowFunction = windowCall.getOperator.asInstanceOf[SqlWindowTableFunction]
    val windowSpec = windowFunction match {
      case FlinkSqlOperatorTable.TUMBLE =>
        val offset: Duration = if (windowCall.operands.size() == 4) {
          Duration.ofMillis(getOperandAsLong(windowCall.operands(3)))
        } else {
          null
        }
        val interval = getOperandAsLong(windowCall.operands(2))
        if (interval <= 0) {
          throw new ValidationException(
            s"TUMBLE table function based aggregate requires size to be positive," +
              s" but got $interval ms.")
        }
        if (offset != null && Math.abs(offset.toMillis) >= interval) {
          throw new ValidationException(
            s"TUMBLE table function parameters must satisfy abs(offset) < size, " +
              s"but got size $interval ms and offset ${offset.toMillis} ms.")
        }
        new TumblingWindowSpec(Duration.ofMillis(interval), offset)

      case FlinkSqlOperatorTable.HOP =>
        val offset = if (windowCall.operands.size() == 5) {
          Duration.ofMillis(getOperandAsLong(windowCall.operands(4)))
        } else {
          null
        }
        val slide = getOperandAsLong(windowCall.operands(2))
        val size = getOperandAsLong(windowCall.operands(3))
        if (slide <= 0 || size <= 0) {
          throw new ValidationException(
            s"HOP table function based aggregate requires slide and size to be positive," +
              s" but got slide $slide ms and size $size ms.")
        }
        new HoppingWindowSpec(Duration.ofMillis(size), Duration.ofMillis(slide), offset)

      case FlinkSqlOperatorTable.CUMULATE =>
        val offset = if (windowCall.operands.size() == 5) {
          Duration.ofMillis(getOperandAsLong(windowCall.operands(4)))
        } else {
          null
        }
        val step = getOperandAsLong(windowCall.operands(2))
        val maxSize = getOperandAsLong(windowCall.operands(3))
        if (step <= 0 || maxSize <= 0) {
          throw new ValidationException(
            s"CUMULATE table function based aggregate requires maxSize and step to be positive," +
              s" but got maxSize $maxSize ms and step $step ms.")
        }
        if (maxSize % step != 0) {
          throw new ValidationException("CUMULATE table function based aggregate requires maxSize " +
            s"must be an integral multiple of step, but got maxSize $maxSize ms and step $step ms.")
        }
        new CumulativeWindowSpec(Duration.ofMillis(maxSize), Duration.ofMillis(step), offset)
      case FlinkSqlOperatorTable.SESSION =>
        val tableArgCall = windowCall.operands(0).asInstanceOf[RexTableArgCall]
        if (!tableArgCall.getOrderKeys.isEmpty) {
          throw new ValidationException("Session window TVF doesn't support order by clause.")
        }
        val gap = getOperandAsLong(windowCall.operands(2))
        if (gap <= 0) {
          throw new ValidationException(
            s"SESSION table function based aggregate requires gap to be positive," +
              s" but got gap $gap ms.")
        }
        new SessionWindowSpec(Duration.ofMillis(gap), tableArgCall.getPartitionKeys)
    }

    new TimeAttributeWindowingStrategy(windowSpec, timeAttributeType, timeIndex)
  }

  /**
   * Window TVF based aggregations don't support early-fire and late-fire, throws exception when the
   * configurations are set.
   */
  def checkEmitConfiguration(tableConfig: TableConfig): Unit = {
    if (
      tableConfig.get(TABLE_EXEC_EMIT_EARLY_FIRE_ENABLED) ||
      tableConfig.get(TABLE_EXEC_EMIT_LATE_FIRE_ENABLED)
    ) {
      throw new TableException(
        "Currently, window table function based aggregate doesn't " +
          s"support early-fire and late-fire configuration " +
          s"'${TABLE_EXEC_EMIT_EARLY_FIRE_ENABLED.key()}' and " +
          s"'${TABLE_EXEC_EMIT_LATE_FIRE_ENABLED.key()}'.")
    }
  }

  // ------------------------------------------------------------------------------------------
  // RelNode RowType
  // ------------------------------------------------------------------------------------------

  def deriveWindowAggregateRowType(
      grouping: Array[Int],
      aggCalls: Seq[AggregateCall],
      windowing: WindowingStrategy,
      namedWindowProperties: Seq[NamedWindowProperty],
      inputRowType: RelDataType,
      typeFactory: FlinkTypeFactory): RelDataType = {
    val groupSet = ImmutableBitSet.of(grouping: _*)
    val baseType = Aggregate.deriveRowType(
      typeFactory,
      inputRowType,
      false,
      groupSet,
      Collections.singletonList(groupSet),
      aggCalls)
    val builder = typeFactory.builder
    builder.addAll(baseType.getFieldList)
    namedWindowProperties.foreach {
      namedProp =>
        // use types from windowing strategy which keeps the precision and timestamp type
        // cast the type to not null type, because window properties should never be null
        val timeType = namedProp.getProperty match {
          case _: WindowStart | _: WindowEnd =>
            new TimestampType(false, 3)
          case _: RowtimeAttribute | _: ProctimeAttribute =>
            windowing.getTimeAttributeType.copy(false)
        }
        builder.add(namedProp.getName, typeFactory.createFieldTypeFromLogicalType(timeType))
    }
    builder.build()
  }

  /** Derives output row type from local window aggregate */
  def deriveLocalWindowAggregateRowType(
      aggInfoList: AggregateInfoList,
      grouping: Array[Int],
      endPropertyName: String,
      inputRowType: RelDataType,
      typeFactory: FlinkTypeFactory): RelDataType = {
    val accTypes = aggInfoList.getAccTypes
    val groupingTypes = grouping
      .map(inputRowType.getFieldList.get(_).getType)
      .map(FlinkTypeFactory.toLogicalType)
    val sliceEndType = Array(DataTypes.BIGINT().getLogicalType)

    val groupingNames = grouping.map(inputRowType.getFieldNames.get(_))
    val accFieldNames = inferAggAccumulatorNames(aggInfoList)
    val sliceEndName = Array(s"$$$endPropertyName")

    typeFactory.buildRelNodeRowType(
      groupingNames ++ accFieldNames ++ sliceEndName,
      groupingTypes ++ accTypes.map(fromDataTypeToLogicalType) ++ sliceEndType)
  }

  /**
   * For rowtime window, return true if the given aggregate grouping contains window start and end.
   * For proctime window, we should also check if it exists a neighbour windowTableFunctionCall and
   * doesn't exist any [[RexCall]] on window time columns.
   *
   * If the window is a session window, we should also check if the partition keys are the same as
   * the group keys. See more at [[WindowUtil.validGroupKeyPartitionKey()]].
   */
  def isValidWindowAggregate(agg: FlinkLogicalAggregate): Boolean = {
    val fmq = FlinkRelMetadataQuery.reuseOrCreate(agg.getCluster.getMetadataQuery)
    val windowProperties = fmq.getRelWindowProperties(agg.getInput)
    val grouping = agg.getGroupSet
    if (!validGroupKeyPartitionKey(grouping, windowProperties)) {
      return false
    }
    if (WindowUtil.groupingContainsWindowStartEnd(grouping, windowProperties)) {
      isValidRowtimeWindow(windowProperties) || isValidProcTimeWindow(windowProperties, fmq, agg)
    } else {
      false
    }
  }

  def isAsyncStateEnabled(
      config: ReadableConfig,
      windowAssigner: WindowAssigner,
      aggInfoList: AggregateInfoList): Boolean = {
    if (!config.get(ExecutionConfigOptions.TABLE_EXEC_ASYNC_STATE_ENABLED)) {
      return false
    }
    // currently, unsliced assigner does not support async state
    if (!windowAssigner.isInstanceOf[SliceAssigner]) {
      return false
    }

    AggregateUtil.isAsyncStateEnabled(config, aggInfoList)
  }

  // ------------------------------------------------------------------------------------------
  // Private Helpers
  // ------------------------------------------------------------------------------------------

  private def getTimeAttributeIndex(windowCall: RexNode): Int = {
    val call = windowCall.asInstanceOf[RexCall]
    val tableArg = call.operands(0)
    val onTimeArg = call.operands(1)

    val fieldName = RexLiteral.stringValue(onTimeArg.asInstanceOf[RexCall].operands(0))
    val timeAttributeIndex = tableArg.getType.getField(fieldName, true, false)

    if (timeAttributeIndex == null) {
      throw new TableException(
        s"Failed to get time attribute index from $onTimeArg. " +
          "This is a bug, please file a JIRA issue.")
    }
    timeAttributeIndex.getIndex
  }

  private def getOperandAsLong(operand: RexNode): Long = {
    operand match {
      case v: RexLiteral if v.getTypeName.getFamily == SqlTypeFamily.INTERVAL_DAY_TIME =>
        v.getValue.asInstanceOf[JBigDecimal].longValue()
      case _: RexLiteral =>
        throw new TableException(
          "Window aggregate only support SECOND, MINUTE, HOUR, DAY as the time unit. " +
            "MONTH and YEAR time unit are not supported yet.")
      case _ => throw new TableException("Only constant window descriptors are supported.")
    }
  }

  private def isValidRowtimeWindow(windowProperties: RelWindowProperties): Boolean = {
    // rowtime tvf window can support calculation on window columns even before aggregation
    windowProperties.isRowtime
  }

  /**
   * If the middle Calc(s) contains call(s) on window columns, we should not convert the Aggregate
   * into WindowAggregate but GroupAggregate instead.
   *
   * The valid plan structure is like:
   *
   * {{{
   * Aggregate
   *  |
   * Calc (should not contain call on window columns)
   *  |
   * WindowTableFunctionScan
   * }}}
   *
   * and unlike:
   *
   * {{{
   * Aggregate
   *  |
   * Calc
   *  |
   * Aggregate
   *  |
   * Calc
   *  |
   * WindowTableFunctionScan
   * }}}
   */
  private def isValidProcTimeWindow(
      windowProperties: RelWindowProperties,
      fmq: FlinkRelMetadataQuery,
      agg: FlinkLogicalAggregate): Boolean = {
    val calcMatcher = new CalcWindowFunctionScanMatcher
    try {
      calcMatcher.go(agg.getInput(0))
    } catch {
      case _: Throwable => // do nothing
    }
    if (!calcMatcher.existNeighbourWindowTableFunc) {
      return false
    }
    var existCallOnWindowColumns = calcMatcher.calcNodes.nonEmpty &&
      calcMatcher.calcNodes.exists(calc => calcContainsCallsOnWindowColumns(calc, fmq))

    // aggregate call shouldn't be on window columns
    val aggInputWindowProps = windowProperties.getWindowColumns
    existCallOnWindowColumns = existCallOnWindowColumns || !agg.getAggCallList.forall {
      call => aggInputWindowProps.intersect(ImmutableBitSet.of(call.getArgList)).isEmpty
    }
    // proctime tvf window can't support calculation on window columns before aggregation
    !existCallOnWindowColumns
  }

  private class CalcWindowFunctionScanMatcher extends RelVisitor {
    val calcNodes: ListBuffer[Calc] = ListBuffer[Calc]()
    var existNeighbourWindowTableFunc = false

    override def visit(node: RelNode, ordinal: Int, parent: RelNode): Unit = {
      node match {
        case calc: Calc =>
          calcNodes += calc
          // continue to visit children
          super.visit(calc, 0, parent)
        case scan: FlinkLogicalTableFunctionScan =>
          if (WindowUtil.isWindowTableFunctionCall(scan.getCall)) {
            existNeighbourWindowTableFunc = true
            // stop visiting
            throw new Util.FoundOne
          }
        case rss: RelSubset =>
          val innerRel = Option.apply(rss.getBest).getOrElse(rss.getOriginal)
          // special case doesn't call super.visit for RelSubSet because it has no children
          visit(innerRel, 0, rss)
        case _: FlinkLogicalAggregate | _: FlinkLogicalMatch | _: FlinkLogicalOverAggregate |
            _: FlinkLogicalRank | _: BiRel | _: SetOp =>
          // proctime attribute comes from these operators can't be used directly for proctime
          // window aggregate, so further tree walk is unnecessary
          throw new Util.FoundOne
        case _ =>
          // continue to visit children
          super.visit(node, ordinal, parent)
      }
    }
  }

  /**
   * This method only checks the window like session window that contains partition keys. The
   * partition keys in session window should be the same as the group keys in aggregate. If they are
   * different, the downstream will not be able to use window-related optimizations, and the window
   * table function scan will always be an isolated node.
   *
   * Take a SQL following as an example.
   *
   * {{{
   *   Source Table `my_table` SCHEMA: a int, b int, c int, proctime as PROCTIME()
   *   SQL: SELECT count(c) FROM
   *          TABLE(SESSION(
   *              TABLE my_table PARTITION BY (a, b),
   *              DESCRIPTOR(proctime),
   *              INTERVAL '5' MINUTE))
   *        GROUP BY a, window_start, window_end
   * }}}
   *
   * The plan is like:
   * {{{
   *  FlinkLogicalAggregate(group=[{0, 1, 2}], EXPR$0=[COUNT($3)])
   *    FlinkLogicalCalc(select=[b, window_start, window_end, c])
   *      FlinkLogicalTableFunctionScan(invocation=[SESSION(PARTITION BY($0, $1),
   *      DESCRIPTOR($3), 300000:INTERVAL MINUTE)]])
   *        FlinkLogicalCalc(select=[a, b, c, PROCTIME() AS proctime])
   *          FlinkLogicalTableSourceScan(table=[[default_catalog, default_database, my_table]],
   *          fields=[a, b, c])
   * }}}
   *
   * In this case, the group keys in Aggregate are different with the partition keys in Session
   * Window in TableFunctionScan. The Aggregate node should not be converted into WindowAggregate
   * finally, because the data from source has been split, resolved and aggregated in different
   * subtasks in FlinkLogicalTableSourceScan with different partition keys.
   */
  private def validGroupKeyPartitionKey(
      grouping: ImmutableBitSet,
      windowProp: RelWindowProperties): Boolean = {
    if (windowProp == null) {
      return false
    }
    val (_, _, _, newGrouping) =
      WindowUtil.groupingExcludeWindowStartEndTimeColumns(grouping, windowProp)
    windowProp.getWindowSpec match {
      case session: SessionWindowSpec =>
        val partitionKeys = session.getPartitionKeyIndices.toSet
        partitionKeys.equals(newGrouping.toSet)
      case _ => true
    }
  }
}
