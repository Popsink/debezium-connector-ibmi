/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.DdsKeys.DdsKey;
import io.debezium.ibmi.db2.journal.retrieve.DdsKeys.Uniqueness;

/**
 * A DDS keyed access path only identifies a record when the file was created with {@code UNIQUE};
 * otherwise IBM i lets any number of records share a key value and a keyed destination keeps just one
 * of them. These cover the whole decision: what the catalog says, what is done with it, and the
 * escape hatch for operators who know a duplicate-allowing key is unique in practice.
 */
class DdsKeysTest {

    /** Access path types as {@code SYSFILES.ACCESS_PATH_TYPE} spells them; any keyed type but UNIQUE allows duplicates. */
    private static final String UNIQUE = "KEYED UNIQUE";
    private static final String NO_ORDER = "KEYED NO ORDER";

    /** One key field of a file whose {@code SYSFILES.ACCESS_PATH_TYPE} is {@code type}, as the catalog returns it. */
    private static ResultSet keyedFile(String type, String... columns) throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        final Boolean[] more = new Boolean[columns.length];
        for (int i = 0; i < columns.length; i++) {
            more[i] = i < columns.length - 1;
        }
        if (columns.length == 0) {
            when(rs.next()).thenReturn(false);
            return rs;
        }
        when(rs.next()).thenReturn(true, more);
        final String[] rest = new String[columns.length - 1];
        for (int i = 1; i < columns.length; i++) {
            rest[i - 1] = columns[i] + "   ";
        }
        when(rs.getString(1)).thenReturn(columns[0] + "   ", rest);
        when(rs.getString(2)).thenReturn(type);
        return rs;
    }

    @Test
    void theQueryFiltersNothingButCarriesTheAccessPathType() {
        // The filtering is done in Java so the rejected key can be named in the log; the type must
        // therefore travel with the key columns rather than being read in a second round trip.
        assertTrue(DdsKeys.KEY_COLUMNS.contains("qsys2.SYSFILES"));
        assertTrue(DdsKeys.KEY_COLUMNS.contains("f.ACCESS_PATH_TYPE"));
        assertTrue(DdsKeys.KEY_COLUMNS.contains("f.SYSTEM_TABLE_SCHEMA=k.DBKLIB"));
        assertTrue(DdsKeys.KEY_COLUMNS.contains("f.SYSTEM_TABLE_NAME=k.DBKFIL"));
        assertTrue(DdsKeys.KEY_COLUMNS.contains("ORDER BY k.DBKPOS ASC"));
    }

    /** QADBXREF holds the same flag but needs an authority on the cross-reference file that PUB400, for one, refuses (SQL0551). */
    @Test
    void theQueryNeverTouchesTheCrossReferenceFile() {
        assertFalse(DdsKeys.KEY_COLUMNS.toUpperCase().contains("QADBXREF"));
    }

    @Test
    void readsTheKeyColumnsInKeyOrderTrimmed() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile(UNIQUE, "COMPANY", "ORDER_NO", "LINE_NO"));

        assertEquals(List.of("COMPANY", "ORDER_NO", "LINE_NO"), key.columns());
        assertTrue(key.unique());
    }

    @Test
    void aFileWithNoKeyedAccessPathReadsEmpty() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile(UNIQUE));

        assertEquals(List.of(), key.columns());
        assertFalse(key.unique());
        assertEquals(List.of(), key.asRecordKey(true, "LIB", "FILE"));
        assertEquals(List.of(), key.asRecordKey(false, "LIB", "FILE"));
    }

    /** {@code KEYED UNIQUE}: the key is an identity, so it keys the records whatever the setting says. */
    @Test
    void aUniqueKeyIsAlwaysUsed() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile(UNIQUE, "ORDER_NO"));

        assertTrue(key.unique());
        assertEquals(List.of("ORDER_NO"), key.asRecordKey(true, "LIB", "ORDERS"));
        assertEquals(List.of("ORDER_NO"), key.asRecordKey(false, "LIB", "ORDERS"));
    }

    /**
     * {@code KEYED NO ORDER}: the regression this guards. Keying on it told every destination that records
     * sharing the key value were versions of one row, so a merged table kept one of them - on the worst
     * file measured, 1.5 M rows out of 716.9 M records.
     */
    @Test
    void aDuplicateAllowingKeyIsDroppedByDefault() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile(NO_ORDER, "ACCOUNT", "POSTED_ON"));

        assertFalse(key.unique());
        assertEquals(List.of("ACCOUNT", "POSTED_ON"), key.columns());
        assertEquals(List.of(), key.asRecordKey(true, "LIB", "GLDETAIL"));
    }

    /** The escape hatch, for an operator who knows the duplicate-allowing key is unique in practice. */
    @Test
    void aDuplicateAllowingKeyIsKeptWhenUniquenessIsNotRequired() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile(NO_ORDER, "ACCOUNT", "POSTED_ON"));

        assertEquals(List.of("ACCOUNT", "POSTED_ON"), key.asRecordKey(false, "LIB", "GLDETAIL"));
    }

    /** Only the UNIQUE path is an identity: the ordered duplicate-allowing kinds, a blank and a null type are not. */
    @Test
    void theAccessPathTypeIsTrimmedAndAnythingButKeyedUniqueIsDuplicateAllowing() throws Exception {
        assertTrue(DdsKeys.read(keyedFile("KEYED UNIQUE ", "K")).unique());
        assertTrue(DdsKeys.read(keyedFile("keyed unique", "K")).unique());
        assertFalse(DdsKeys.read(keyedFile("KEYED FIFO", "K")).unique());
        assertFalse(DdsKeys.read(keyedFile("KEYED LIFO", "K")).unique());
        assertFalse(DdsKeys.read(keyedFile("KEYED FCFO", "K")).unique());
        assertFalse(DdsKeys.read(keyedFile(" ", "K")).unique());
        assertFalse(DdsKeys.read(keyedFile(null, "K")).unique());
    }

    // --- data-plane#5932: a release without QSYS2.SYSFILES (before IBM i 7.4 TR7 / 7.5 TR1) ---

    private static final SQLException SQL0204 = new SQLException("[SQL0204] SYSFILES in QSYS2 type *FILE not found.", "42704", -204);
    private static final SQLException SQL0551 = new SQLException("[SQL0551] Not authorized to object QADBXREF in QSYS type *FILE.", "42501", -551);

    private final Connection con = mock(Connection.class);

    /** A statement whose query answers {@code rs}, or fails with {@code failure} when it is not null. */
    private void answer(String query, SQLException failure, ResultSet rs) throws SQLException {
        if (failure != null) {
            when(con.prepareStatement(query)).thenThrow(failure);
            return;
        }
        final PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(con.prepareStatement(query)).thenReturn(ps);
    }

    @Test
    void aSystemWithSysfilesNeverTouchesTheOtherCatalogs() throws Exception {
        answer(DdsKeys.KEY_COLUMNS, null, keyedFile(UNIQUE, "ORDER_NO"));

        final DdsKey key = new DdsKeys.Reader().read(con, "LIB", "ORDERS");

        assertTrue(key.unique());
        verify(con, never()).prepareStatement(DdsKeys.KEY_COLUMNS_QADBXREF);
        verify(con, never()).prepareStatement(DdsKeys.KEY_COLUMNS_ONLY);
    }

    /** The crash loop: SQL0204 on SYSFILES failed every start. QADBXREF.DBXUNQ tells the same thing. */
    @Test
    void aMissingSysfilesFallsBackToTheCrossReferenceFile() throws Exception {
        answer(DdsKeys.KEY_COLUMNS, SQL0204, null);
        answer(DdsKeys.KEY_COLUMNS_QADBXREF, null, keyedFile("U", "ORDER_NO"));

        final DdsKey key = new DdsKeys.Reader().read(con, "LIB", "ORDERS");

        assertEquals(List.of("ORDER_NO"), key.columns());
        assertTrue(key.unique());
    }

    @Test
    void aDuplicateAllowingFileReadFromTheCrossReferenceFileIsNotUnique() throws Exception {
        answer(DdsKeys.KEY_COLUMNS, SQL0204, null);
        answer(DdsKeys.KEY_COLUMNS_QADBXREF, null, keyedFile("D", "ACCOUNT"));

        assertSame(Uniqueness.DUPLICATES_ALLOWED, new DdsKeys.Reader().read(con, "LIB", "GL").uniqueness());
    }

    /** The view's absence is a property of the system: one failing round trip per connection, not per table. */
    @Test
    void theMissingViewIsOnlyTriedOncePerReader() throws Exception {
        answer(DdsKeys.KEY_COLUMNS, SQL0204, null);
        final PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenAnswer(i -> keyedFile("U", "K"));
        when(con.prepareStatement(DdsKeys.KEY_COLUMNS_QADBXREF)).thenReturn(ps);
        final DdsKeys.Reader reader = new DdsKeys.Reader();

        reader.read(con, "LIB", "A");
        reader.read(con, "LIB", "B");
        reader.read(con, "LIB", "C");

        verify(con, times(1)).prepareStatement(DdsKeys.KEY_COLUMNS);
        verify(con, times(3)).prepareStatement(DdsKeys.KEY_COLUMNS_QADBXREF);
    }

    /** No SYSFILES and no authority on QADBXREF: the key columns alone, uniqueness unknown, never a failed start. */
    @Test
    void aRefusedCrossReferenceFileLeavesTheUniquenessUnknown() throws Exception {
        answer(DdsKeys.KEY_COLUMNS, SQL0204, null);
        answer(DdsKeys.KEY_COLUMNS_QADBXREF, SQL0551, null);
        final ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("ACCOUNT ", "POSTED_ON ");
        answer(DdsKeys.KEY_COLUMNS_ONLY, null, rs);

        final DdsKey key = new DdsKeys.Reader().read(con, "LIB", "GL");

        assertSame(Uniqueness.UNKNOWN, key.uniqueness());
        assertEquals(List.of("ACCOUNT", "POSTED_ON"), key.columns());
        assertEquals(List.of(), key.asRecordKey(true, "LIB", "GL"));
        assertEquals(List.of("ACCOUNT", "POSTED_ON"), key.asRecordKey(false, "LIB", "GL"));
    }

    /** Only a missing or refused catalog degrades; anything else (a lock, a dropped link) still fails loudly. */
    @Test
    void anyOtherFailureStillPropagates() throws Exception {
        final SQLException locked = new SQLException("[SQL0913] Row or object in use.", "57033", -913);
        when(con.prepareStatement(anyString())).thenThrow(locked);

        assertSame(locked, assertThrows(SQLException.class, () -> new DdsKeys.Reader().read(con, "LIB", "GL")));
        verify(con, never()).prepareStatement(DdsKeys.KEY_COLUMNS_QADBXREF);
    }

    @Test
    void aRefusedSysfilesIsNotMistakenForAMissingOne() throws Exception {
        answer(DdsKeys.KEY_COLUMNS, SQL0551, null);

        assertThrows(SQLException.class, () -> new DdsKeys.Reader().read(con, "LIB", "GL"));
    }
}
