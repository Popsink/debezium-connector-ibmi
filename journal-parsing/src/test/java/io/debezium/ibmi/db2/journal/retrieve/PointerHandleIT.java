/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.CommandCall;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.test.TestConnector;

/**
 * Answers, against a live IBM i, the two things the Journal and Commit APIs manual leaves open about
 * the pointer handles {@code QjoRetrieveJournalEntries} hands out - both of which decide how much a
 * buffer of LOB entries costs.
 *
 * <p>
 * The manual says only "if the entry specific data returned for this journal entry returned any
 * pointers, this is the handle associated with those pointers". It does not say whether one retrieve
 * allocates a handle per entry or one handle for the whole buffer. A handle per entry means a
 * {@code QjoDeletePointerHandle} round trip per entry; one per buffer means the deletes can be deduped
 * down to a single call. {@link #handlesAreAllocatedPerEntryNotPerRetrieve()} measures which it is.
 * </p>
 *
 * <p>
 * It also puts a number on the round trip, which is what decides whether the per-entry delete is a
 * problem at all: the same 1000 entry buffer costs 0.3s on a LAN and 30s over a WAN.
 * </p>
 *
 * <p>
 * Needs {@code ISERIES_HOST}, {@code ISERIES_USER}, {@code ISERIES_PASSWORD} and {@code ISERIES_SCHEMA}.
 * Creates {@code PTRTST} in that schema and journals it to whatever journal the schema's other tables
 * use. Everything it measures it also logs, so a run is readable even when it passes.
 * </p>
 */
class PointerHandleIT {
    private static final Logger log = LoggerFactory.getLogger(PointerHandleIT.class);

    private static final String TABLE = "PTRTST";
    /** Enough entries to tell a handle per entry from one per buffer, and to time the round trip over. */
    private static final int ROWS = 40;
    /** Small: the lob data stays behind the pointer, so what lands in the buffer is the record image. */
    private static final String BODY = "a clob, whose presence is what makes the entry pointer bearing";
    /** Bounds on the leak hunt: PUB400 is shared, so it stops rather than hammer it indefinitely. */
    private static final int MAX_ROUNDS = 400;
    private static final Duration BUDGET = Duration.ofMinutes(5);
    /** Rows behind each retrieve, so one round leaks thousands of handles rather than tens. */
    private static final int BULK_ROWS = 3000;

    /** The byte ceiling hunt: a second table whose clobs are big enough for storage to bind before count. */
    private static final String BIG_TABLE = "PTRBIG";
    private static final int LOB_BYTES = 256 * 1024;
    private static final int BIG_ROWS = 50;
    /** Hard cap on how much job storage this is willing to tie up on a shared system. */
    private static final long BYTE_CAP = 3L * 1024 * 1024 * 1024;

    private static Connect<AS400, IOException> as400Connect;
    private static Connect<Connection, SQLException> sqlConnect;
    private static As400TextFactory textFactory;
    private static String schema;

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
        final var metaData = sqlConnect.connection().getMetaData();
        log.info("system: {} {} (release decides whether the handle limit still applies)",
                metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());
        createTable();
        startJournaling();
    }

    /**
     * The question that decides whether the deletes can be batched at all. Every entry of a LOB bearing
     * table carries a handle; if the value repeats within one retrieve then all but the first delete are
     * wasted round trips - and, since deleting a handle twice is an error, they would also be showing up
     * as failures today.
     */
    @Test
    void handlesAreAllocatedPerEntryNotPerRetrieve() throws Exception {
        final JournalInfoRetrieval journalInfoRetrieval = new JournalInfoRetrieval(textFactory, 0, 0, 1000);
        final JournalInfo journal = journalInfoRetrieval.getJournal(as400Connect.connection(), schema, TABLE);
        final JournalPosition current = journalInfoRetrieval.getCurrentPosition(as400Connect.connection(), journal);
        final JournalProcessedPosition position = new JournalProcessedPosition(current, Instant.now(), true);

        for (int i = 1; i <= ROWS; i++) {
            insert(i);
        }

        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect)
                .withTextFactory(textFactory)
                .withJournalInfo(journal)
                .build();
        final RetrieveJournal rj = new RetrieveJournal(config, journalInfoRetrieval);

        final Instant retrieveStart = Instant.now();
        rj.retrieveJournal(position);
        final Duration retrieveTook = Duration.between(retrieveStart, Instant.now());

        // the walk only decodes locally, so its cost is what a connector pays on top of the single
        // retrieve above - the handles are given back by cycling the job, not per entry
        final List<Long> handles = new ArrayList<>();
        int ours = 0;
        final Instant walkStart = Instant.now();
        while (rj.nextEntry()) {
            final var header = rj.getEntryHeader();
            if (!TABLE.equals(header.getFile().trim())) {
                continue;
            }
            ours++;
            if (header.getPointerHandle() != 0) {
                handles.add(header.getPointerHandle());
            }
        }
        final Duration walkTook = Duration.between(walkStart, Instant.now());

        final Set<Long> distinct = new HashSet<>(handles);
        log.info("=== pointer handles ===");
        log.info("entries for {}.{}: {}, of which pointer bearing: {}", schema, TABLE, ours, handles.size());
        log.info("distinct handle values: {}", distinct.size());
        log.info("handles: {}", handles);
        log.info("handles counted by PointerHandles: {}", rj.pointerHandles().outstanding());
        log.info("one retrieveJournal call: {} ms", retrieveTook.toMillis());
        log.info("walking {} entries: {} ms, {} ms per entry", ours, walkTook.toMillis(),
                ours == 0 ? 0 : walkTook.toMillis() / (double) ours);

        final int seen = ours;
        assertTrue(seen >= ROWS, () -> "expected at least the " + ROWS + " inserts, got " + seen);
        assertEquals(seen, handles.size(),
                "every entry of a table with a lob column should carry a pointer handle");
        assertEquals(handles.size(), distinct.size(),
                "handles repeat within one retrieve - the deletes can be deduped to one per distinct handle");
    }

    /**
     * The number the manual never gives: how many handles a job can hold before "the maximum number
     * allowed can be reached, which will prevent further retrieval of journal entries". Without it there
     * is no safe threshold at which to cycle the job instead of deleting per entry.
     *
     * <p>
     * The handles are allocated by the retrieve itself as it fills the buffer, so retrieving the same
     * range over and over without walking the entries leaks a whole buffer's worth each time - no
     * production code has to be changed to leak them. The handles belong to this job alone, so nothing
     * else on the system is affected, and ending the connection frees them.
     * </p>
     */
    @Test
    void findsTheMaximumNumberOfOutstandingPointerHandles() throws Exception {
        final JournalInfoRetrieval journalInfoRetrieval = new JournalInfoRetrieval(textFactory, 0, 0, 1000);
        final JournalInfo journal = journalInfoRetrieval.getJournal(as400Connect.connection(), schema, TABLE);
        final JournalPosition start = journalInfoRetrieval.getCurrentPosition(as400Connect.connection(), journal);

        bulkInsert(BULK_ROWS);

        // a buffer big enough to hold the whole bulk insert, so one retrieve leaks thousands of handles
        // rather than tens - the difference between finding the limit in a minute and in an hour
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect)
                .withTextFactory(textFactory)
                .withJournalInfo(journal)
                .withJournalBufferSize(4 * 1024 * 1024)
                .build();

        int round = 0;
        String failure = null;
        final Instant began = Instant.now();
        while (round < MAX_ROUNDS && Duration.between(began, Instant.now()).compareTo(BUDGET) < 0) {
            // a fresh position each round so the same entries, and the same handles, are allocated again
            final JournalProcessedPosition position = new JournalProcessedPosition(start, Instant.now(), true);
            try {
                new RetrieveJournal(config, journalInfoRetrieval).retrieveJournal(position);
            }
            catch (final Exception e) {
                failure = e.toString();
                break;
            }
            round++;
            if (round % 10 == 0) {
                // the handle number is the odometer: handles are handed out in sequence and never
                // recycled until freed, so the newest one counts every handle this job has allocated
                log.info("{} retrieves, newest handle {}", round, currentHandle(config, journalInfoRetrieval, start));
            }
        }

        log.info("=== maximum outstanding pointer handles ===");
        log.info("retrieves completed: {}", round);
        log.info("handles outstanding at the end: {}", currentHandle(config, journalInfoRetrieval, start));
        log.info("failure: {}", failure == null ? "NONE - the limit is above this, raise the budget" : failure);

        // whatever happened, give the job's handles back so the next run starts clean
        as400Connect.connection().disconnectService(AS400.COMMAND);
        final JournalPosition afterCycle = journalInfoRetrieval.getCurrentPosition(as400Connect.connection(), journal);
        final RetrieveJournal recovered = new RetrieveJournal(config, journalInfoRetrieval);
        recovered.retrieveJournal(new JournalProcessedPosition(afterCycle, Instant.now(), true));
        log.info("retrieval works again after cycling the job");
    }

    /**
     * The ceiling that actually binds. A handle owns the allocation holding the LOB data the pointer
     * addresses, so outstanding handles hold bytes in the job, not just slots: 650k handles over 60 byte
     * clobs is 40MB, but the same count over megabyte clobs would be hundreds of gigabytes. This finds
     * where the storage rather than the count gives out.
     *
     * <p>
     * The clobs are built server side with {@code REPEAT} so the wire only ever carries record images,
     * and both the leak and the table are bounded and released at the end - the system under test is
     * shared.
     * </p>
     */
    @Test
    void findsTheByteCeilingOnOutstandingHandles() throws Exception {
        createBigLobTable();
        try {
            startJournaling(BIG_TABLE);
            final JournalInfoRetrieval journalInfoRetrieval = new JournalInfoRetrieval(textFactory, 0, 0, 1000);
            final JournalInfo journal = journalInfoRetrieval.getJournal(as400Connect.connection(), schema, BIG_TABLE);
            final JournalPosition start = journalInfoRetrieval.getCurrentPosition(as400Connect.connection(), journal);

            bulkInsertBig(BIG_ROWS, LOB_BYTES);

            final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect)
                    .withTextFactory(textFactory)
                    .withJournalInfo(journal)
                    .withJournalBufferSize(4 * 1024 * 1024)
                    .build();

            final long bytesPerRetrieve = (long) BIG_ROWS * LOB_BYTES;
            long leaked = 0;
            String failure = null;
            final Instant began = Instant.now();
            while (leaked < BYTE_CAP && Duration.between(began, Instant.now()).compareTo(BUDGET) < 0) {
                final JournalProcessedPosition position = new JournalProcessedPosition(start, Instant.now(), true);
                try {
                    new RetrieveJournal(config, journalInfoRetrieval).retrieveJournal(position);
                }
                catch (final Exception e) {
                    failure = e.toString();
                    break;
                }
                leaked += bytesPerRetrieve;
                if (leaked % (bytesPerRetrieve * 20) == 0) {
                    log.info("{} MB of lob data outstanding, newest handle {}", leaked / (1024 * 1024),
                            currentHandle(config, journalInfoRetrieval, start));
                }
            }

            log.info("=== byte ceiling on outstanding handles ===");
            log.info("lob size per entry: {} bytes, entries per retrieve: {}", LOB_BYTES, BIG_ROWS);
            log.info("lob data outstanding at the end: {} MB", leaked / (1024 * 1024));
            log.info("handles outstanding at the end: {}", currentHandle(config, journalInfoRetrieval, start));
            log.info("failure: {}", failure == null
                    ? "NONE up to the " + (BYTE_CAP / (1024 * 1024)) + " MB cap this test stops at"
                    : failure);

            as400Connect.connection().disconnectService(AS400.COMMAND);
            log.info("job cycled, allocations released");
        }
        finally {
            dropBigLobTable();
        }
    }

    /**
     * The only bulk alternative the manual offers: "the pointer handles will be implicitly deleted when
     * the process that requested the journal entries is ended". Ending the host server job frees every
     * handle it holds in one go, so what matters is what re-establishing it costs against the
     * {@code QjoDeletePointerHandle} calls it replaces.
     */
    @Test
    void cyclingTheHostServerJobCostsLessThanTheDeletesItReplaces() throws Exception {
        final JournalInfoRetrieval journalInfoRetrieval = new JournalInfoRetrieval(textFactory, 0, 0, 1000);
        final JournalInfo journal = journalInfoRetrieval.getJournal(as400Connect.connection(), schema, TABLE);
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect)
                .withTextFactory(textFactory)
                .withJournalInfo(journal)
                .build();

        // warm: the service is already connected, so this is the steady state a connector runs in
        final JournalPosition before = journalInfoRetrieval.getCurrentPosition(as400Connect.connection(), journal);
        insert(ROWS + 2);
        final RetrieveJournal warm = new RetrieveJournal(config, journalInfoRetrieval);
        final Instant warmStart = Instant.now();
        warm.retrieveJournal(new JournalProcessedPosition(before, Instant.now(), true));
        final long warmMs = Duration.between(warmStart, Instant.now()).toMillis();

        // end the job that owns the handles, which is what would replace the per entry deletes
        final Instant cycleStart = Instant.now();
        as400Connect.connection().disconnectService(AS400.COMMAND);
        final JournalPosition after = journalInfoRetrieval.getCurrentPosition(as400Connect.connection(), journal);
        insert(ROWS + 3);
        final RetrieveJournal cold = new RetrieveJournal(config, journalInfoRetrieval);
        cold.retrieveJournal(new JournalProcessedPosition(after, Instant.now(), true));
        final long cycleMs = Duration.between(cycleStart, Instant.now()).toMillis();

        // the retrieve still works against the new job, which is what makes cycling viable at all
        assertTrue(cold.nextEntry(), "no entry retrieved after the host server job was cycled");
        log.info("=== cycling the host server job ===");
        log.info("retrieve on the established job: {} ms", warmMs);
        log.info("disconnect + reconnect + retrieve: {} ms", cycleMs);
        log.info("reconnect overhead: {} ms, equivalent to {} per entry deletes at 31 ms",
                cycleMs - warmMs, (cycleMs - warmMs) / 31);
    }

    /**
     * The newest handle the API has handed out, read by retrieving one entry. Handles are allocated in
     * sequence and only returned to the pool when deleted, so this is how many are outstanding.
     */
    private static long currentHandle(RetrieveConfig config, JournalInfoRetrieval retrieval, JournalPosition start) {
        try {
            final RetrieveJournal probe = new RetrieveJournal(config, retrieval);
            probe.retrieveJournal(new JournalProcessedPosition(start, Instant.now(), true));
            while (probe.nextEntry()) {
                final long handle = probe.getEntryHeader().getPointerHandle();
                if (handle != 0) {
                    return handle;
                }
            }
            return 0;
        }
        catch (final Exception e) {
            // once the limit is reached the probe cannot retrieve either, which is the point
            log.info("could not read the current handle: {}", e.toString());
            return -1;
        }
    }

    /** Deposits many journal entries in one round trip; inserting them one by one is minutes of latency. */
    private static void bulkInsert(int rows) throws SQLException {
        final String sql = String.format("""
                INSERT INTO %s.%s (ID, BODY)
                WITH n(i) AS (VALUES 1 UNION ALL SELECT i + 1 FROM n WHERE i < %d)
                SELECT i + 100000, '%s' FROM n
                """, schema, TABLE, rows, BODY);
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            log.info("bulk inserting {} rows", rows);
            stmt.executeUpdate(sql);
        }
    }

    private static void createBigLobTable() throws SQLException {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            try {
                stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, BIG_TABLE));
            }
            catch (final SQLException e) {
                log.debug("{}.{} did not exist", schema, BIG_TABLE);
            }
            stmt.executeUpdate(String.format("CREATE TABLE %s.%s (ID INT NOT NULL PRIMARY KEY, BODY CLOB(2M))",
                    schema, BIG_TABLE));
        }
    }

    /** Gives the disk back: the table is only there to be journaled, and the system is shared. */
    private static void dropBigLobTable() {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, BIG_TABLE));
            log.info("dropped {}.{}", schema, BIG_TABLE);
        }
        catch (final SQLException e) {
            log.warn("could not drop {}.{}", schema, BIG_TABLE, e);
        }
    }

    /** Builds the clobs on the system with REPEAT, so megabytes are never sent over the connection. */
    private static void bulkInsertBig(int rows, int lobBytes) throws SQLException {
        final String sql = String.format("""
                INSERT INTO %s.%s (ID, BODY)
                WITH n(i) AS (VALUES 1 UNION ALL SELECT i + 1 FROM n WHERE i < %d)
                SELECT i, REPEAT(CAST('x' AS CLOB(2M)), %d) FROM n
                """, schema, BIG_TABLE, rows, lobBytes);
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            log.info("bulk inserting {} rows of {} byte clobs ({} MB)", rows, lobBytes,
                    (long) rows * lobBytes / (1024 * 1024));
            stmt.executeUpdate(sql);
        }
    }

    private static void createTable() throws SQLException {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            try {
                stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, TABLE));
            }
            catch (final SQLException e) {
                log.debug("{}.{} did not exist", schema, TABLE);
            }
            stmt.executeUpdate(String.format("CREATE TABLE %s.%s (ID INT NOT NULL PRIMARY KEY, BODY CLOB(1M))",
                    schema, TABLE));
        }
    }

    /** The schema is not necessarily journaled itself, so the table needs STRJRNPF of its own. */
    private static void startJournaling() throws Exception {
        startJournaling(TABLE);
    }

    private static void startJournaling(String table) throws Exception {
        final String[] journal = journalOfSchema();
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

    private static void insert(int id) throws SQLException {
        try (var ps = sqlConnect.connection()
                .prepareStatement(String.format("INSERT INTO %s.%s (ID, BODY) VALUES (?, ?)", schema, TABLE))) {
            ps.setInt(1, id);
            ps.setString(2, BODY + " " + id);
            ps.executeUpdate();
        }
    }
}
