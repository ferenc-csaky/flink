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

package org.apache.flink.table.operations.utils;

import org.apache.flink.annotation.Internal;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.ContextResolvedFunction;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ExpressionUtils;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.utils.ResolvedExpressionDefaultVisitor;
import org.apache.flink.table.functions.FunctionDefinition;
import org.apache.flink.table.functions.FunctionKind;
import org.apache.flink.table.operations.CorrelatedFunctionQueryOperation;
import org.apache.flink.table.operations.QueryOperation;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks;
import org.apache.flink.table.types.utils.DataTypeUtils;

import java.util.Collections;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.apache.flink.table.expressions.ApiExpressionUtils.isFunctionOfKind;
import static org.apache.flink.table.functions.BuiltInFunctionDefinitions.AS;

/** Utility class for creating a valid {@link CorrelatedFunctionQueryOperation} operation. */
@Internal
final class CorrelatedFunctionTableFactory {

    /**
     * Creates a valid {@link CorrelatedFunctionQueryOperation} operation.
     *
     * @param callExpr call to table function as expression
     * @return valid calculated table
     */
    QueryOperation create(ResolvedExpression callExpr, List<String> leftTableFieldNames) {
        FunctionTableCallVisitor calculatedTableCreator =
                new FunctionTableCallVisitor(leftTableFieldNames);
        return callExpr.accept(calculatedTableCreator);
    }

    private static class FunctionTableCallVisitor
            extends ResolvedExpressionDefaultVisitor<CorrelatedFunctionQueryOperation> {
        private final List<String> leftTableFieldNames;
        private static final String ATOMIC_FIELD_NAME = "f0";

        public FunctionTableCallVisitor(List<String> leftTableFieldNames) {
            this.leftTableFieldNames = leftTableFieldNames;
        }

        @Override
        public CorrelatedFunctionQueryOperation visit(CallExpression call) {
            FunctionDefinition definition = call.getFunctionDefinition();
            if (definition.equals(AS)) {
                return unwrapFromAlias(call);
            }

            return createFunctionCall(call, Collections.emptyList(), call.getResolvedChildren());
        }

        private CorrelatedFunctionQueryOperation unwrapFromAlias(CallExpression call) {
            List<Expression> children = call.getChildren();
            List<String> aliases =
                    children.subList(1, children.size()).stream()
                            .map(
                                    alias ->
                                            ExpressionUtils.extractValue(alias, String.class)
                                                    .orElseThrow(
                                                            () ->
                                                                    new ValidationException(
                                                                            "Unexpected alias: "
                                                                                    + alias)))
                            .collect(toList());

            if (!isFunctionOfKind(children.get(0), FunctionKind.TABLE)) {
                throw fail();
            }

            CallExpression tableCall = (CallExpression) children.get(0);
            return createFunctionCall(tableCall, aliases, tableCall.getResolvedChildren());
        }

        private CorrelatedFunctionQueryOperation createFunctionCall(
                CallExpression callExpression,
                List<String> aliases,
                List<ResolvedExpression> parameters) {

            final ResolvedSchema resolvedSchema =
                    adjustNames(
                            extractSchema(callExpression.getOutputDataType()),
                            aliases,
                            callExpression.getFunctionName());

            return new CorrelatedFunctionQueryOperation(
                    ContextResolvedFunction.fromCallExpression(callExpression),
                    parameters,
                    resolvedSchema);
        }

        private ResolvedSchema extractSchema(DataType resultDataType) {
            if (LogicalTypeChecks.isCompositeType(resultDataType.getLogicalType())) {
                return DataTypeUtils.expandCompositeTypeToSchema(resultDataType);
            }

            int i = 0;
            String fieldName = ATOMIC_FIELD_NAME;
            while (leftTableFieldNames.contains(fieldName)) {
                fieldName = ATOMIC_FIELD_NAME + "_" + i++;
            }
            return ResolvedSchema.physical(
                    Collections.singletonList(fieldName),
                    Collections.singletonList(resultDataType));
        }

        private ResolvedSchema adjustNames(
                ResolvedSchema resolvedSchema, List<String> aliases, String functionName) {
            int aliasesSize = aliases.size();
            if (aliasesSize == 0) {
                return resolvedSchema;
            }

            int callArity = resolvedSchema.getColumnCount();
            if (callArity != aliasesSize) {
                throw new ValidationException(
                        String.format(
                                "List of column aliases must have same degree as table; "
                                        + "the returned table of function '%s' has "
                                        + "%d columns, whereas alias list has %d columns",
                                functionName, callArity, aliasesSize));
            }

            return ResolvedSchema.physical(aliases, resolvedSchema.getColumnDataTypes());
        }

        @Override
        protected CorrelatedFunctionQueryOperation defaultMethod(ResolvedExpression expression) {
            throw fail();
        }

        private ValidationException fail() {
            return new ValidationException(
                    "A lateral join only accepts an expression which defines a table function "
                            + "call that might be followed by some alias.");
        }
    }
}
