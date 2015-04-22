/*
 * Copyright 2013-2015, Teradata, Inc. All rights reserved.
 */

package com.teradata.test.assertions;

import com.teradata.test.internal.query.QueryResultValueComparator;
import com.teradata.test.query.QueryExecutionException;
import com.teradata.test.query.QueryExecutor;
import com.teradata.test.query.QueryResult;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.slf4j.Logger;

import java.sql.JDBCType;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.common.collect.Lists.newArrayList;
import static com.teradata.test.assertions.QueryAssert.Row.row;
import static com.teradata.test.query.QueryResult.fromSqlIndex;
import static com.teradata.test.query.QueryResult.toSqlIndex;
import static java.lang.String.format;
import static java.sql.JDBCType.INTEGER;
import static java.util.Optional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;

public class QueryAssert
        extends AbstractAssert<QueryAssert, QueryResult>
{

    private static final Logger LOGGER = getLogger(QueryExecutor.class);

    private static final NumberFormat DECIMAL_FORMAT = new DecimalFormat("#0.00000000000");

    private final List<Comparator<Object>> columnComparators;

    protected QueryAssert(QueryResult actual, List<Comparator<Object>> columnComparators)
    {
        super(actual, QueryAssert.class);
        this.columnComparators = columnComparators;
    }

    public static QueryAssert assertThat(QueryResult queryResult)
    {
        List<Comparator<Object>> comparators = getComparators(queryResult);
        return new QueryAssert(queryResult, comparators);
    }

    public static QueryExecutionAssert assertThat(QueryCallback queryCallback)
    {
        QueryExecutionException executionException = null;
        try {
            queryCallback.executeQuery();
        }
        catch (QueryExecutionException e) {
            executionException = e;
        }
        return new QueryExecutionAssert(ofNullable(executionException));
    }

    public QueryAssert hasRowsCount(int resultCount)
    {
        if (actual.getRowsCount() != resultCount) {
            failWithMessage("Expected row count to be <%s>, but was <%s>; rows=%s", resultCount, actual.getRowsCount(), actual.rows());
        }
        return this;
    }

    public QueryAssert hasNoRows()
    {
        return hasRowsCount(0);
    }

    public QueryAssert hasAnyRows()
    {
        if (actual.getRowsCount() == 0) {
            failWithMessage("Expected some rows to be returned from query");
        }
        return this;
    }

    public QueryAssert hasColumnsCount(int columnCount)
    {
        if (actual.getColumnsCount() != columnCount) {
            failWithMessage("Expected column count to be <%s>, but was <%s> - columns <%s>", columnCount, actual.getColumnsCount(), actual.getColumnTypes());
        }
        return this;
    }

    public QueryAssert hasColumns(List<JDBCType> expectedTypes)
    {
        hasColumnsCount(expectedTypes.size());
        for (int i = 0; i < expectedTypes.size(); i++) {
            JDBCType expectedType = expectedTypes.get(i);
            JDBCType actualType = actual.getColumnType(toSqlIndex(i));

            if (!actualType.equals(expectedType)) {
                failWithMessage("Expected <%s> column of type <%s>, but was <%s>, actual columns: %s", i, expectedType, actualType, actual.getColumnTypes());
            }
        }
        return this;
    }

    public QueryAssert hasColumns(JDBCType... expectedTypes)
    {
        return hasColumns(Arrays.asList(expectedTypes));
    }

    /**
     * Verifies that the actual result set contains all the given {@code rows}
     */
    public QueryAssert contains(List<Row> rows)
    {
        List<List<Object>> missingRows = newArrayList();
        for (Row row : rows) {
            List<Object> expectedRow = row.getValues();

            if (!containsRow(expectedRow)) {
                missingRows.add(expectedRow);
            }
        }

        if (!missingRows.isEmpty()) {
            failWithMessage(buildContainsMessage(missingRows));
        }

        return this;
    }

    /**
     * @see #contains(java.util.List)
     */
    public QueryAssert contains(Row... rows)
    {
        return contains(Arrays.asList(rows));
    }

    /**
     * Verifies that the actual result set consist of only {@code rows} in any order
     */
    public QueryAssert containsOnly(List<Row> rows)
    {
        hasRowsCount(rows.size());
        contains(rows);

        return this;
    }

    /**
     * @see #containsOnly(java.util.List)
     */
    public QueryAssert containsOnly(Row... rows)
    {
        return containsOnly(Arrays.asList(rows));
    }

    /**
     * Verifies that the actual result set equals to {@code rows}.
     * ResultSet in different order or with any extra rows perceived as not same
     */
    public QueryAssert containsExactly(List<Row> rows)
    {
        hasRowsCount(rows.size());
        List<Integer> unequalRowsIndexes = newArrayList();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<Object> expectedRow = rows.get(rowIndex).getValues();
            List<Object> actualRow = actual.row(rowIndex);

            if (!rowsEqual(expectedRow, actualRow)) {
                unequalRowsIndexes.add(rowIndex);
            }
        }

        if (!unequalRowsIndexes.isEmpty()) {
            failWithMessage(buildContainsExactlyErrorMessage(unequalRowsIndexes, rows));
        }

        return this;
    }

    /**
     * @see #containsExactly(java.util.List)
     */
    public QueryAssert containsExactly(Row... rows)
    {
        return containsExactly(Arrays.asList(rows));
    }

    /**
     * Verifies number of rows updated/inserted by last update query
     */
    public QueryAssert updatedRowsCountIsEqualTo(int count)
    {
        hasRowsCount(1);
        hasColumnsCount(1);
        hasColumns(INTEGER);
        containsExactly(row(count));
        return this;
    }

    private static List<Comparator<Object>> getComparators(QueryResult queryResult)
    {
        return queryResult.getColumnTypes().stream()
                .map(QueryResultValueComparator::comparatorForType)
                .collect(Collectors.toList());
    }

    private String buildContainsMessage(List<List<Object>> missingRows)
    {
        StringBuilder msg = new StringBuilder("Could not find rows:");
        appendRows(msg, missingRows);
        msg.append("\n\nactual rows:");
        appendRows(msg, actual.rows());
        return msg.toString();
    }

    private void appendRows(StringBuilder msg, List<List<Object>> rows)
    {
        rows.stream().forEach(row -> msg.append('\n').append(row));
    }

    private String buildContainsExactlyErrorMessage(List<Integer> unequalRowsIndexes, List<Row> rows)
    {
        StringBuilder msg = new StringBuilder("Not equal rows:");
        for (Integer unequalRowsIndex : unequalRowsIndexes) {
            int unequalRowIndex = unequalRowsIndex;
            msg.append('\n');
            msg.append(unequalRowIndex);
            msg.append(" - expected: ");
            formatRow(msg, rows.get(unequalRowIndex).getValues());
            msg.append('\n');
            msg.append(unequalRowIndex);
            msg.append(" - actual:   ");
            formatRow(msg, actual.row(unequalRowIndex));
        }
        return msg.toString();
    }

    private void formatRow(StringBuilder msg, List<Object> rowValues)
    {
        msg.append('<');
        for (Object rowValue : rowValues) {
            if (rowValue instanceof Double || rowValue instanceof Float) {
                msg.append(DECIMAL_FORMAT.format(rowValue));
            }
            else if (rowValue != null) {
                msg.append(rowValue.toString());
            }
            msg.append('|');
        }
        msg.append('>');
    }

    private boolean containsRow(List<Object> expectedRow)
    {
        for (int i = 0; i < actual.getRowsCount(); i++) {
            if (rowsEqual(actual.row(i), expectedRow)) {
                return true;
            }
        }
        return false;
    }

    private boolean rowsEqual(List<Object> expectedRow, List<Object> actualRow)
    {
        for (int i = 0; i < expectedRow.size(); ++i) {
            Object expectedValue = expectedRow.get(i);
            Object actualValue = actualRow.get(i);

            if (columnComparators.get(i).compare(expectedValue, actualValue) != 0) {
                return false;
            }
        }
        return true;
    }

    public <T> QueryAssert column(int columnIndex, JDBCType type, ColumnValuesAssert<T> columnValuesAssert)
    {
        if (fromSqlIndex(columnIndex) > actual.getColumnsCount()) {
            failWithMessage("Result contains only <%s> columns, extracting column <%s>",
                    actual.getColumnsCount(), columnIndex);
        }

        JDBCType actualColumnType = actual.getColumnType(columnIndex);
        if (!type.equals(actualColumnType)) {
            failWithMessage("Expected <%s> column, to be type: <%s>, but was: <%s>", columnIndex, type, actualColumnType);
        }

        List<T> columnValues = actual.column(columnIndex);

        columnValuesAssert.assertColumnValues(Assertions.assertThat(columnValues));

        return this;
    }

    public <T> QueryAssert column(String columnName, JDBCType type, ColumnValuesAssert<T> columnValuesAssert)
    {
        Optional<Integer> index = actual.tryFindColumnIndex(columnName);
        if (!index.isPresent()) {
            failWithMessage("No column with name: <%s>", columnName);
        }

        return column(index.get(), type, columnValuesAssert);
    }

    @FunctionalInterface
    public static interface QueryCallback
    {
        QueryResult executeQuery()
                throws QueryExecutionException;
    }

    public static class QueryExecutionAssert
    {

        private Optional<QueryExecutionException> executionExceptionOptional;

        public QueryExecutionAssert(Optional<QueryExecutionException> executionExceptionOptional)
        {
            this.executionExceptionOptional = executionExceptionOptional;
        }

        public QueryExecutionAssert failsWithMessage(String... expectedErrorMessages)
        {

            if (!executionExceptionOptional.isPresent()) {
                throw new AssertionError("Query did not fail as expected.");
            }

            QueryExecutionException executionException = executionExceptionOptional.get();

            String exceptionMessage = executionException.getMessage();
            LOGGER.debug("Query failed as expected with message: {}", exceptionMessage);
            for (String expectedErrorMessage : expectedErrorMessages) {
                if (!exceptionMessage.contains(expectedErrorMessage)) {
                    throw new AssertionError(format(
                            "Query failed with unexpected error message: '%s' \n Expected error message was '%s'",
                            exceptionMessage,
                            expectedErrorMessage
                    ));
                }
            }

            return this;
        }
    }

    public static class Row
    {

        private final List<Object> values;

        private Row(Object... values)
        {
            this.values = newArrayList(values);
        }

        public List<Object> getValues()
        {
            return values;
        }

        public static Row row(Object... values)
        {
            return new Row(values);
        }
    }
}
