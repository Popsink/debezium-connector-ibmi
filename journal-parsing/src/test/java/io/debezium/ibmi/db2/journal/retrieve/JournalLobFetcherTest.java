/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.JournalLobFetcher.LobSegment;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;

public class JournalLobFetcherTest {

    // ---------------------------------------------------------------- splitting the appended segments

    @Test
    public void splitsASingleSegment() {
        final byte[] lob = bytes("some clob text");
        final byte[] data = entryData(EBCDIC_Q, lob);

        final List<LobSegment> segments = JournalLobFetcher.splitLobs(data, RECORD_LENGTH, new int[]{ lob.length });

        assertEquals(1, segments.size());
        assertArrayEquals(lob, bytesOf(data, segments.get(0)));
    }

    @Test
    public void splitsOneSegmentPerColumnInOrder() {
        final byte[] first = bytes("first column");
        final byte[] second = bytes("the second, longer, column");
        final byte[] data = entryData(EBCDIC_Q, first, second);

        final List<LobSegment> segments = JournalLobFetcher.splitLobs(data, RECORD_LENGTH,
                new int[]{ first.length, second.length });

        assertEquals(2, segments.size());
        assertArrayEquals(first, bytesOf(data, segments.get(0)));
        assertArrayEquals(second, bytesOf(data, segments.get(1)));
    }

    /**
     * The entry data is a BLOB of untranslated bytes, so the 'Q' separator is matched in EBCDIC as well
     * as ASCII rather than assuming which side of the connection built the buffer.
     */
    @Test
    public void splitsSegmentsSeparatedByAsciiQ() {
        final byte[] lob = bytes("some clob text");
        final byte[] data = entryData((byte) 'Q', lob);

        final List<LobSegment> segments = JournalLobFetcher.splitLobs(data, RECORD_LENGTH, new int[]{ lob.length });

        assertArrayEquals(lob, bytesOf(data, segments.get(0)));
    }

    /** A column with nothing in it still gets its place, so the columns after it stay aligned. */
    @Test
    public void keepsEmptyColumnsInPlace() {
        final byte[] third = bytes("third");
        final byte[] data = entryData(EBCDIC_Q, new byte[0], new byte[0], third);

        final List<LobSegment> segments = JournalLobFetcher.splitLobs(data, RECORD_LENGTH,
                new int[]{ 0, 0, third.length });

        assertEquals(3, segments.size());
        assertEquals(0, segments.get(0).length());
        assertArrayEquals(third, bytesOf(data, segments.get(2)));
    }

    /** A null column in the middle contributes no bytes, and the ones after it stay in step. */
    @Test
    public void keepsColumnsAfterANullOneInStep() {
        final byte[] first = bytes("first");
        final byte[] third = bytes("third");
        final byte[] data = entryData(EBCDIC_Q, first, new byte[0], third);

        final List<LobSegment> segments = JournalLobFetcher.splitLobs(data, RECORD_LENGTH,
                new int[]{ first.length, 0, third.length });

        assertArrayEquals(first, bytesOf(data, segments.get(0)));
        assertEquals(0, segments.get(1).length());
        assertArrayEquals(third, bytesOf(data, segments.get(2)));
    }

    /** Data of its own that happens to start with 'Q's must not be eaten as separator. */
    @Test
    public void doesNotTakeLeadingQsOfTheDataForSeparator() {
        final byte[] lob = bytes("QQQQ a clob that starts with Qs");
        final byte[] data = entryData((byte) 'Q', lob);

        final List<LobSegment> segments = JournalLobFetcher.splitLobs(data, RECORD_LENGTH, new int[]{ lob.length });

        assertArrayEquals(lob, bytesOf(data, segments.get(0)));
    }

    /** Data appended with no separator in front of it is still read at the right place. */
    @Test
    public void readsASegmentThatHasNoSeparator() {
        final byte[] lob = bytes("no separator");
        final byte[] data = new byte[RECORD_LENGTH + lob.length];
        System.arraycopy(lob, 0, data, RECORD_LENGTH, lob.length);

        final List<LobSegment> segments = JournalLobFetcher.splitLobs(data, RECORD_LENGTH, new int[]{ lob.length });

        assertArrayEquals(lob, bytesOf(data, segments.get(0)));
    }

    /**
     * A short buffer means the lengths, the record length, or the separators were misread. Splitting
     * anyway would attribute one column's data to another, so nothing is returned.
     */
    @Test
    public void returnsNothingWhenTheDataIsShorterThanTheLengthsAskFor() {
        final byte[] data = entryData(EBCDIC_Q, bytes("short"));

        assertTrue(JournalLobFetcher.splitLobs(data, RECORD_LENGTH, new int[]{ 500 }).isEmpty());
    }

    /** Entry data that stops before the record image even ends says nothing about the lobs. */
    @Test
    public void returnsNothingWhenTheDataStopsInsideTheRecordImage() {
        assertTrue(JournalLobFetcher.splitLobs(new byte[RECORD_LENGTH - 10], RECORD_LENGTH, new int[]{ 4 }).isEmpty());
        assertTrue(JournalLobFetcher.splitLobs(new byte[0], RECORD_LENGTH, new int[]{ 0 }).isEmpty());
    }

    @Test
    public void returnsNothingForANegativeLength() {
        final byte[] data = entryData(EBCDIC_Q, bytes("something"));

        assertTrue(JournalLobFetcher.splitLobs(data, RECORD_LENGTH, new int[]{ -1 }).isEmpty());
    }

    /**
     * A misread descriptor can hand over any length at all. Adding it to the offset must not be allowed
     * to wrap round into a negative, which would slip past the bounds check.
     */
    @Test
    public void returnsNothingForALengthThatOverflowsTheOffset() {
        final byte[] data = entryData(EBCDIC_Q, bytes("something"));

        assertTrue(JournalLobFetcher.splitLobs(data, RECORD_LENGTH, new int[]{ Integer.MAX_VALUE - 8 }).isEmpty());
        assertTrue(JournalLobFetcher.splitLobs(data, RECORD_LENGTH, new int[]{ Integer.MAX_VALUE }).isEmpty());
    }

    // ------------------------------------------------------------------------------------ the window

    /**
     * The point of the window: one query covers a run of entries, so the entries after the first are
     * served without going back to the system. A second query would mean the batching is not working.
     */
    @Test
    public void oneQueryServesEveryEntryInTheWindow() throws Exception {
        final PreparedStatement ps = returning(window(row(SEQUENCE, bytes("first entry")),
                row(SEQUENCE + 1, bytes("second entry"))));
        final JournalLobFetcher fetcher = fetcherOn(ps);

        assertArrayEquals(bytes("first entry"), fetcher.entryData(header(SEQUENCE)).orElseThrow());
        assertArrayEquals(bytes("second entry"), fetcher.entryData(header(SEQUENCE + 1)).orElseThrow());

        verify(ps, times(1)).executeQuery();
    }

    /**
     * A window holds hundreds of entries of lob data. Once an entry has been decoded the journal is
     * never asked for it again, so holding on to it just keeps megabytes reachable for the rest of the
     * run - a second read simply refills.
     */
    @Test
    public void releasesAnEntryOnceItHasBeenRead() throws Exception {
        final byte[] data = bytes("read once");
        final PreparedStatement ps = returning(window(row(SEQUENCE, data)), window(row(SEQUENCE, data)));
        final JournalLobFetcher fetcher = fetcherOn(ps);

        assertArrayEquals(data, fetcher.entryData(header(SEQUENCE)).orElseThrow());
        fetcher.entryData(header(SEQUENCE));

        verify(ps, times(2)).executeQuery();
    }

    /**
     * Two lob tables changing in the same buffer arrive interleaved. Each switch must not throw away
     * the other table's window: refilling hundreds of entries to serve one, over and over, would make
     * the window far more expensive than the per-entry query it replaced.
     */
    @Test
    public void interleavedTablesDoNotThrashTheWindow() throws Exception {
        final PreparedStatement ps = returning(
                window(row(10, bytes("A")), row(30, bytes("A"))),
                window(row(20, bytes("B")), row(40, bytes("B"))));
        final JournalLobFetcher fetcher = fetcherOn(ps);

        // A, B, A, B - two tables, four entries, and only two windows are needed to serve them
        assertArrayEquals(bytes("A"), fetcher.entryData(headerOf("CLOBTST", 10)).orElseThrow());
        assertArrayEquals(bytes("B"), fetcher.entryData(headerOf("CLOBFST", 20)).orElseThrow());
        assertArrayEquals(bytes("A"), fetcher.entryData(headerOf("CLOBTST", 30)).orElseThrow());
        assertArrayEquals(bytes("B"), fetcher.entryData(headerOf("CLOBFST", 40)).orElseThrow());

        verify(ps, times(2)).executeQuery();
    }

    /**
     * The window is capped on rows, but an entry can carry up to the cast limit of lob data, so a row
     * cap alone lets one fill hold tens of megabytes - and several tables' worth can be live at once.
     * The fill has to stop on bytes as well as rows.
     */
    @Test
    public void stopsFillingTheWindowOnceItHasEnoughBytes() throws Exception {
        // just under the cast limit, so each row is complete and only the byte budget can stop the fill
        final int rows = 400;
        final Row[] wide = IntStream.rangeClosed(1, rows)
                .mapToObj(i -> row(i, new byte[31_999]))
                .toArray(Row[]::new);
        final PreparedStatement ps = returning(window(wide), window(row(rows, bytes("late"))));
        final JournalLobFetcher fetcher = fetcherOn(ps);

        fetcher.entryData(header(1));
        // 400 rows of 32k is far past the budget, so the last one was never taken into the window
        assertArrayEquals(bytes("late"), fetcher.entryData(header(rows)).orElseThrow());

        verify(ps, times(2)).executeQuery();
    }

    /**
     * An entry whose data the cast could not carry whole is re-read on its own. A value that fills the
     * limit exactly may have been cut off, and handing on the truncated bytes would corrupt the column.
     */
    @Test
    public void anEntryTooLargeForTheCastIsReadWhole() throws Exception {
        final byte[] whole = bytes("the entire entry, read back as a blob");
        final PreparedStatement windowPs = returning(window(row(SEQUENCE, new byte[32000])));
        final PreparedStatement wholePs = returning(wholeEntry("PT", 1, whole));
        final Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString()))
                .thenAnswer(i -> ((String) i.getArgument(0)).contains("VARBINARY") ? windowPs : wholePs);

        assertArrayEquals(whole, fetcherOn(() -> con).entryData(header(SEQUENCE)).orElseThrow(),
                "a truncated window value must not be handed on as if it were complete");
    }

    /**
     * Sequence numbers are only unique within a receiver chain, and a chain whose numbers were reset
     * holds them again from the start. The window outlives a single retrieve, so an entry cached while
     * reading the old chain must not be handed to an entry of the new one that happens to sit at the
     * same sequence - the type and record number can legitimately match, which is exactly when the
     * identity guard cannot tell them apart.
     */
    @Test
    public void doesNotServeAnEntryCachedFromADifferentReceiver() throws Exception {
        final byte[] oldChain = bytes("data belonging to the old receiver");
        final byte[] newChain = bytes("data belonging to the new receiver");
        // two entries, so that reading the first leaves the second sitting in the window. Reading the
        // only entry of a window empties it, and then the receiver change has nothing left to serve
        // wrongly - the collision this guards against needs a survivor
        final PreparedStatement ps = returning(
                window(row(SEQUENCE, oldChain), row(SEQUENCE + 1, oldChain)),
                window(row(SEQUENCE + 1, newChain)));
        final JournalLobFetcher fetcher = fetcherOn(ps);

        assertArrayEquals(oldChain, fetcher.entryData(headerOn("JRNRCV01", SEQUENCE)).orElseThrow());

        // same table, same sequence, same type, same record number - only the receiver differs, and the
        // old chain's entry is still cached under exactly that key
        assertArrayEquals(newChain, fetcher.entryData(headerOn("JRNRCV02", SEQUENCE + 1)).orElseThrow(),
                "the new chain's entry was served the old chain's lob data");
        verify(ps, times(2)).executeQuery();
    }

    /**
     * A failed window fill must not take the record down with it. The fetcher gives up the statements
     * it was holding, because whatever went wrong may have left them unusable, and the entry decodes
     * with its lob columns null rather than throwing.
     */
    @Test
    public void aFailedWindowFillLeavesTheEntryWithoutLobDataRatherThanThrowing() throws Exception {
        final PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenThrow(new SQLException("connection reset"));

        assertTrue(fetcherOn(ps).entryData(header(SEQUENCE)).isEmpty());

        // the statements it was holding are given up, so the next attempt prepares fresh ones
        verify(ps, atLeastOnce()).close();
        // and it does not immediately ask the same broken connection for the entry on its own
        verify(ps, times(1)).executeQuery();
    }

    /** Having failed once, the fetcher has to work again rather than stay poisoned. */
    @Test
    public void recoversOnTheNextEntryAfterAFailure() throws Exception {
        final byte[] data = bytes("readable again");
        final PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenThrow(new SQLException("connection reset"))
                .thenReturn(window(row(SEQUENCE, data)));
        final JournalLobFetcher fetcher = fetcherOn(ps);

        assertTrue(fetcher.entryData(header(SEQUENCE)).isEmpty());
        assertArrayEquals(data, fetcher.entryData(header(SEQUENCE)).orElseThrow());
    }

    /**
     * A reconnect hands the fetcher a different {@link Connection}. Statements belong to the connection
     * they were prepared on, so reusing them would fail against the new one for the rest of the run.
     */
    @Test
    public void preparesAgainstTheNewConnectionAfterAReconnect() throws Exception {
        final byte[] data = bytes("after the reconnect");
        final PreparedStatement first = returning(window(row(SEQUENCE, data)));
        final PreparedStatement second = returning(window(row(SEQUENCE, data)));
        final Connection con1 = mock(Connection.class);
        when(con1.prepareStatement(anyString())).thenReturn(first);
        final Connection con2 = mock(Connection.class);
        when(con2.prepareStatement(anyString())).thenReturn(second);
        final Connection[] current = { con1 };
        final JournalLobFetcher fetcher = fetcherOn(() -> current[0]);

        assertArrayEquals(data, fetcher.entryData(header(SEQUENCE)).orElseThrow());
        current[0] = con2;
        assertArrayEquals(data, fetcher.entryData(header(SEQUENCE)).orElseThrow());

        // the old connection's statement is closed and the new one is asked to prepare its own
        verify(first, atLeastOnce()).close();
        verify(con2, atLeastOnce()).prepareStatement(anyString());
        verify(second, atLeastOnce()).executeQuery();
    }

    // ------------------------------------------------------- is this really the entry being decoded?

    /**
     * A sequence number is only unique within a receiver chain, and repeats in one whose numbers were
     * reset. Using another row's lob data would be silent corruption, so the entry that comes back is
     * checked against the one being decoded.
     */
    @Test
    public void refusesLobDataFromADifferentRow() throws Exception {
        final JournalLobFetcher fetcher = fetcherOn(returning(window(new Row(SEQUENCE, "PT", 99, bytes("wrong row")))));

        assertTrue(fetcher.entryData(header(SEQUENCE)).isEmpty());
    }

    @Test
    public void refusesLobDataFromADifferentKindOfEntry() throws Exception {
        final JournalLobFetcher fetcher = fetcherOn(
                returning(window(new Row(SEQUENCE, "UB", 1, bytes("before image")))));

        assertTrue(fetcher.entryData(header(SEQUENCE)).isEmpty());
    }

    @Test
    public void acceptsLobDataForTheEntryBeingDecoded() throws Exception {
        final byte[] data = bytes("the entry");
        final JournalLobFetcher fetcher = fetcherOn(returning(window(row(SEQUENCE, data))));

        assertArrayEquals(data, fetcher.entryData(header(SEQUENCE)).orElseThrow());
    }

    // ---------------------------------------------------------------------------------------- set-up

    private static final int RECORD_LENGTH = 49;
    private static final byte EBCDIC_Q = (byte) 0xD8;
    private static final long SEQUENCE = 42;
    private static final String TABLE = "CLOBTST";
    private static final String LIBRARY = "PYP31";
    private static final JournalInfo JOURNAL = new JournalInfo("JRNTEST", LIBRARY, false);

    /** The 16 byte run of 'Q' DISPLAY_JOURNAL writes in front of each appended segment. */
    private static byte[] separator(byte q) {
        final byte[] separator = new byte[16];
        Arrays.fill(separator, q);
        return separator;
    }

    private static byte[] entryData(byte q, byte[]... segments) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[RECORD_LENGTH]);
        for (final byte[] segment : segments) {
            out.writeBytes(separator(q));
            out.writeBytes(segment);
        }
        return out.toByteArray();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A segment points into the entry data rather than copying out of it, so read it back to compare. */
    private static byte[] bytesOf(byte[] entryData, LobSegment segment) {
        return Arrays.copyOfRange(entryData, segment.offset(), segment.offset() + segment.length());
    }

    /** An entry as the RPC hands it over. */
    private static EntryHeader header(String table, String receiver, long sequence, String entryType, long rrn) {
        final String object = String.format("%-10s%-10s%-10s", table, LIBRARY, table);
        return new EntryHeader(0, 0, 0, BigInteger.valueOf(sequence), BigInteger.ZERO, Instant.EPOCH, 'R', entryType,
                object, BigInteger.ZERO, 0, 13, receiver, receiver.isEmpty() ? "" : LIBRARY,
                BigInteger.valueOf(rrn));
    }

    /** An ordinary record entry of the one table, which is what all but a few of these tests need. */
    private static EntryHeader header(long sequence) {
        return header(TABLE, "", sequence, "PT", 1);
    }

    private static EntryHeader headerOf(String table, long sequence) {
        return header(table, "", sequence, "PT", 1);
    }

    private static EntryHeader headerOn(String receiver, long sequence) {
        return header(TABLE, receiver, sequence, "PT", 1);
    }

    /** One row of a window query result: the four columns the fill reads, in order. */
    private record Row(long sequence, String entryType, long rrn, byte[] data) {
    }

    private static Row row(long sequence, byte[] data) {
        return new Row(sequence, "PT", 1, data);
    }

    /**
     * A window query result. Driven by a cursor rather than by a sequence of canned return values, so
     * that a test which reads the columns in a different order than expected still sees the right row.
     */
    private static ResultSet window(Row... rows) throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        final int[] at = { -1 };
        when(rs.next()).thenAnswer(i -> ++at[0] < rows.length);
        when(rs.getBigDecimal(1)).thenAnswer(i -> BigDecimal.valueOf(rows[at[0]].sequence()));
        when(rs.getString(2)).thenAnswer(i -> rows[at[0]].entryType());
        when(rs.getBigDecimal(3)).thenAnswer(i -> BigDecimal.valueOf(rows[at[0]].rrn()));
        when(rs.getBytes(4)).thenAnswer(i -> rows[at[0]].data());
        return rs;
    }

    /** A single entry read whole, which puts the data in column 1 rather than the cast-down column 4. */
    private static ResultSet wholeEntry(String entryType, long rrn, byte[] data) throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getBytes(1)).thenReturn(data);
        when(rs.getString(2)).thenReturn(entryType);
        when(rs.getBigDecimal(3)).thenReturn(BigDecimal.valueOf(rrn));
        return rs;
    }

    /** A statement that answers each execution with the next result in turn. */
    private static PreparedStatement returning(ResultSet first, ResultSet... rest) throws SQLException {
        final PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(first, rest);
        return ps;
    }

    /** A fetcher on a connection that answers every prepare with the one statement. */
    private static JournalLobFetcher fetcherOn(PreparedStatement ps) throws SQLException {
        final Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        return fetcherOn(() -> con);
    }

    private static JournalLobFetcher fetcherOn(Connect<Connection, SQLException> jdbc) {
        return new JournalLobFetcher(jdbc, JOURNAL);
    }
}
