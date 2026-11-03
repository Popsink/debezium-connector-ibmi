/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.sql.SQLTimeoutException;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400Text;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;

/** Given a table's untagged columns and a sample of their bytes, when decided, then text or binary. */
class UntaggedColumnsTest {

    private static final byte[] HASH = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF };
    private static final byte[] BLANKS = { 0x40, 0x40, 0x40, 0x40 };

    private final Connection connection = mock(Connection.class);
    private final UntaggedDecisions decisions = new UntaggedDecisions();

    private static byte[] ebcdic(String text) {
        return new AS400Text(text.length(), 37).toBytes(text);
    }

    private UntaggedColumns columns(int systemCcsid) {
        return new UntaggedColumns(() -> connection, decisions, As400TextFactory.forCcsid(systemCcsid));
    }

    /** The catalogue lists {@code untagged}; the sample returns {@code rows}, one byte[] per column. */
    private PreparedStatement table(List<String> untagged, List<byte[][]> rows) throws SQLException {
        final PreparedStatement catalogue = mock(PreparedStatement.class);
        final ResultSet catalogueRows = mock(ResultSet.class);
        final Iterator<String> names = untagged.iterator();
        when(catalogueRows.next()).thenAnswer(i -> names.hasNext());
        when(catalogueRows.getString(1)).thenAnswer(i -> names.next());
        when(catalogue.executeQuery()).thenReturn(catalogueRows);
        when(connection.prepareStatement(UntaggedColumns.UNTAGGED_COLUMNS)).thenReturn(catalogue);

        final PreparedStatement sample = mock(PreparedStatement.class);
        final ResultSet sampleRows = mock(ResultSet.class);
        final Iterator<byte[][]> it = rows.iterator();
        final byte[][][] current = new byte[1][][];
        when(sampleRows.next()).thenAnswer(i -> {
            if (!it.hasNext()) {
                return false;
            }
            current[0] = it.next();
            return true;
        });
        when(sampleRows.getBytes(anyInt())).thenAnswer(i -> current[0][(Integer) i.getArgument(0) - 1]);
        when(sample.executeQuery()).thenReturn(sampleRows);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.startsWith("SELECT "))).thenReturn(sample);
        return sample;
    }

    @Test
    void textReadsInTheSystemCcsidsSupersetAndBinaryStaysBinary() throws SQLException {
        final PreparedStatement sample = table(List.of("NAME", "PWD"), List.of(
                new byte[][]{ BLANKS, HASH },
                new byte[][]{ ebcdic("D2000106"), HASH }));
        final UntaggedColumns columns = columns(935);

        assertEquals(OptionalInt.of(1388), columns.textCcsid("LIB", "T", "NAME"));
        assertTrue(columns.textCcsid("LIB", "T", "PWD").isEmpty());
        assertEquals("LIB.T.NAME=1388;LIB.T.PWD=hex", decisions.serialized());
        // one capped query for both columns
        verify(connection, times(1)).prepareStatement(
                "SELECT \"NAME\", \"PWD\" FROM \"LIB\".\"T\" FETCH FIRST 1000 ROWS ONLY OPTIMIZE FOR 1000 ROWS");
        verify(sample).setQueryTimeout(UntaggedColumns.SAMPLE_TIMEOUT_SECONDS);
    }

    /** A single unreadable value is enough: a column of mostly text with binary in it is not text. */
    @Test
    void oneUnreadableValueKeepsTheColumnBinary() throws SQLException {
        table(List.of("C"), List.of(new byte[][]{ ebcdic("ABC") }, new byte[][]{ HASH }, new byte[][]{ ebcdic("DEF") }));

        assertTrue(columns(37).textCcsid("LIB", "T", "C").isEmpty());
    }

    @Test
    void aColumnWithNothingButBlanksIsText() throws SQLException {
        table(List.of("C"), List.of(new byte[][]{ BLANKS }, new byte[][]{ null }, new byte[][]{ new byte[0] }));

        assertEquals(OptionalInt.of(37), columns(37).textCcsid("LIB", "T", "C"));
    }

    @Test
    void aFailedSampleKeepsEveryColumnBinary() throws SQLException {
        final PreparedStatement sample = table(List.of("A", "B"), List.of());
        when(sample.executeQuery()).thenThrow(new SQLTimeoutException("query timed out"));
        final UntaggedColumns columns = columns(37);

        assertTrue(columns.textCcsid("LIB", "T", "A").isEmpty());
        assertTrue(columns.textCcsid("LIB", "T", "B").isEmpty());
        assertEquals("LIB.T.A=hex;LIB.T.B=hex", decisions.serialized());
    }

    /** What the offset carries is not re-sampled, so a restart never flips a column's type. */
    @Test
    void aPersistedDecisionIsNotResampled() throws SQLException {
        decisions.load("LIB.T.C=hex;LIB.T.D=1388");
        final UntaggedColumns columns = columns(935);

        assertTrue(columns.textCcsid("LIB", "T", "C").isEmpty());
        assertEquals(OptionalInt.of(1388), columns.textCcsid("LIB", "T", "D"));
        verify(connection, never()).prepareStatement(anyString());
    }
}
