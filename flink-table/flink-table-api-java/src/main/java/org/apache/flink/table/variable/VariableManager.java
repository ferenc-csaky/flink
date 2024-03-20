package org.apache.flink.table.variable;

import org.apache.flink.table.api.SqlParserException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VariableManager {

    private static final Logger LOG = LoggerFactory.getLogger(VariableManager.class);

    private static final int DEFAULT_MAX_VARIABLE_COUNT = 50;

    /* Pattern to match ${name} style variable syntax. */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{[^}$ ]+}");

    private final Map<String, String> userVariables;
    private final int maxVariableCount;

    public VariableManager() {
        this(new HashMap<>(), DEFAULT_MAX_VARIABLE_COUNT);
    }

    public VariableManager(Map<String, String> userVariables, int maxVariableCount) {
        this.userVariables = userVariables;
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
            final String escapedVal = getEscapedValue(variable);

            resolvedStmt =
                    resolvedStmt.substring(0, match.start())
                            + escapedVal
                            + resolvedStmt.substring(match.end());

            match.reset(resolvedStmt);
        }

        LOG.debug("Resolved statement: {}", resolvedStmt);
        return resolvedStmt;
    }

    public void add(String key, String value) {
        userVariables.put(key, value);
    }

    public void remove(String key) {
        userVariables.remove(key);
    }

    public void reset() {
        userVariables.clear();
    }

    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(userVariables);
    }

    private String getEscapedValue(String variable) {
        final String key = variable.substring(2, variable.length() - 1);
        return Optional.ofNullable(userVariables.get(key))
                .map(val -> '`' + val + '`')
                .orElseThrow(
                        () ->
                                new SqlParserException(
                                        String.format("Variable '%s' does not exist.", variable)));
    }
}
