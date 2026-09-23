/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.debezium.jdbc.JdbcConnection.BlockingResultSetConsumer;
import io.debezium.jdbc.JdbcConnection.StatementPreparer;

/**
 * Verifies that the long-name/system-name prefetch is scoped to the captured tables rather than to the
 * whole schema. It used to run {@code select ... from qsys2.systables where system_table_schema=?} with no
 * table predicate at all, so schema discovery scaled with the size of the source library instead of with
 * {@code table.include.list} - 462 and 722 rows for ~41 captured tables on the deployment in issue #50,
 * about 25s of round trips on every connector start.
 *
 * <p>Since issue #71 the same query also carries the table type, for {@code shortIncludes} at task start.
 *
 * <p>The query is executed through {@code prepareQueryWithBlockingConsumer}, which is stubbed here to
 * capture the statement text and the bound parameters; {@link As400JdbcConnection}'s constructor resolves
 * the real database name eagerly and would otherwise need a live AS400.
 */
class As400JdbcConnectionSystemNamesTest {

    private final As400JdbcConnection connection = mock(As400JdbcConnection.class, CALLS_REAL_METHODS);
    private final List<String> statements = new ArrayList<>();
    private final List<Map<Integer, String>> boundParameters = new ArrayList<>();

    /** {@code CALLS_REAL_METHODS} skips the constructor, so the cache maps are null. */
    @BeforeEach
    void giveTheMockItsCaches() throws Exception {
        for (final String name : List.of("systemToLongTableName", "longToSystemTableName", "tableTypeByUpperName")) {
            final Field field = As400JdbcConnection.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(connection, new HashMap<>());
        }
    }

    /** Records the statement and its parameters, answering with the given {@code (system name, long name, type)} rows. */
    private void captureQueries(String[]... rows) throws Exception {
        doAnswer(invocation -> {
            statements.add(invocation.getArgument(0));
            final Map<Integer, String> bound = new LinkedHashMap<>();
            final PreparedStatement statement = mock(PreparedStatement.class);
            doAnswer(set -> bound.put(set.getArgument(0), set.getArgument(1)))
                    .when(statement).setString(anyInt(), anyString());
            invocation.getArgument(1, StatementPreparer.class).accept(statement);
            boundParameters.add(bound);
            invocation.getArgument(2, BlockingResultSetConsumer.class).accept(resultSetOf(rows));
            return connection;
        }).when(connection).prepareQueryWithBlockingConsumer(anyString(), any(), any());
    }

    private static ResultSet resultSetOf(String[]... rows) throws Exception {
        final ResultSet resultSet = mock(ResultSet.class);
        final Iterator<String[]> remaining = Arrays.asList(rows).iterator();
        final String[][] current = { null };
        doAnswer(next -> remaining.hasNext() && (current[0] = remaining.next()) != null).when(resultSet).next();
        doAnswer(get -> current[0][get.getArgument(0, Integer.class) - 1]).when(resultSet).getString(anyInt());
        return resultSet;
    }

    @Test
    void theQueryIsRestrictedToTheTablesAskedFor() throws Exception {
        captureQueries();

        connection.getAllSystemNames("MYLIB", Set.of("ORDERS", "CUSTOMERS"));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0))
                .contains("system_table_schema=?")
                // both names, because the captured set may hold either one
                .contains("upper(table_name) in (?, ?)")
                .contains("upper(replace(system_table_name, '\"', '')) in (?, ?)");

        // the schema, then the table names once per column, upper-cased for the upper() comparisons
        final Map<Integer, String> bound = boundParameters.get(0);
        assertThat(bound).hasSize(5);
        assertThat(bound.get(1)).isEqualTo("MYLIB");
        assertThat(List.of(bound.get(2), bound.get(3))).containsExactlyInAnyOrder("ORDERS", "CUSTOMERS");
        assertThat(List.of(bound.get(4), bound.get(5))).containsExactlyInAnyOrder("ORDERS", "CUSTOMERS");
    }

    @Test
    void tableNamesAreUpperCasedToMatchTheCatalogComparison() throws Exception {
        captureQueries();

        connection.getAllSystemNames("MYLIB", Set.of("orders"));

        assertThat(boundParameters.get(0).values()).containsExactly("MYLIB", "ORDERS", "ORDERS");
    }

    /**
     * A whole-library include list would otherwise put thousands of bind parameters into one statement.
     */
    @Test
    void aLargeCapturedSetIsSplitIntoBatches() throws Exception {
        captureQueries();
        final Set<String> tables = IntStream.range(0, 1_200).mapToObj(i -> "T" + i)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        connection.getAllSystemNames("MYLIB", tables);

        // 500 + 500 + 200, and every table is asked about exactly once per column
        assertThat(statements).hasSize(3);
        assertThat(boundParameters.stream().map(Map::size)).containsExactly(1_001, 1_001, 401);
        final List<String> namesAsked = boundParameters.stream()
                .flatMap(bound -> bound.entrySet().stream().filter(e -> e.getKey() > 1).map(Map.Entry::getValue))
                .toList();
        assertThat(namesAsked).hasSize(2_400).containsAll(tables);
    }

    @Test
    void nothingIsQueriedWhenNoTableIsCaptured() throws Exception {
        captureQueries();

        connection.getAllSystemNames("MYLIB", Set.of());

        verify(connection, never()).prepareQueryWithBlockingConsumer(anyString(), any(), any());
    }

    /**
     * A duplicate would double the placeholders for no gain; the captured set can hold a table under both
     * its long and its system name.
     */
    @Test
    void duplicateNamesAreAskedAboutOnce() throws Exception {
        captureQueries();

        connection.getAllSystemNames("MYLIB", List.of("ORDERS", "orders", "ORDERS"));

        assertThat(statements.get(0)).contains("upper(table_name) in (?)");
        assertThat(boundParameters.get(0).values()).containsExactly("MYLIB", "ORDERS", "ORDERS");
    }

    @Test
    void theTableTypeComesBackWithTheNamesAndAnswersTheTypeLookupWithoutARoundTrip() throws Exception {
        captureQueries(new String[]{ "ORDERS", "ORDERS", "T" }, new String[]{ "\"Vue10002\"", "Vue1", "V" });

        connection.getAllSystemNames("MYLIB", Set.of("ORDERS", "VUE1"));

        assertThat(statements.get(0)).contains("trim(table_type)");
        // by either name, in any case, as the per-table query matches
        assertThat(connection.getTableType("mylib", "orders")).isEqualTo("T");
        assertThat(connection.getTableType("MYLIB", "VUE10002")).isEqualTo("V");
        assertThat(connection.getSystemName("MYLIB", "ORDERS")).isEqualTo(Optional.of("ORDERS"));
        verify(connection, never()).prepareQueryAndMap(anyString(), any(), any());
    }

    /** The snapshot asks again after task start did; only what is still unresolved is queried, so a missing table is never cached as missing. */
    @Test
    void tablesAlreadyResolvedAreNotAskedAboutAgain() throws Exception {
        captureQueries(new String[]{ "ORDERS", "ORDERS", "T" });
        connection.getAllSystemNames("MYLIB", Set.of("ORDERS", "GONE"));

        connection.getAllSystemNames("MYLIB", Set.of("ORDERS"));
        connection.getAllSystemNames("MYLIB", Set.of("ORDERS", "GONE", "NEW"));

        assertThat(statements).hasSize(2);
        assertThat(boundParameters.get(1).values()).containsExactlyInAnyOrder("MYLIB", "GONE", "NEW", "GONE", "NEW");
    }
}
