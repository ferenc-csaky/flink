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

package org.apache.flink.table.factories;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.variable.VariableStore;
import org.apache.flink.table.variable.VariableStoreException;

import java.util.Map;
import java.util.Optional;

/**
 * Factory to create and configure {@link VariableStore} instances based on string-based properties.
 * See also {@link Factory} for more information.
 */
@PublicEvolving
public interface VariableStoreFactory extends Factory {

    /** Creates a {@link VariableStore} instance from context information. */
    VariableStore createVariableStore();

    /** Opens the variable store factory, handling any necessary initialization steps. */
    void open(Context context) throws VariableStoreException;

    /** Closes the variable store factory, handling any necessary tear-down steps. */
    void close() throws VariableStoreException;

    /** Context provided during variable store creation. */
    @PublicEvolving
    interface Context {

        /**
         * Returns the options the variable store was created with.
         *
         * <p>Any implementation should validate these.
         */
        Map<String, String> getOptions();

        /** Gives read-only access to the configuration of the current session. */
        ReadableConfig getConfiguration();

        /** Returns preloaded variables if there are any. */
        Optional<Map<String, String>> getVariables();
    }
}
