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
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.table.api.SqlParserException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Internal
public class VariableManager {

    private static final Logger LOG = LoggerFactory.getLogger(VariableManager.class);

    private static final int DEFAULT_MAX_VARIABLE_COUNT = 50;
    private static final Character PROPERTY_GUARD = '\'';

    /* Pattern to match "${key}" style variable syntax. */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{[^}$ ]+}");

    private final VariableStore variableStore;
    private final int maxVariableCount;

    public VariableManager() {
        this(new GenericInMemoryVariableStore(), DEFAULT_MAX_VARIABLE_COUNT);
    }

    public VariableManager(VariableStore variableStore, int maxVariableCount) {
        this.variableStore = variableStore;
        this.maxVariableCount = maxVariableCount;
    }

    /**
     * Resolves all variables in an SQL statement, if there are any. The resolved values are always
     * escaped.
     *
     * @param statement given SQL statement
     * @return statement with the resolved variables, if no variables present
     * @throws SqlParserException if a referenced variable does not exist
     */
    public String resolveVariables(String statement) {
        final Matcher match = VAR_PATTERN.matcher(statement);

        String resolvedStmt = statement;
        for (int i = 0; match.find() && i < maxVariableCount; ++i) {
            final String variable = match.group();
            final String variableValue = getValue(variable);
            final Tuple2<Character, Character> prevNextChars =
                    getPrevAndNextChars(resolvedStmt, match);

            resolvedStmt =
                    resolvedStmt.substring(0, match.start())
                            + escapeIfNeeded(variableValue, prevNextChars)
                            + resolvedStmt.substring(match.end());

            match.reset(resolvedStmt);
        }

        LOG.debug("Resolved statement: {}", resolvedStmt);
        return resolvedStmt;
    }

    public void add(String key, String value) {
        variableStore.storeVariable(key, value);
    }

    public void remove(String key) {
        variableStore.removeVariable(key);
    }

    public void reset() {
        variableStore.reset();
    }

    public Map<String, String> getAll() {
        return variableStore.getAllVariables();
    }

    private String getValue(String variable) {
        // Exclude '${' prefix and '}' postfix.
        final String key = variable.substring(2, variable.length() - 1);
        return variableStore
                .getVariable(key)
                .orElseThrow(
                        () ->
                                new SqlParserException(
                                        String.format("Variable '%s' does not exist.", variable)));
    }

    private Tuple2<Character, Character> getPrevAndNextChars(String statement, Matcher match) {
        int prevPos = match.start() - 1;
        int nextPos = match.end() + 1;

        Character prev = null;
        Character next = null;

        if (prevPos >= 0) {
            prev = statement.charAt(prevPos);
        }

        if (nextPos < statement.length()) {
            next = statement.charAt(nextPos);
        }

        return Tuple2.of(prev, next);
    }

    private String escapeIfNeeded(String value, Tuple2<Character, Character> prevPostChars) {
        return PROPERTY_GUARD.equals(prevPostChars.f0) || PROPERTY_GUARD.equals(prevPostChars.f1)
                ? value
                : '`' + value + '`';
    }
}
