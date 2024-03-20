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

package org.apache.flink.table.planner.operations.converters;

import org.apache.flink.sql.parser.ddl.SqlCreateView;
import org.apache.flink.sql.parser.ddl.SqlSetVariable;
import org.apache.flink.table.catalog.CatalogView;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.UnresolvedIdentifier;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.command.SetVariableOperation;
import org.apache.flink.table.operations.ddl.CreateViewOperation;
import org.apache.flink.table.planner.utils.OperationConverterUtils;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.util.NlsString;

import java.util.List;
import java.util.Map;

/** A converter for {@link SqlCreateView}. */
public class SqlSetVariableConverter implements SqlNodeConverter<SqlSetVariable> {

    @Override
    public Operation convertSqlNode(SqlSetVariable sqlSetVariable, ConvertContext context) {
        if (sqlSetVariable.getKey() == null && sqlSetVariable.getValue() == null) {
            return new SetVariableOperation();
        }

        return new SetVariableOperation(
                sqlSetVariable.getKeyString(), sqlSetVariable.getValueString());
    }
}
