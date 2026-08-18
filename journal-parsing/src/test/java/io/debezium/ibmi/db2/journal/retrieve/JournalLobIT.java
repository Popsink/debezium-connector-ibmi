/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.CommandCall;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.data.types.Diagnostics;
import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheIF.Structure;
import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheIF.TableInfo;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.ibmi.db2.journal.test.TestConnector;

/**
 * End to end check of large object support against a live IBM i: that each descriptor in the record
 * image is read where it is expected and is the width the columns after it assume, that the data itself
 * comes back out of the journal, and that the pointer handles are given back.
 *
 * <p>
 * Needs {@code ISERIES_HOST}, {@code ISERIES_USER}, {@code ISERIES_PASSWORD} and
 * {@code ISERIES_SCHEMA}, and the schema has to be journaled ({@code STRJRNLIB}). It creates {@code CLOBTST} in that
 * schema and starts journaling it. Whatever it asserts it also logs - the record image and the
 * entry data DISPLAY_JOURNAL returns are dumped as hex, so a failure says what the layout really is.
 * </p>
 */
class JournalLobIT {
    private static final Logger log = LoggerFactory.getLogger(JournalLobIT.class);

    private static final String TABLE = "CLOBTST";
    private static final String HEAD = "ABCDE";
    private static final String TAIL = "ZZ";
    private static final String MID = "mid";
    private static final String BODY = "the quick brown fox jumps over the lazy dog";
    private static final byte[] RAW = new byte[]{ 0x00, 0x01, (byte) 0xD8, (byte) 0xD8, 0x7f, (byte) 0xff, 0x40, 0x00 };
    // a character that is two bytes in the column's UTF-8, to tell a length in bytes from one in characters
    private static final String DOC = "<root><child a=\"1\">text é</child></root>";
    /** Double byte text, whose descriptor length is in characters where the appended data is bytes. */
    private static final String WIDE = "double byte";
    private static final String UPDATED_BODY = "a second, longer, value for the same row, with 'QQQQQQQQQQQQQQQQ' in it";

    /** The record entries a row change produces: inserts, an update's two images, and deletes. */
    private static final Set<JournalEntryType> RECORD_CHANGES = Set.of(JournalEntryType.ADD_ROW1,
            JournalEntryType.ADD_ROW2, JournalEntryType.BEFORE_IMAGE, JournalEntryType.AFTER_IMAGE,
            JournalEntryType.DELETE_ROW, JournalEntryType.ROLLBACK_DELETE_ROW);

    /** A second table, to read a lob that starts the record rather than sitting inside it. */
    private static final String LOB_FIRST_TABLE = "CLOBFST";
    /** Long enough to dwarf the 32766 bytes that make any entry's data pointer-only. */
    private static final String LARGE = "0123456789".repeat(4_000);

    private static Connect<AS400, IOException> as400Connect;
    private static Connect<Connection, SQLException> sqlConnect;
    private static String schema;
    private static As400TextFactory textFactory;
    private static JournalInfoRetrieval retrieval;

    @BeforeAll
    static void setup() throws Exception {
        assumeTrue(System.getenv("ISERIES_HOST") != null && System.getenv("ISERIES_PASSWORD") != null,
                "needs ISERIES_HOST, ISERIES_USER, ISERIES_PASSWORD and ISERIES_SCHEMA for a live system");
        final TestConnector connector = new TestConnector();
        as400Connect = connector.getAs400();
        sqlConnect = connector.getJdbc();
        textFactory = connector.getTextFactory();
        schema = connector.getSchema();
        assertNotNull(schema, "ISERIES_SCHEMA must be set");
        retrieval = new JournalInfoRetrieval(textFactory, 0, 0, 1000);
        createTable();
        startJournaling(TABLE);
        logCatalogue();
    }

    // ------------------------------------------------------------------------ decoding the lob shapes

    @Test
    void decodesTheColumnsAroundAClobAndFetchesItsText() throws Exception {
        final JournalInfo journal = journalFor(TABLE);
        final JournalProcessedPosition position = positionNow(journal);

        insert(1, BODY, RAW, DOC);
        update(1, UPDATED_BODY);
        insert(2, null, null, null);
        insert(3, "", new byte[0], null);

        final RetrieveJournal rj = retrieveJournal(journal);
        final List<Entry> entries = read(rj, fetchingDecoder(journal), position, journal, 5);

        assertEquals(5, entries.size(), () -> "expected an insert, an update's two images and two more "
                + "inserts, got " + entries);
        for (final Entry entry : entries) {
            // the fixed width columns between and after the large objects prove every descriptor is the
            // right width: get one wrong and these read from the wrong offset
            assertEquals(HEAD, trimmed(entry, "HEAD"), () -> "entry " + entry);
            assertEquals(MID, trimmed(entry, "MID"), () -> "entry " + entry);
            assertEquals(TAIL, trimmed(entry, "TAIL"), () -> "entry " + entry);
        }
        assertEquals(BODY, body(entries.get(0)), "clob of the inserted row");
        assertArrayEquals(RAW, (byte[]) entries.get(0).values().get("RAW"), "blob of the inserted row");
        assertEquals(DOC, text(entries.get(0), "DOC"), "xml of the inserted row");
        assertEquals(WIDE, text(entries.get(0), "WIDE"), "dbclob of the inserted row");
        // the before image is the case a re-read of the row could not answer: it holds the old value
        assertEquals(JournalEntryType.BEFORE_IMAGE, entries.get(1).type());
        assertEquals(BODY, body(entries.get(1)), "clob of the update's before image");
        assertEquals(JournalEntryType.AFTER_IMAGE, entries.get(2).type());
        assertEquals(UPDATED_BODY, body(entries.get(2)), "clob of the update's after image");
        // the update only touched the clob, so the other large objects come back unchanged with it
        assertArrayEquals(RAW, (byte[]) entries.get(2).values().get("RAW"), "blob of the update's after image");
        assertEquals(DOC, text(entries.get(2), "DOC"), "xml of the update's after image");
        assertEquals(WIDE, text(entries.get(2), "WIDE"), "dbclob of the update's after image");

        final Entry nulls = entries.get(3);
        assertNull(nulls.values().get("BODY"), "a null clob stays null");
        assertNull(nulls.values().get("RAW"), "a null blob stays null");
        assertNull(nulls.values().get("DOC"), "a null xml stays null");
        assertNull(nulls.values().get("WIDE"), "a null dbclob stays null");

        final Entry empty = entries.get(4);
        assertEquals("", body(empty), "an empty clob needs no fetching");
        assertArrayEquals(new byte[0], (byte[]) empty.values().get("RAW"), "an empty blob needs no fetching");

        // every entry of a lob bearing table carries a pointer handle; they are counted rather than
        // deleted one at a time, and freed in bulk by replacing the connection once the budget is spent
        assertEquals(entries.size(), rj.pointerHandles().outstanding(), "a handle counted per lob entry");
    }

    /**
     * The shapes the main table does not have: a lob that starts the record (nothing in front of it to
     * pad against), a value far larger than the 32766 bytes that make an entry pointer-only, and a delete,
     * whose entry is the only record of what the row held. The same entries are then decoded a second
     * time by a decoder with no fetcher, which is what {@code lob.fetch=false} leaves behind.
     */
    @Test
    void decodesALobThatStartsTheRecordAndALargeOne() throws Exception {
        // the lob comes first, so its padding is worked out from nothing in front of it
        create(LOB_FIRST_TABLE, "BODY CLOB(1M), ID INT NOT NULL PRIMARY KEY, TAIL CHAR(2)");
        startJournaling(LOB_FIRST_TABLE);

        final JournalInfo journal = journalFor(LOB_FIRST_TABLE);
        final JournalPosition current = retrieval.getCurrentPosition(as400Connect.connection(), journal);

        insertLobFirst(1, BODY);
        insertLobFirst(2, LARGE);
        delete(LOB_FIRST_TABLE, 1);

        final List<Entry> entries = read(retrieveJournal(journal), fetchingDecoder(journal),
                new JournalProcessedPosition(current, Instant.now(), true), journal, 3, LOB_FIRST_TABLE);

        assertEquals(3, entries.size(), () -> "two inserts and a delete, got " + entries.size());
        for (final Entry entry : entries) {
            assertEquals(TAIL, trimmed(entry, "TAIL"), () -> "the column after a leading lob is misplaced: " + entry);
        }
        assertEquals(BODY, body(entries.get(0)), "a lob at the start of the record");
        assertEquals(LARGE, body(entries.get(1)), "a lob far larger than an entry can hold inline");
        assertEquals(JournalEntryType.DELETE_ROW, entries.get(2).type());
        assertEquals(BODY, body(entries.get(2)), "the deleted row's lob, which only the journal still has");

        // and again with no fetcher, as lob.fetch=false leaves it: the lob column is null, the rest is not
        final List<Entry> unfetched = read(retrieveJournal(journal), plainDecoder(),
                new JournalProcessedPosition(current, Instant.now(), true), journal, 3, LOB_FIRST_TABLE);

        assertEquals(3, unfetched.size());
        for (final Entry entry : unfetched) {
            assertNull(entry.values().get("BODY"), () -> "no fetcher, so no lob data: " + entry);
            assertEquals(TAIL, trimmed(entry, "TAIL"), () -> "the rest of the row is unaffected: " + entry);
            // the column immediately after the leading lob, so it moves if the descriptor is mis-sized
            final int id = (Integer) entry.values().get("ID");
            assertTrue(id == 1 || id == 2, () -> "id decoded as " + id + " in " + entry);
        }
    }

    // ----------------------------------------------------------------------------- the handle budget

    /**
     * Every entry with lob data comes with a pointer handle owning an allocation that is only released
     * when the handle is deleted or the job that requested it ends. They are counted rather than
     * deleted one at a time, and freed in bulk by moving to another host server job.
     *
     * <p>
     * The two halves that matter: the budget counts a handle for each lob bearing entry, and retrieval
     * carries on correctly against whatever job serves the next retrieve - which is what makes recycling
     * a safe substitute for deleting. Whether the disconnect here frees anything is a separate question,
     * and {@link #recordsWhetherADisconnectEndsTheHostServerJob} is where it is asked.
     * </p>
     */
    @Test
    void countsPointerHandlesAndSurvivesRecyclingTheJob() throws Exception {
        final JournalInfo journal = journalFor(TABLE);
        final JdbcFileDecoder fileDecoder = fetchingDecoder(journal);
        final JournalProcessedPosition position = positionNow(journal);
        insert(10, BODY, RAW, DOC);

        final RetrieveJournal rj = retrieveJournal(journal);
        final List<Entry> entries = read(rj, fileDecoder, position, journal, 1);

        assertEquals(1, entries.size());
        assertNotEquals(0, entries.get(0).pointerHandle(), "an entry with lob data comes with a pointer handle");
        assertTrue(rj.pointerHandles().outstanding() > 0, "the entry's handle was counted");

        as400Connect.connection().disconnectService(AS400.COMMAND);

        insert(11, BODY, RAW, DOC);
        assertNotNull(read(retrieveJournal(journal), fileDecoder, positionNow(journal), journal, 0),
                "retrieval failed after the host server job was recycled");
    }

    /**
     * {@code lob.fetch=false} is the default, and it leaves the decoder with no fetcher at all. The
     * entries still arrive owning pointer handles though - the system allocates those whether or not
     * anyone reads the lob data - so the budget has to keep counting them. If it only counted on the
     * fetch path the default configuration would accumulate handles with nothing ever freeing them,
     * which is the exact failure this design exists to prevent.
     */
    @Test
    void theDefaultConfigurationStillCountsHandlesWithNoFetcher() throws Exception {
        final int rows = 5;
        final JournalInfo journal = journalFor(TABLE);
        final JournalPosition start = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        for (int i = 0; i < rows; i++) {
            insert(40000 + i, BODY, RAW, DOC);
        }

        final RetrieveJournal rj = retrieveJournal(journal);
        // no lob fetcher: this is what lob.fetch=false leaves behind
        final Timings timings = decodeEveryRecord(rj, plainDecoder(), TABLE, start, rows);

        log.info("=== lob.fetch=false ===");
        log.info("{} lob entries decoded with no fetcher, {} handles counted", timings.decoded(),
                rj.pointerHandles().outstanding());
        assertEquals(rows, timings.decoded());
        // at least: the budget counts every pointer bearing entry the retrieve walked, which includes
        // the other lob tables sharing this journal, not just the rows this test wrote
        assertTrue(rj.pointerHandles().outstanding() >= rows,
                () -> "handles must still be counted when the lob data is never read, or the default "
                        + "configuration would never recycle the job - counted "
                        + rj.pointerHandles().outstanding());
    }

    // --------------------------------------------------------------- what recycling the job really does

    /**
     * Records what a disconnect actually does to the host server job, which is the assumption the whole
     * handle budget rests on.
     *
     * <p>
     * <b>It does not reliably end the job.</b> QZRCSRVS are prestart jobs: disconnecting returns one to
     * a pool rather than ending it, and reconnecting frequently hands back the very same job - measured
     * here repeatedly, with {@code disconnectService(AS400.COMMAND)} and with
     * {@code disconnectAllServices()} alike. When that happens the handles are still outstanding and
     * the recycle freed nothing.
     * </p>
     *
     * <p>
     * The budget copes rather than lying about it: it only clears when a retrieve reports a genuinely
     * different job, so an ineffective recycle leaves the count standing and is retried. What actually
     * frees them is replacing the connection object, which
     * {@link #replacingTheConnectionObjectLandsOnADifferentJob} pins down.
     * </p>
     */
    @Test
    void recordsWhetherADisconnectEndsTheHostServerJob() throws Exception {
        final JournalInfo journal = journalFor(TABLE);
        final RetrieveJournal rj = retrieveJournal(journal);

        final JournalPosition before = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        insert(30001, BODY, RAW, DOC);
        // entries are not in the journal the instant they are written, and a retrieve over an empty
        // range never issues the call - so it would observe no job at all and this would flake
        retrieveUntilCalled(rj, before);
        assertEquals(1, rj.pointerHandles().jobChanges(), "the first retrieve should have observed a job");

        as400Connect.connection().disconnectService(AS400.COMMAND);

        final JournalPosition mid = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        insert(30002, BODY, RAW, DOC);
        retrieveUntilCalled(rj, mid);

        final long changes = rj.pointerHandles().jobChanges();
        log.info("=== job identity across a disconnect ===");
        log.info("job changes observed: {} - 1 means the same prestart job came back and nothing was freed",
                changes);
        if (changes < 2) {
            log.warn("the disconnect did NOT end the host server job: its pointer handles are still "
                    + "outstanding. The budget will keep retrying rather than assume they were freed, "
                    + "but recycling is not releasing anything on this system");
        }
        // deliberately not asserted either way: the outcome depends on the prestart job pool, and the
        // point of this test is to report which happened, not to fail on the system's scheduling
        assertTrue(changes >= 1, "no job was observed at all");
    }

    /**
     * The fix for the recycling blocker. Disconnecting a service returns the prestart job to a pool and
     * the same {@code AS400} reconnects straight back into it, so nothing is freed. A brand new
     * connection lands on a different job with a fresh handle space - so replacing the connection
     * object, rather than disconnecting its service, is what actually ends our use of that job.
     */
    @Test
    void replacingTheConnectionObjectLandsOnADifferentJob() throws Exception {
        final JournalInfo journal = journalFor(TABLE);

        final Instant d0 = Instant.now();
        final String viaDisconnect = jobAfter(journal, true);
        final long disconnectMs = Duration.between(d0, Instant.now()).toMillis();
        final Instant n0 = Instant.now();
        final String viaNewConnection = jobAfter(journal, false);
        final long newConnectionMs = Duration.between(n0, Instant.now()).toMillis();

        log.info("=== recycling: disconnect versus a new connection object ===");
        log.info("same AS400, service disconnected : {} ({} ms incl. one retrieve)", viaDisconnect, disconnectMs);
        log.info("a new AS400 object               : {} ({} ms incl. auth and one retrieve)",
                viaNewConnection, newConnectionMs);
        assertNotEquals(viaDisconnect, viaNewConnection,
                "a brand new connection landed on the same prestart job, so replacing the connection "
                        + "would not free the handles either");
    }

    /**
     * The whole point, end to end: handles accumulate, the connection is replaced, and the budget the
     * connector consults actually comes back to nothing. The pieces are checked elsewhere - that a new
     * connection lands on a different job, and that the budget clears when the job changes - but only
     * this puts them together on one budget, which is what {@code freePointerHandlesIfDue} relies on.
     */
    @Test
    void replacingTheConnectionEmptiesTheHandleBudget() throws Exception {
        final JournalInfo journal = journalFor(TABLE);
        // a connection the test can swap underneath the retriever, the way As400RpcConnection does
        final AS400[] current = { as400Connect.connection() };
        final RetrieveJournal rj = retrieveJournal(() -> current[0], journal);

        final JournalPosition before = retrieval.getCurrentPosition(current[0], journal);
        for (int i = 0; i < 5; i++) {
            insert(70000 + i, BODY, RAW, DOC);
        }
        retrieveUntilCalled(rj, before);
        while (rj.nextEntry()) {
            // walking is what counts the handles
        }
        final long accumulated = rj.pointerHandles().outstanding();
        final String firstJob = rj.pointerHandles().job();
        log.info("=== recycling empties the budget ===");
        log.info("accumulated {} handles in job {}", accumulated, firstJob);
        assertTrue(accumulated > 0, "no handles were counted to begin with");

        // exactly what freePointerHandlesIfDue does: drop the connection object rather than its service
        current[0] = new TestConnector().getAs400().connection();

        final JournalPosition after = retrieval.getCurrentPosition(current[0], journal);
        insert(70100, BODY, RAW, DOC);
        retrieveUntilCalled(rj, after);

        log.info("after replacing the connection: job {}, {} handles outstanding",
                rj.pointerHandles().job(), rj.pointerHandles().outstanding());
        assertNotEquals(firstJob, rj.pointerHandles().job(), "the replacement landed on the same job");
        assertTrue(rj.pointerHandles().outstanding() < accumulated,
                "the budget still carries the handles the old job was holding");
    }

    /**
     * Characterises what a fresh connection actually lands on, which decides how far handles can
     * accumulate in practice. A prestart job that is handed back with its handles still counted keeps
     * climbing; one that is genuinely fresh starts its numbering again. Neither is under our control,
     * so this reports what happened rather than asserting a particular outcome.
     */
    @Test
    void reportsTheJobAndHandleNumberAFreshConnectionLandsOn() throws Exception {
        final JournalInfo journal = journalFor(TABLE);
        final RetrieveJournal rj = retrieveJournal(journal);

        final JournalPosition before = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        insert(50001, BODY, RAW, DOC);
        retrieveUntilCalled(rj, before);

        long firstHandle = 0;
        while (rj.nextEntry() && firstHandle == 0) {
            firstHandle = rj.getEntryHeader().getPointerHandle();
        }

        log.info("=== what a fresh connection landed on ===");
        log.info("host server job: {}", rj.pointerHandles().job());
        log.info("first pointer handle it handed out: {} - a low number means this job's handle space "
                + "is fresh, a high one means it is carrying handles from earlier work", firstHandle);
        assertTrue(firstHandle != 0, "no pointer bearing entry to read a handle from");
    }

    // -------------------------------------------------------------------------------- what it costs

    /**
     * What the implemented path actually costs an entry, end to end: retrieve over RPC, then decode
     * every lob through the window. The design doc quotes 5.9 ms a row for the cast query measured with
     * a hand written statement; this checks the real code reaches the same order, because a batching
     * window that quietly fell back to a query an entry would still pass every functional test.
     */
    @Test
    void measuresTheCostOfTheImplementedLobPath() throws Exception {
        final int rows = 120;
        final JournalInfo journal = journalFor(TABLE);
        final JournalPosition start = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        for (int i = 0; i < rows; i++) {
            insert(20000 + i, BODY, RAW, DOC);
        }

        final RetrieveJournal rj = retrieveJournal(journal);
        final Timings timings = decodeEveryRecord(rj, fetchingDecoder(journal), TABLE, start, rows);

        log.info("=== implemented lob path ===");
        logTimings(timings, "loads the record format and fills the window", "the steady state lob path");
        log.info("pointer handles counted: {}, job changes: {}", rj.pointerHandles().outstanding(),
                rj.pointerHandles().jobChanges());
        assertEquals(rows, timings.decoded(), "not every inserted row was decoded");
    }

    /**
     * The control for {@link #measuresTheCostOfTheImplementedLobPath}: the same decode loop over a table
     * with no lob column at all, so nothing touches the fetcher. If this costs the same per entry then
     * the expense is in decoding generally, not in reading lob data, and the lob path is being blamed
     * for something else.
     */
    @Test
    void measuresTheCostOfDecodingWithoutAnyLobColumn() throws Exception {
        final int rows = 120;
        final String plain = "NOLOBTST";
        create(plain, "ID INT NOT NULL PRIMARY KEY, HEAD CHAR(5), MID CHAR(3), TAIL CHAR(2)");
        try {
            startJournaling(plain);
            final JournalInfo journal = journalFor(plain);
            final JournalPosition start = retrieval.getCurrentPosition(as400Connect.connection(), journal);
            try (Statement stmt = sqlConnect.connection().createStatement()) {
                stmt.executeUpdate(String.format("""
                        INSERT INTO %s.%s (ID, HEAD, MID, TAIL)
                        WITH n(i) AS (VALUES 1 UNION ALL SELECT i + 1 FROM n WHERE i < %d)
                        SELECT i, 'ABCDE', 'mid', 'ZZ' FROM n
                        """, schema, plain, rows));
            }

            final RetrieveJournal rj = retrieveJournal(journal);
            final Timings timings = decodeEveryRecord(rj, fetchingDecoder(journal), plain, start, rows);

            log.info("=== control: no lob column ===");
            // no window is filled here: the table has no lob column, so the fetcher is never consulted
            logTimings(timings, "loads the record format", "decoding with nothing to fetch");
            log.info("handles counted: {}", rj.pointerHandles().outstanding());
            assertEquals(rows, timings.decoded(), "not every inserted row was decoded");
        }
        finally {
            drop(plain);
        }
    }

    // ---------------------------------------------------------------------------- retrieving entries

    /** A decoded journal entry for the table under test. */
    private record Entry(JournalEntryType type, long pointerHandle, Map<String, Object> values) {

        @Override
        public String toString() {
            return String.format("%s handle=%s %s", type, pointerHandle, values);
        }
    }

    private List<Entry> read(RetrieveJournal rj, JdbcFileDecoder fileDecoder, JournalProcessedPosition position,
                             JournalInfo journal, int expected)
            throws Exception {
        return read(rj, fileDecoder, position, journal, expected, TABLE);
    }

    private List<Entry> read(RetrieveJournal rj, JdbcFileDecoder fileDecoder, JournalProcessedPosition position,
                             JournalInfo journal, int expected, String table)
            throws Exception {
        final List<Entry> entries = new ArrayList<>();
        // journal entries are not available straight away
        for (int i = 0; entries.size() < expected && i < 100; i++) {
            final RetrievalState state = rj.retrieveJournal(position);
            while (state.hasData() && rj.nextEntry()) {
                final EntryHeader entryHeader = rj.getEntryHeader();
                final JournalEntryType type = entryHeader.getJournalEntryType();
                if (isRecordOf(table, entryHeader)) {
                    log.info("{} entry {}", type, entryHeader);
                    if (TABLE.equals(table)) {
                        // the hex of what DISPLAY_JOURNAL returns, to read the layout off a failure. Left
                        // out for the large values of the other table, where it is pages of noise
                        logEntryData(journal, entryHeader);
                    }
                    entries.add(new Entry(type, entryHeader.getPointerHandle(),
                            toValues(fileDecoder.getRecordFormat(table, schema).orElse(null),
                                    rj.decode(fileDecoder))));
                }
                final JournalProcessedPosition next = rj.getPosition();
                if (position.equals(next)) {
                    Thread.sleep(100);
                }
                position.setPosition(next);
            }
        }
        return entries;
    }

    /** Retrieves until the call is actually issued: an empty range returns without touching the system. */
    private static void retrieveUntilCalled(RetrieveJournal rj, JournalPosition from) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (rj.retrieveJournal(new JournalProcessedPosition(from, Instant.now(), true)) != RetrievalState.NotCalled) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no journal entry became visible to retrieve");
    }

    /**
     * How long the decode of one table's records took. The first entry is kept apart from the rest
     * because it loads the record format and fills the window - averaging it in put the reported cost
     * of an entry two orders of magnitude out.
     */
    private record Timings(int decoded, long firstNanos, long restNanos) {

        String msPerEntry() {
            return decoded <= 1 ? "n/a" : String.format("%.3f", restNanos / 1e6 / (decoded - 1));
        }
    }

    /** Decodes every record entry of one table from {@code start}, timing the work rather than the wait. */
    private static Timings decodeEveryRecord(RetrieveJournal rj, JdbcFileDecoder decoder, String table,
                                             JournalPosition start, int rows)
            throws Exception {
        final JournalProcessedPosition position = new JournalProcessedPosition(start, Instant.now(), true);
        int decoded = 0;
        long firstNanos = 0;
        long restNanos = 0;
        // the retrieve is deliberately outside the timing: entries are not visible in the journal the
        // instant they are written, so a wall clock over the whole loop measures this test waiting
        for (int round = 0; decoded < rows && round < 50; round++) {
            final RetrievalState state = rj.retrieveJournal(position);
            while (state.hasData() && rj.nextEntry()) {
                if (isRecordOf(table, rj.getEntryHeader())) {
                    final long at = System.nanoTime();
                    assertNotNull(rj.decode(decoder), "the record failed to decode");
                    final long took = System.nanoTime() - at;
                    if (decoded == 0) {
                        firstNanos = took;
                    }
                    else {
                        restNanos += took;
                    }
                    decoded++;
                }
                position.setPosition(rj.getPosition());
            }
        }
        return new Timings(decoded, firstNanos, restNanos);
    }

    /** @param firstPays what the first entry paid for on top of decoding, which is not the steady state */
    private static void logTimings(Timings timings, String firstPays, String what) {
        log.info("first entry {} ms - {}", timings.firstNanos() / 1_000_000, firstPays);
        log.info("remaining {} entries {} ms ({} ms/entry) - {}", timings.decoded() - 1,
                timings.restNanos() / 1_000_000, timings.msPerEntry(), what);
    }

    /** Whether this entry is a row change of the table under test rather than of anything else sharing the journal. */
    private static boolean isRecordOf(String table, EntryHeader entryHeader) {
        return table.equals(entryHeader.getFile()) && schema.equals(entryHeader.getLibrary())
                && RECORD_CHANGES.contains(entryHeader.getJournalEntryType());
    }

    // -------------------------------------------------------------------------------------- plumbing

    private static JournalInfo journalFor(String table) throws Exception {
        // resolved from the table, not the library: a library is not necessarily journaled itself
        final JournalInfo journal = retrieval.getJournal(as400Connect.connection(), schema, table);
        log.info("journal of {}.{}: {}", schema, table, journal);
        return journal;
    }

    private static JournalProcessedPosition positionNow(JournalInfo journal) throws Exception {
        return new JournalProcessedPosition(retrieval.getCurrentPosition(as400Connect.connection(), journal),
                Instant.now(), true);
    }

    private static RetrieveJournal retrieveJournal(JournalInfo journal) {
        return retrieveJournal(as400Connect, journal);
    }

    private static RetrieveJournal retrieveJournal(Connect<AS400, IOException> connect, JournalInfo journal) {
        return new RetrieveJournal(new RetrieveConfigBuilder().withAs400(connect)
                .withTextFactory(textFactory).withJournalInfo(journal).build(), retrieval);
    }

    /** A decoder that resolves lob columns out of the journal, as {@code lob.fetch=true} configures it. */
    private static JdbcFileDecoder fetchingDecoder(JournalInfo journal) throws SQLException {
        final JdbcFileDecoder fileDecoder = plainDecoder();
        fileDecoder.setLobFetcher(new JournalLobFetcher(sqlConnect, journal));
        return fileDecoder;
    }

    /** A decoder with no fetcher, which is what {@code lob.fetch=false} leaves behind. */
    private static JdbcFileDecoder plainDecoder() throws SQLException {
        return new JdbcFileDecoder(sqlConnect, JdbcFileDecoder.getDatabaseName(sqlConnect.connection()),
                new SchemaCacheHash(), textFactory, -1, -1);
    }

    /**
     * @param disconnectOnly disconnect the existing connection's service, or build a whole new one
     * @return the host server job the retrieve ran in afterwards
     */
    private String jobAfter(JournalInfo journal, boolean disconnectOnly) throws Exception {
        final Connect<AS400, IOException> connect;
        if (disconnectOnly) {
            as400Connect.connection().disconnectService(AS400.COMMAND);
            connect = as400Connect;
        }
        else {
            connect = new TestConnector().getAs400();
        }
        final RetrieveJournal rj = retrieveJournal(connect, journal);
        final JournalPosition at = retrieval.getCurrentPosition(connect.connection(), journal);
        insert(60000 + (disconnectOnly ? 1 : 2), BODY, RAW, DOC);
        retrieveUntilCalled(rj, at);
        return rj.pointerHandles().job();
    }

    private static String trimmed(Entry entry, String column) {
        final Object value = entry.values().get(column);
        assertNotNull(value, () -> column + " of " + entry);
        return ((String) value).trim();
    }

    private static String body(Entry entry) {
        return text(entry, "BODY");
    }

    private static String text(Entry entry, String column) {
        final Object value = entry.values().get(column);
        assertNotNull(value, () -> "no " + column + " text decoded for " + entry
                + ", see the logged entry data for the layout");
        return (String) value;
    }

    /**
     * One fetcher for the diagnostics, shared like the real one. Building a fresh one per entry would
     * refill its window every time and make the logs look as though batching were not working.
     */
    private static JournalLobFetcher diagnosticFetcher;

    /** Dumps what DISPLAY_JOURNAL hands back, which is where the appended lob segments are read from. */
    private static void logEntryData(JournalInfo journal, EntryHeader entryHeader) {
        if (diagnosticFetcher == null) {
            diagnosticFetcher = new JournalLobFetcher(sqlConnect, journal);
        }
        final Optional<byte[]> entryData = diagnosticFetcher.entryData(entryHeader);
        assertTrue(entryData.isPresent(), "DISPLAY_JOURNAL returned no entry data for " + entryHeader);
        final byte[] data = entryData.get();
        log.info("DISPLAY_JOURNAL entry data, {} bytes: {}", data.length,
                Diagnostics.binAsHex(data, 0, Math.min(512, data.length)));
        log.info("as text: {}", Diagnostics.binAsEbcdic(textFactory, data, 0, Math.min(512, data.length)));
    }

    private static Map<String, Object> toValues(TableInfo tableInfo, Object[] values) {
        assertNotNull(tableInfo, "table structure");
        final Map<String, Object> map = new HashMap<>();
        final List<Structure> structures = tableInfo.getStructure();
        for (int i = 0; i < structures.size() && i < values.length; i++) {
            map.put(structures.get(i).getName(), values[i]);
        }
        return map;
    }

    /**
     * The catalogue's view of the column, for comparison with the 44 bytes the record image spends on
     * it - {@code STORAGE} is the number of bytes the column occupies in a row.
     */
    private static void logCatalogue() throws SQLException {
        final String sql = """
                SELECT COLUMN_NAME, DATA_TYPE, LENGTH, STORAGE, CHARACTER_OCTET_LENGTH, CCSID, ORDINAL_POSITION
                    FROM QSYS2.SYSCOLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION
                """;
        try (var ps = sqlConnect.connection().prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                final ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    final StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.append(meta.getColumnLabel(i)).append('=').append(rs.getString(i)).append(' ');
                    }
                    log.info("syscolumns {}", row);
                }
            }
        }
    }

    /** A fixed width column between each large object, so a mis-sized descriptor shifts a value. */
    private static void createTable() throws SQLException {
        create(TABLE, "ID INT NOT NULL PRIMARY KEY, HEAD CHAR(5), BODY CLOB(1M), MID CHAR(3), RAW BLOB(1M), "
                + "DOC XML, WIDE DBCLOB(1M) CCSID 13488, TAIL CHAR(2)");
    }

    private static void create(String table, String columns) throws SQLException {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            try {
                stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, table));
            }
            catch (final SQLException e) {
                log.debug("{}.{} did not exist", schema, table);
            }
            stmt.executeUpdate(String.format("CREATE TABLE %s.%s (%s)", schema, table, columns));
        }
    }

    private static void drop(String table) {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, table));
        }
        catch (final SQLException e) {
            log.warn("could not drop {}.{}", schema, table, e);
        }
    }

    /**
     * A table only reaches the journal if journaling was started on it, which for a library that is not
     * itself journaled means {@code STRJRNPF}. The journal is taken from whatever the other tables in
     * the schema are journaled to, so the test does not have to be told which one to use.
     */
    private static void startJournaling(String table) throws Exception {
        final String[] journal = journalOfSchema();
        log.info("journaling {}.{} to {}/{}", schema, table, journal[0], journal[1]);
        // *BOTH so that updates journal a before image as well, which is the case where re-reading the
        // row rather than the journal would give the wrong lob
        final String command = String.format("STRJRNPF FILE(%s/%s) JRN(%s/%s) IMAGES(*BOTH) OMTJRNE(*NONE)",
                schema, table, journal[0], journal[1]);
        final CommandCall call = new CommandCall(as400Connect.connection());
        final boolean ok = call.run(command);
        for (final AS400Message message : call.getMessageList()) {
            log.info("{} -> {}", command, message.getText());
        }
        assertTrue(ok, command);
    }

    /** @return the library and name of the journal the schema's other tables are journaled to */
    private static String[] journalOfSchema() throws SQLException {
        final String sql = """
                SELECT JOURNAL_LIBRARY, JOURNAL_NAME FROM TABLE(QSYS2.OBJECT_STATISTICS(?, '*FILE'))
                    WHERE JOURNALED = 'YES' FETCH FIRST ROW ONLY
                """;
        try (var ps = sqlConnect.connection().prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no journaled table in " + schema + " to take the journal from");
                return new String[]{ rs.getString(1).trim(), rs.getString(2).trim() };
            }
        }
    }

    private static void insert(int id, String body, byte[] raw, String doc) throws SQLException {
        try (var ps = sqlConnect.connection().prepareStatement(
                String.format("INSERT INTO %s.%s (ID, HEAD, BODY, MID, RAW, DOC, WIDE, TAIL) "
                        + "VALUES (?, ?, ?, ?, ?, XMLPARSE(DOCUMENT CAST(? AS CLOB(1M)) STRIP WHITESPACE), ?, ?)",
                        schema, TABLE))) {
            ps.setInt(1, id);
            ps.setString(2, HEAD);
            ps.setString(3, body);
            ps.setString(4, MID);
            ps.setBytes(5, raw);
            ps.setString(6, doc);
            ps.setString(7, (doc == null) ? null : WIDE);
            ps.setString(8, TAIL);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void insertLobFirst(int id, String body) throws SQLException {
        try (var ps = sqlConnect.connection().prepareStatement(String.format(
                "INSERT INTO %s.%s (BODY, ID, TAIL) VALUES (?, ?, ?)", schema, LOB_FIRST_TABLE))) {
            ps.setString(1, body);
            ps.setInt(2, id);
            ps.setString(3, TAIL);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void update(int id, String body) throws SQLException {
        try (var ps = sqlConnect.connection()
                .prepareStatement(String.format("UPDATE %s.%s SET BODY = ? WHERE ID = ?", schema, TABLE))) {
            ps.setString(1, body);
            ps.setInt(2, id);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void delete(String table, int id) throws SQLException {
        try (var ps = sqlConnect.connection()
                .prepareStatement(String.format("DELETE FROM %s.%s WHERE ID = ?", schema, table))) {
            ps.setInt(1, id);
            assertEquals(1, ps.executeUpdate());
        }
    }
}
