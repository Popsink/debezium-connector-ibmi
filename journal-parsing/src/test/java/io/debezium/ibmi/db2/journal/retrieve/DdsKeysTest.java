/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.DdsKeys.DdsKey;

/**
 * A DDS keyed access path only identifies a record when the file was created with {@code UNIQUE};
 * otherwise IBM i lets any number of records share a key value and a keyed destination keeps just one
 * of them. These cover the whole decision: what the catalog says, what is done with it, and the
 * escape hatch for operators who know a duplicate-allowing key is unique in practice.
 */
class DdsKeysTest {

    /** One key field of a file whose {@code QADBXREF.DBXUNQ} is {@code unq}, as the catalog returns it (blank padded). */
    private static ResultSet keyedFile(String unq, String... columns) throws SQLException {
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
        when(rs.getString(2)).thenReturn(unq);
        return rs;
    }

    @Test
    void theQueryFiltersNothingButCarriesTheUniquenessAttribute() {
        // The filtering is done in Java so the rejected key can be named in the log; the attribute must
        // therefore travel with the key columns rather than being read in a second round trip.
        assertTrue(DdsKeys.KEY_COLUMNS.contains("qsys.QADBXREF"));
        assertTrue(DdsKeys.KEY_COLUMNS.contains("x.DBXUNQ"));
        assertTrue(DdsKeys.KEY_COLUMNS.contains("x.DBXLIB=k.DBKLIB"));
        assertTrue(DdsKeys.KEY_COLUMNS.contains("x.DBXFIL=k.DBKFIL"));
        assertTrue(DdsKeys.KEY_COLUMNS.contains("ORDER BY k.DBKPOS ASC"));
    }

    @Test
    void readsTheKeyColumnsInKeyOrderTrimmed() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile("U", "COMPANY", "ORDER_NO", "LINE_NO"));

        assertEquals(List.of("COMPANY", "ORDER_NO", "LINE_NO"), key.columns());
        assertTrue(key.unique());
    }

    @Test
    void aFileWithNoKeyedAccessPathReadsEmpty() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile("U"));

        assertEquals(List.of(), key.columns());
        assertFalse(key.unique());
        assertEquals(List.of(), key.asRecordKey(true, "LIB", "FILE"));
        assertEquals(List.of(), key.asRecordKey(false, "LIB", "FILE"));
    }

    /** {@code DBXUNQ='U'}: the key is an identity, so it keys the records whatever the setting says. */
    @Test
    void aUniqueKeyIsAlwaysUsed() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile("U", "ORDER_NO"));

        assertTrue(key.unique());
        assertEquals(List.of("ORDER_NO"), key.asRecordKey(true, "LIB", "ORDERS"));
        assertEquals(List.of("ORDER_NO"), key.asRecordKey(false, "LIB", "ORDERS"));
    }

    /**
     * {@code DBXUNQ='D'}: the regression this guards. Keying on it told every destination that records
     * sharing the key value were versions of one row, so a merged table kept one of them - on the worst
     * file measured, 1.5 M rows out of 716.9 M records.
     */
    @Test
    void aDuplicateAllowingKeyIsDroppedByDefault() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile("D", "ACCOUNT", "POSTED_ON"));

        assertFalse(key.unique());
        assertEquals(List.of("ACCOUNT", "POSTED_ON"), key.columns());
        assertEquals(List.of(), key.asRecordKey(true, "LIB", "GLDETAIL"));
    }

    /** The escape hatch, for an operator who knows the duplicate-allowing key is unique in practice. */
    @Test
    void aDuplicateAllowingKeyIsKeptWhenUniquenessIsNotRequired() throws Exception {
        final DdsKey key = DdsKeys.read(keyedFile("D", "ACCOUNT", "POSTED_ON"));

        assertEquals(List.of("ACCOUNT", "POSTED_ON"), key.asRecordKey(false, "LIB", "GLDETAIL"));
    }

    /** The attribute comes back CHAR(1) blank padded, and a file with no uniqueness attribute at all is not unique. */
    @Test
    void theUniquenessAttributeIsTrimmedAndAnythingButUIsDuplicateAllowing() throws Exception {
        assertTrue(DdsKeys.read(keyedFile("U ", "K")).unique());
        assertFalse(DdsKeys.read(keyedFile(" ", "K")).unique());
        assertFalse(DdsKeys.read(keyedFile(null, "K")).unique());
    }
}
