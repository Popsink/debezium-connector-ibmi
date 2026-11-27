/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;

/**
 * A file dropped after its entries were journalled has no column left in the catalogue: its entries fail, so the
 * streaming loop skips them with their row image, instead of being delivered as records with no column
 * (Popsink/data-plane#6408).
 */
class JdbcFileDecoderDroppedFileTest {

    private final SchemaCacheHash cache = new SchemaCacheHash();

    /** A decoder whose catalogue no longer lists LIB.T: no long name, no column. */
    private JdbcFileDecoder decoderOverADroppedFile() throws SQLException {
        final Connection connection = mock(Connection.class, RETURNS_DEEP_STUBS);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class, RETURNS_DEEP_STUBS);
        when(connection.getMetaData()).thenReturn(metadata);
        final ResultSet noColumn = mock(ResultSet.class);
        when(noColumn.next()).thenReturn(false);
        when(metadata.getColumns(any(), eq("LIB"), eq("T"), any())).thenReturn(noColumn);
        return new JdbcFileDecoder(() -> connection, "DB", cache, As400TextFactory.forCcsid(37), -1, -1);
    }

    @Test
    void aFileWithNoColumnHasNoRecordFormatAndNothingIsCached() throws SQLException {
        // Given a file the catalogue lists no column for, When its format is asked, Then none, and none cached
        assertTrue(decoderOverADroppedFile().getRecordFormat("T", "LIB").isEmpty());
        assertNull(cache.retrieve("DB", "LIB", "T"), "a column-less format would decode every later entry empty");
    }

    @Test
    void anEntryOfAFileWithNoRecordFormatFailsInsteadOfDecodingEmpty() throws SQLException {
        // Given an insert entry of a dropped file, When decoded, Then it throws, naming the file
        final String object = String.format("%-10s%-10s%-10s", "T", "LIB", "T");
        final EntryHeader insert = new EntryHeader(0, 0, 0, BigInteger.ONE, BigInteger.ZERO, Instant.EPOCH, 'R', "PT",
                object, BigInteger.ZERO, 0, 13, "", "", BigInteger.ONE);

        // a 15-byte record image, its length in EBCDIC digits ("00015") where the entry-specific data starts
        final byte[] entry = new byte[64];
        System.arraycopy(new byte[]{ (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF1, (byte) 0xF5 }, 0, entry, 0, 5);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> decoderOverADroppedFile().decodeFile(insert, entry, 0, null));
        assertTrue(thrown.getMessage().contains("LIB.T"), thrown.getMessage());
    }
}
