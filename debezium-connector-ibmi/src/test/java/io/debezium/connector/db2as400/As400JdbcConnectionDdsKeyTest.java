/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.DatabaseMetaData;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.DdsKeys.DdsKey;
import io.debezium.relational.TableId;

/**
 * The order in which a table's key is resolved during schema discovery. The DDS keyed access path is
 * only an identity when the file requires unique key values (issue #96), and the resolution has to stay
 * the one the journal decoder reaches for the same table - a snapshot keyed differently from the stream
 * that continues it leaves a merged destination with two generations of rows it cannot reconcile.
 *
 * <p>Driven through a {@code CALLS_REAL_METHODS} mock because {@link As400JdbcConnection}'s constructor
 * eagerly resolves the real database name and would otherwise require a live AS400.
 */
class As400JdbcConnectionDdsKeyTest {

    private static final TableId ORDERS = new TableId(null, "MYLIB", "ORDERS");

    private final As400JdbcConnection connection = mock(As400JdbcConnection.class, CALLS_REAL_METHODS);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);

    private void given(List<String> sqlPrimaryKey, DdsKey ddsKey, List<String> uniqueIndex) throws Exception {
        doReturn(sqlPrimaryKey).when(connection).readPrimaryKeyNames(metadata, ORDERS);
        doReturn(ddsKey).when(connection).readDdsKey(ORDERS);
        doReturn(uniqueIndex).when(connection).readTableUniqueIndices(metadata, ORDERS);
        doReturn(List.of("LONG_UIDX")).when(connection).mapSystemColumnNamesToLongNames(ORDERS, uniqueIndex);
        doReturn(true).when(connection).isUniqueDdsKeyRequired();
    }

    /** A real SQL primary key settles it; the catalog read for the access path is never made. */
    @Test
    void aSqlPrimaryKeyWins() throws Exception {
        given(List.of("ORDER_ID"), new DdsKey(List.of("K"), false), List.of("UIDX"));

        assertThat(connection.readPrimaryKeyOrUniqueIndexNames(metadata, ORDERS)).containsExactly("ORDER_ID");
        verify(connection, never()).readDdsKey(any());
    }

    @Test
    void aUniqueDdsKeyIsUsed() throws Exception {
        given(List.of(), new DdsKey(List.of("COMPANY", "ORDER_NO"), true), List.of("UIDX"));

        assertThat(connection.readPrimaryKeyOrUniqueIndexNames(metadata, ORDERS))
                .containsExactly("COMPANY", "ORDER_NO");
    }

    /**
     * The regression guard. Before the fix a duplicate-allowing access path was taken as the key, which
     * made every keyed destination keep one record per key value.
     */
    @Test
    void aDuplicateAllowingDdsKeyLeavesTheTableUnkeyed() throws Exception {
        given(List.of(), new DdsKey(List.of("ACCOUNT", "POSTED_ON"), false), List.of("UIDX"));

        assertThat(connection.readPrimaryKeyOrUniqueIndexNames(metadata, ORDERS)).isEmpty();
    }

    /**
     * Refusing the access path must not silently promote a unique index in its place: the journal decoder
     * has no such fallback, so the stream would key the same table differently from the snapshot.
     */
    @Test
    void aRefusedDdsKeyDoesNotFallThroughToAUniqueIndex() throws Exception {
        given(List.of(), new DdsKey(List.of("ACCOUNT"), false), List.of("UIDX"));

        assertThat(connection.readPrimaryKeyOrUniqueIndexNames(metadata, ORDERS)).isEmpty();
        verify(connection, never()).mapSystemColumnNamesToLongNames(any(), any());
    }

    /** A file with no keyed access path at all still reaches the unique indices, as it always did. */
    @Test
    void aFileWithNoAccessPathStillFallsThroughToAUniqueIndex() throws Exception {
        given(List.of(), new DdsKey(List.of(), false), List.of("UIDX"));

        assertThat(connection.readPrimaryKeyOrUniqueIndexNames(metadata, ORDERS)).containsExactly("LONG_UIDX");
    }

    /** The opt-out restores the old resolution for operators who know their duplicate-allowing key is unique. */
    @Test
    void theOptOutKeepsADuplicateAllowingDdsKey() throws Exception {
        given(List.of(), new DdsKey(List.of("ACCOUNT", "POSTED_ON"), false), List.of("UIDX"));
        doReturn(false).when(connection).isUniqueDdsKeyRequired();

        assertThat(connection.readPrimaryKeyOrUniqueIndexNames(metadata, ORDERS))
                .containsExactly("ACCOUNT", "POSTED_ON");
    }
}
