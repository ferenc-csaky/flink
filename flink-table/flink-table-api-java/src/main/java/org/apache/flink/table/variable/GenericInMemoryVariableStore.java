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

import org.apache.flink.annotation.Internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Internal
public class GenericInMemoryVariableStore extends AbstractVariableStore {

    private final Map<String, String> variables;

    public GenericInMemoryVariableStore() {
        this(new HashMap<>());
    }

    private GenericInMemoryVariableStore(Map<String, String> variables) {
        this.variables = variables;
    }

    public static GenericInMemoryVariableStore of(Map<String, String> variables) {
        return new GenericInMemoryVariableStore(variables);
    }

    @Override
    public void storeVariable(String key, String value) {
        variables.put(key, value);
    }

    @Override
    public void removeVariable(String key) {
        variables.remove(key);
    }

    @Override
    public Optional<String> getVariable(String key) {
        return Optional.ofNullable(variables.get(key));
    }

    @Override
    public Map<String, String> getAllVariables() {
        return Collections.unmodifiableMap(variables);
    }

    @Override
    public void reset() {
        variables.clear();
    }
}
