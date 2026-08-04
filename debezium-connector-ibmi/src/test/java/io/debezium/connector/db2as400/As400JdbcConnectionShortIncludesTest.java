/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.FileFilter;

/**
 * Covers the handling of {@code table.include.list} entries the journal cannot capture, both detectable
 * from the SQL catalog without an RPC call: a table that does not exist is always dropped (Debezium
 * ignores include-list entries with no matching table), an SQL view only with {@code errors.tolerance=all}.
 *
 * <p>Exercised through a {@code CALLS_REAL_METHODS} mock — {@link As400JdbcConnection}'s constructor
 * eagerly resolves the real database name and would otherwise require a live AS400.</p>
 */
public class As400JdbcConnectionShortIncludesTest {

    private final As400JdbcConnection connection = mock(As400JdbcConnection.class, CALLS_REAL_METHODS);

    @Test
    void tolerant_mode_skips_missing_tables_and_views() throws Exception {
        // Given a library holding a table, a dropped table and an SQL view
        doReturn("T").when(connection).getTableType("LIB1", "T1");
        doReturn(null).when(connection).getTableType("LIB1", "GONE");
        doReturn("V").when(connection).getTableType("LIB1", "VUE1");
        doReturn(Optional.of("T1")).when(connection).getSystemName("LIB1", "T1");

        // When translating the include list with errors.tolerance=all
        List<FileFilter> includes = connection.shortIncludes("LIB1", "LIB1.T1,LIB1.GONE,LIB1.VUE1", true);

        // Then only the capturable table reaches the journal filters
        assertThat(includes).containsExactly(new FileFilter("LIB1", "T1"));
    }

    @Test
    void tolerant_mode_keeps_the_table_when_the_catalog_lookup_fails() throws Exception {
        // Given a catalog lookup that blows up rather than answering
        doThrow(new SQLException("catalog unavailable")).when(connection).getTableType("LIB1", "T1");
        doReturn(Optional.of("T1")).when(connection).getSystemName("LIB1", "T1");

        // When translating the include list with errors.tolerance=all
        List<FileFilter> includes = connection.shortIncludes("LIB1", "LIB1.T1", true);

        // Then the table is kept - a catalog hiccup must not silently drop a capturable table
        assertThat(includes).containsExactly(new FileFilter("LIB1", "T1"));
    }

    @Test
    void default_mode_still_drops_a_missing_table_but_keeps_a_view() throws Exception {
        // Given a library holding a table, a dropped table and an SQL view, with errors.tolerance left at none
        doReturn("T").when(connection).getTableType("LIB1", "T1");
        doReturn(null).when(connection).getTableType("LIB1", "GONE");
        doReturn("V").when(connection).getTableType("LIB1", "VUE1");
        doReturn(Optional.of("T1")).when(connection).getSystemName("LIB1", "T1");
        doReturn(Optional.of("VUE10002")).when(connection).getSystemName("LIB1", "VUE1");

        // When translating the include list
        List<FileFilter> includes = connection.shortIncludes("LIB1", "LIB1.T1,LIB1.GONE,LIB1.VUE1", false);

        // Then the table that does not exist is dropped as Debezium drops it everywhere else, while the view
        // is kept and fails later, loudly, when its journal is resolved
        assertThat(includes).containsExactly(new FileFilter("LIB1", "T1"), new FileFilter("LIB1", "VUE10002"));
    }
}
