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

package org.apache.flink.table.variable;

import org.apache.flink.annotation.PublicEvolving;

import java.util.Map;
import java.util.Optional;

/** Storage to persist SQL variables. */
@PublicEvolving
public interface VariableStore {

    /**
     * Stores a variable with the given {@code key} and {@code value}. If a variable already exists
     * under the same {@code key}, its {@code value} will be overwritten.
     *
     * @param key variable key
     * @param value variable value
     */
    void storeVariable(String key, String value);

    /**
     * Removes a variable according to the given {@code key}. If the no such variable exists,
     * nothing happens.
     *
     * @param key variable key
     */
    void removeVariable(String key);

    /**
     * Retrieves the value of a variable by its {@code key}.
     *
     * @param key variable key
     * @return the requested variable value or empty
     */
    Optional<String> getVariable(String key);

    /**
     * Retrieves all stored variables as key-value pairs.
     *
     * @return a {@link Map} of all stored variables.
     */
    Map<String, String> getAllVariables();

    /** Removes all stored variables. */
    void reset();

    /** Opens the variable store, handling any necessary initialization steps. */
    void open();

    /** Closes the variable store, handling any necessary tear-down steps. */
    void close();
}
