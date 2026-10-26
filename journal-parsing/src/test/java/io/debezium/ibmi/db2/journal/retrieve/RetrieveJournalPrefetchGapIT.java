/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Bin4;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.AS400Structure;
import com.ibm.as400.access.CommandCall;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.ServiceProgramCall;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeaderDecoder;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.OffsetStatus;
import io.debezium.ibmi.db2.journal.test.TestConnector;

/**
 * Issue #103: streams a continuous insert workload at the (delayed) journal head the way the connector
 * does - tiny buffer so every poll spans several continuations, server-side file filter, prefetch on -
 * and checks every R entry the journal holds for the table against what the reader delivered. Needs
 * {@code ISERIES_HOST}, {@code ISERIES_USER}, {@code ISERIES_PASSWORD}, {@code ISERIES_SCHEMA}.
 */
class RetrieveJournalPrefetchGapIT {
    private static final Logger log = LoggerFactory.getLogger(RetrieveJournalPrefetchGapIT.class);
    private static final String TABLE = "PFGAP";
    /** small enough for a poll at the head to need several blocks */
    private static final int BUFFER = Integer.getInteger("gap.buffer", 16 * 1024);
    /** the connector's journal.additional.delay; the system's journal cache wait is added when the journal caches */
    private static final long HEAD_DELAY_MS = Long.getLong("gap.head.delay.ms", 3000);
    /** -1 reads the system's cache wait time as the connector does, anything else overrides it */
    private static final long CACHE_DELAY_MS = Long.getLong("gap.cache.delay.ms", -1);
    private static final long POLL_MS = 250;
    private static final long WRITE_FOR_MS = Long.getLong("gap.write.ms", 45_000);

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
    }

    @AfterAll
    static void cleanup() {
        if (sqlConnect == null) {
            return;
        }
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, TABLE));
        }
        catch (final Exception e) {
            log.warn("could not drop {}.{}", schema, TABLE, e);
        }
    }

    record Block(int n, RetrievalState state, JournalProcessedPosition before, FirstHeader header, long waitMs, long rpcMs,
            List<BigInteger> tableSequences) {
        @Override
        public String toString() {
            final String seqs = tableSequences.isEmpty() ? "no table entries"
                    : String.format("%d table entries %s..%s", tableSequences.size(), tableSequences.get(0),
                            tableSequences.get(tableSequences.size() - 1));
            return String.format("block %d %s from %s/%s rpc %dms waited %dms -> %s: %s", n, state, before.getReceiver().name(),
                    before.getOffset(), rpcMs, waitMs, header, seqs);
        }
    }

    record Run(boolean prefetch, int inserted, List<Block> blocks, TreeSet<BigInteger> delivered, TreeSet<BigInteger> journaled,
            JournalProcessedPosition first, JournalPosition last) {
        TreeSet<BigInteger> missing() {
            final TreeSet<BigInteger> m = new TreeSet<>(journaled);
            m.removeAll(delivered);
            return m;
        }
    }

    @Test
    void nothingIsSkippedAcrossContinuations() throws Exception {
        final Run pipelined = run(true);
        report(pipelined);
        final Run serial = run(false);
        report(serial);
        assertTrue(pipelined.blocks().stream().filter(b -> b.state() == RetrievalState.MoreDataAvailable).count() > 5,
                "the workload must make polls run out of buffer for the continuation to be exercised");
        assertTrue(pipelined.missing().isEmpty() && serial.missing().isEmpty(),
                String.format("missing with prefetch: %s, without: %s", pipelined.missing(), serial.missing()));
    }

    /**
     * How long after deposit a cached entry becomes retrievable: once idle after a burst, and under a
     * continuous workload. The gap is only ever as wide as this latency exceeds the head delay.
     */
    @Test
    void cachedEntriesBecomeVisibleAfter() throws Exception {
        create();
        startJournaling();
        final JournalInfoRetrieval retrieval = new JournalInfoRetrieval(textFactory, 0, 0, POLL_MS);
        final JournalInfo journal = retrieval.getJournal(as400Connect.connection(), schema, TABLE);
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect).withTextFactory(textFactory)
                .withJournalInfo(journal).withJournalBufferSize(BUFFER).withServerFiltering(true)
                .withIncludeFiles(List.of(new FileFilter(schema, TABLE))).withPrefetch(false).build();
        final RetrieveJournal rj = new RetrieveJournal(config, retrieval);
        final AtomicInteger inserted = new AtomicInteger();
        log.info("visibility: {} burst of 25 rows then idle", journal);
        for (int i = 0; i < 8; i++) {
            visibility(rj, retrieval, journal, inserted, "idle");
            Thread.sleep(5000);
        }
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger background = new AtomicInteger(100_000);
        final Thread writer = new Thread(() -> {
            try {
                write(stop, background);
            }
            catch (final Exception e) {
                log.error("writer failed", e);
            }
        }, "writer");
        writer.start();
        try {
            Thread.sleep(2000);
            for (int i = 0; i < 8; i++) {
                visibility(rj, retrieval, journal, inserted, "under load");
                Thread.sleep(3000);
            }
        }
        finally {
            stop.set(true);
            writer.join();
        }
    }

    private static void visibility(RetrieveJournal rj, JournalInfoRetrieval retrieval, JournalInfo journal, AtomicInteger inserted,
                                   String what)
            throws Exception {
        final JournalPosition before = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        final int from = inserted.get() + 1;
        try (PreparedStatement ps = sqlConnect.connection()
                .prepareStatement(String.format("INSERT INTO %s.%s (ID, FILLER) VALUES (?, ?)", schema, TABLE))) {
            for (int i = 0; i < 25; i++) {
                ps.setInt(1, inserted.incrementAndGet());
                ps.setString(2, "probe");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        final long deposited = System.currentTimeMillis();
        final JournalPosition after = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        final JournalProcessedPosition start = new JournalProcessedPosition(before, Instant.EPOCH, true);
        int seen = 0;
        int polls = 0;
        String firstAnswer = null;
        while (seen < 25 && System.currentTimeMillis() - deposited < 90_000) {
            polls++;
            final RetrievalState state = rj.retrieveJournal(new JournalProcessedPosition(start),
                    new PositionRange(false, new JournalProcessedPosition(start), after));
            seen = 0;
            if (state.hasData()) {
                while (rj.nextEntry()) {
                    final EntryHeader h = rj.getEntryHeader();
                    if (h.getJournalCode() == 'R' && TABLE.equals(h.getFile().trim())) {
                        seen++;
                    }
                }
            }
            if (firstAnswer == null) {
                firstAnswer = String.format("%s %s with %d of the rows", state, rj.getFirstHeader().status(), seen);
            }
            if (seen < 25) {
                Thread.sleep(POLL_MS);
            }
        }
        log.info("visibility {}: rows {}..{} deposited in {}..{} {} after {}ms and {} polls; first answer {}", what, from, inserted.get(),
                before.getOffset(), after.getOffset(), seen == 25 ? "all retrievable" : "STILL " + (25 - seen) + " MISSING",
                System.currentTimeMillis() - deposited, polls, firstAnswer);
    }

    /**
     * Whether a slow but steady trickle of entries keeps the cache from flushing: the client's journal
     * sees ~30 entries/s, far too few to fill the cache, so what matters is whether the cache wait time
     * counts from the first cached entry or from the last deposit.
     */
    @Test
    void aTrickleDoesNotKeepEntriesCached() throws Exception {
        create();
        startJournaling();
        final JournalInfoRetrieval retrieval = new JournalInfoRetrieval(textFactory, 0, 0, POLL_MS);
        final JournalInfo journal = retrieval.getJournal(as400Connect.connection(), schema, TABLE);
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect).withTextFactory(textFactory)
                .withJournalInfo(journal).withJournalBufferSize(BUFFER).withServerFiltering(true)
                .withIncludeFiles(List.of(new FileFilter(schema, TABLE))).withPrefetch(false).build();
        final RetrieveJournal rj = new RetrieveJournal(config, retrieval);
        int id = 0;
        for (final long everyMs : new long[]{ 2000, 2000, 500, 10_000 }) {
            final JournalPosition before = retrieval.getCurrentPosition(as400Connect.connection(), journal);
            insert(++id);
            final long deposited = System.currentTimeMillis();
            final JournalPosition after = retrieval.getCurrentPosition(as400Connect.connection(), journal);
            final JournalProcessedPosition start = new JournalProcessedPosition(before, Instant.EPOCH, true);
            long nextInsert = deposited + everyMs;
            long visibleAfter = -1;
            int trickled = 0;
            while (visibleAfter < 0 && System.currentTimeMillis() - deposited < 120_000) {
                if (System.currentTimeMillis() >= nextInsert) {
                    insert(++id);
                    trickled++;
                    nextInsert += everyMs;
                }
                final RetrievalState state = rj.retrieveJournal(new JournalProcessedPosition(start),
                        new PositionRange(false, new JournalProcessedPosition(start), after));
                if (state.hasData()) {
                    while (rj.nextEntry()) {
                        final EntryHeader h = rj.getEntryHeader();
                        if (h.getJournalCode() == 'R' && TABLE.equals(h.getFile().trim())
                                && h.getSequenceNumber().compareTo(before.getOffset()) > 0) {
                            visibleAfter = System.currentTimeMillis() - deposited;
                        }
                    }
                }
                Thread.sleep(POLL_MS);
            }
            log.info("trickle: one row every {}ms, the first row {} became retrievable after {} ({} rows trickled in meanwhile)", everyMs,
                    after.getOffset(), visibleAfter < 0 ? "NEVER within 120s" : visibleAfter + "ms", trickled);
            Thread.sleep(35_000); // let the cache drain before the next scenario
        }
    }

    private static void insert(int id) throws SQLException {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            stmt.executeUpdate(String.format("INSERT INTO %s.%s (ID, FILLER) VALUES (%d, 'trickle')", schema, TABLE, id));
        }
    }

    /** What the API answers for the flushed frontier while a burst is still cached. */
    @Test
    void frontierProbe() throws Exception {
        create();
        startJournaling();
        final JournalInfoRetrieval retrieval = new JournalInfoRetrieval(textFactory, 0, 0, POLL_MS);
        final JournalInfo journal = retrieval.getJournal(as400Connect.connection(), schema, TABLE);
        final JournalPosition before = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        try (PreparedStatement ps = sqlConnect.connection()
                .prepareStatement(String.format("INSERT INTO %s.%s (ID, FILLER) VALUES (?, ?)", schema, TABLE))) {
            for (int i = 0; i < 25; i++) {
                ps.setInt(1, i + 1);
                ps.setString(2, "probe");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        final long deposited = System.currentTimeMillis();
        final JournalPosition head = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        final String rcv = head.getReceiver().name();
        final String lib = head.getReceiver().library();
        log.info("frontier: burst deposited {}..{} in {}", before.getOffset(), head.getOffset(), rcv);
        for (int i = 0; i < 32; i++) {
            final long t = System.currentTimeMillis() - deposited;
            final RetrievalCriteria a = criteria(journal);
            a.withReceiverRange(rcv, lib, rcv, lib);
            a.withFromEnt(RetrievalCriteria.FromEnt.LAST);
            a.withEnd();
            a.withMaxEntriesToReturn(1);
            final RetrievalCriteria b = criteria(journal);
            b.withReceiverRange(rcv, lib, rcv, lib);
            b.withFromEnt(head.getOffset());
            b.withEnd(head.getOffset());
            b.withMaxEntriesToReturn(1);
            final RetrievalCriteria c = criteria(journal);
            c.withReceiverRange("*CURCHAIN");
            c.withFromEnt(RetrievalCriteria.FromEnt.LAST);
            c.withEnd();
            c.withMaxEntriesToReturn(1);
            final RetrievalCriteria d = criteria(journal);
            d.withReceiverRange(rcv, lib, rcv, lib);
            d.withFromEnt(before.getOffset());
            d.withEnd(head.getOffset());
            log.info("frontier +{}ms: A rcv/*LAST -> {} | B at head {} -> {} | C *CURCHAIN/*LAST -> {} | D {}..{} -> {}", t, call(journal, a),
                    head.getOffset(), call(journal, b), call(journal, c), before.getOffset(), head.getOffset(), call(journal, d));
            Thread.sleep(1000);
        }
    }

    private static RetrievalCriteria criteria(JournalInfo journal) {
        final RetrievalCriteria c = new RetrievalCriteria(textFactory);
        c.reset();
        c.withLenNullPointerIndicatorVarLength();
        return c;
    }

    /** one unfiltered RJNE0200 call, summarised: status, entries, first and last sequence, or the message ids */
    private static String call(JournalInfo journal, RetrievalCriteria criteria) throws Exception {
        final int buffer = 64 * 1024;
        final String jrnLib = StringHelpers.padRight(journal.journalName(), 10) + StringHelpers.padRight(journal.journalLibrary(), 10);
        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, buffer),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, new AS400Bin4().toBytes(buffer)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, textFactory.text(20).toBytes(jrnLib)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, textFactory.text(8).toBytes("RJNE0200")),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE,
                        new AS400Structure(criteria.getStructure()).toBytes(criteria.getObject())),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, new AS400Bin4().toBytes(0)) };
        final ServiceProgramCall spc = new ServiceProgramCall(as400Connect.connection());
        spc.setProgram(JournalInfoRetrieval.JOURNAL_SERVICE_LIB, parameters);
        spc.setProcedureName("QjoRetrieveJournalEntries");
        spc.setAlignOn16Bytes(true);
        spc.setReturnValueFormat(ServiceProgramCall.RETURN_INTEGER);
        if (!spc.run()) {
            final StringBuilder sb = new StringBuilder();
            for (final AS400Message m : spc.getMessageList()) {
                sb.append(m.getID()).append(' ').append(m.getText()).append(' ');
            }
            return sb.toString().trim();
        }
        final byte[] data = parameters[0].getOutputData();
        final JournalProcessedPosition end = new JournalProcessedPosition();
        final FirstHeader header = new FirstHeaderDecoder(textFactory).decode(data, end);
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect).withTextFactory(textFactory)
                .withJournalInfo(journal).withPrefetch(false).build();
        final RetrieveJournal rj = new RetrieveJournal(config, new JournalInfoRetrieval(textFactory, 0, 0, POLL_MS));
        rj.setOutputData(data, header, new JournalProcessedPosition());
        BigInteger first = null;
        BigInteger last = null;
        int n = 0;
        while (header.size() > 0 && rj.nextEntry()) {
            final BigInteger seq = rj.getEntryHeader().getSequenceNumber();
            first = first == null ? seq : first;
            last = seq;
            n++;
        }
        return String.format("%s %d entries %s..%s next %s", header.status(), n, first, last, header.nextPosition().getOffset());
    }

    private static void report(Run run) {
        final TreeSet<BigInteger> missing = run.missing();
        final long moreData = run.blocks().stream().filter(b -> b.state() == RetrievalState.MoreDataAvailable).count();
        final long notCalled = run.blocks().stream().filter(b -> b.state() == RetrievalState.NotCalled).count();
        final long empty = run.blocks().stream()
                .filter(b -> b.state().hasData() && b.header() != null && b.header().status() == OffsetStatus.NO_DATA).count();
        log.info("prefetch={}: inserted {}, journal holds {} R entries for the table in {}..{}, delivered {}, missing {}; "
                + "{} polls of which {} ran out of buffer, {} found nothing to read and {} were answered with no data", run.prefetch(),
                run.inserted(), run.journaled().size(), run.first().getOffset(), run.last().getOffset(), run.delivered().size(),
                missing.size(), run.blocks().size(), moreData, notCalled, empty);
        if (missing.isEmpty()) {
            return;
        }
        // the stretches, and the blocks whose start brackets each of them
        BigInteger start = missing.first();
        BigInteger prev = start;
        final List<BigInteger[]> stretches = new ArrayList<>();
        for (final BigInteger s : missing) {
            if (s.subtract(prev).compareTo(BigInteger.valueOf(50)) > 0) {
                stretches.add(new BigInteger[]{ start, prev });
                start = s;
            }
            prev = s;
        }
        stretches.add(new BigInteger[]{ start, prev });
        for (final BigInteger[] stretch : stretches) {
            log.error("prefetch={}: journaled but never delivered: {}..{} ({} entries)", run.prefetch(), stretch[0], stretch[1],
                    missing.subSet(stretch[0], true, stretch[1], true).size());
            for (final Block b : run.blocks()) {
                if (b.before().getOffset().compareTo(stretch[0].subtract(BigInteger.valueOf(200))) >= 0
                        && b.before().getOffset().compareTo(stretch[1].add(BigInteger.valueOf(200))) <= 0) {
                    log.error("  {}", b);
                }
            }
        }
    }

    private static Run run(boolean prefetch) throws Exception {
        create();
        startJournaling();
        final long cacheDelay = CACHE_DELAY_MS >= 0 ? CACHE_DELAY_MS
                : JournalInfoRetrieval.getJournalCacheDurationInMilliseconds(sqlConnect);
        final JournalInfoRetrieval retrieval = new JournalInfoRetrieval(textFactory, cacheDelay, HEAD_DELAY_MS, POLL_MS);
        final JournalInfo journal = retrieval.getJournal(as400Connect.connection(), schema, TABLE);
        log.info("prefetch={}: {} - head delay {}ms plus {}ms cache wait when it caches", prefetch, journal, HEAD_DELAY_MS, cacheDelay);
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect).withTextFactory(textFactory)
                .withJournalInfo(journal).withJournalBufferSize(BUFFER).withServerFiltering(true)
                .withIncludeFiles(List.of(new FileFilter(schema, TABLE))).withPrefetch(prefetch).build();
        final RetrieveJournal rj = new RetrieveJournal(config, retrieval);

        final JournalPosition at = retrieval.getCurrentPosition(as400Connect.connection(), journal);
        final JournalProcessedPosition position = new JournalProcessedPosition(at, Instant.now(), true);
        final JournalProcessedPosition first = new JournalProcessedPosition(position);
        log.info("prefetch={}: streaming {} from {} with a {} byte buffer, head delayed {}ms", prefetch, journal, position, BUFFER,
                HEAD_DELAY_MS);

        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger inserted = new AtomicInteger();
        final Throwable[] writerFailure = new Throwable[1];
        final Thread writer = new Thread(() -> {
            try {
                write(stop, inserted);
            }
            catch (final Throwable t) {
                writerFailure[0] = t;
                log.error("writer failed", t);
            }
        }, "writer");
        writer.start();

        final List<Block> blocks = new ArrayList<>();
        final TreeSet<BigInteger> delivered = new TreeSet<>();
        final long started = System.currentTimeMillis();
        JournalPosition last = null;
        while (true) {
            final JournalProcessedPosition before = new JournalProcessedPosition(position);
            final RetrievalState state = rj.retrieveJournal(position);
            final List<BigInteger> tableSequences = new ArrayList<>();
            if (state.hasData()) {
                while (rj.nextEntry()) {
                    final EntryHeader h = rj.getEntryHeader();
                    if (h.getJournalCode() == 'R' && TABLE.equals(h.getFile().trim())) {
                        tableSequences.add(h.getSequenceNumber());
                        delivered.add(h.getSequenceNumber());
                    }
                    position.setPosition(rj.getPosition());
                }
                position.setPosition(rj.getPosition());
            }
            blocks.add(new Block(blocks.size() + 1, state, before, rj.getFirstHeader(), rj.lastWaitMs(), rj.lastRpcMs(), tableSequences));
            if (!stop.get() && System.currentTimeMillis() - started > WRITE_FOR_MS) {
                stop.set(true);
                writer.join();
                // the writer has stopped: read on until the delayed head has reached everything it wrote
                last = retrieval.getCurrentPosition(as400Connect.connection(), journal);
                log.info("prefetch={}: writer done after {} rows, draining up to {}", prefetch, inserted.get(), last);
            }
            if (last != null && position.getReceiver().equals(last.getReceiver())
                    && position.getOffset().compareTo(last.getOffset()) >= 0) {
                break;
            }
            if (last != null && System.currentTimeMillis() - started > WRITE_FOR_MS + 180_000) {
                log.error("prefetch={}: gave up draining at {}, head {}", prefetch, position, last);
                break;
            }
            if (state != RetrievalState.MoreDataAvailable) {
                Thread.sleep(POLL_MS);
            }
        }
        assertTrue(writerFailure[0] == null, "the writer failed: " + writerFailure[0]);
        assertTrue(first.getReceiver().equals(last.getReceiver()), "the receiver rolled during the run, sequences are not comparable");
        return new Run(prefetch, inserted.get(), blocks, delivered, journaled(journal, first.getOffset(), last.getOffset()), first, last);
    }

    private static void write(AtomicBoolean stop, AtomicInteger inserted) throws Exception {
        final Connection connection = sqlConnect.connection();
        try (PreparedStatement ps = connection
                .prepareStatement(String.format("INSERT INTO %s.%s (ID, FILLER) VALUES (?, ?)", schema, TABLE))) {
            while (!stop.get()) {
                for (int i = 0; i < 25; i++) {
                    ps.setInt(1, inserted.get() + i + 1);
                    ps.setString(2, "row " + (inserted.get() + i + 1));
                    ps.addBatch();
                }
                ps.executeBatch();
                inserted.addAndGet(25);
            }
        }
    }

    /** every R entry the journal holds for the table between the two sequences, as the connector should have seen them */
    private static TreeSet<BigInteger> journaled(JournalInfo journal, BigInteger from, BigInteger to) throws Exception {
        final String sql = String.format("""
                SELECT SEQUENCE_NUMBER
                    FROM TABLE(QSYS2.DISPLAY_JOURNAL('%s', '%s',
                        STARTING_SEQUENCE => CAST(? AS DECIMAL(21,0)),
                        ENDING_SEQUENCE => CAST(? AS DECIMAL(21,0)),
                        JOURNAL_CODES => 'R',
                        OBJECT_LIBRARY => '%s', OBJECT_NAME => '%s',
                        OBJECT_OBJTYPE => '*FILE', OBJECT_MEMBER => '*ALL'))
                """, journal.journalLibrary(), journal.journalName(), schema, TABLE);
        final TreeSet<BigInteger> sequences = new TreeSet<>();
        try (PreparedStatement ps = sqlConnect.connection().prepareStatement(sql)) {
            ps.setLong(1, from.longValue());
            ps.setLong(2, to.longValue());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sequences.add(rs.getBigDecimal(1).toBigInteger());
                }
            }
        }
        return sequences;
    }

    private static void create() throws SQLException {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            try {
                stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, TABLE));
            }
            catch (final SQLException e) {
                log.debug("{}.{} did not exist", schema, TABLE);
            }
            stmt.executeUpdate(String.format("CREATE TABLE %s.%s (ID INT NOT NULL PRIMARY KEY, FILLER CHAR(200))", schema, TABLE));
        }
    }

    private static void startJournaling() throws Exception {
        final String command = String.format("STRJRNPF FILE(%s/%s) JRN(%s/JRNTEST) IMAGES(*BOTH) OMTJRNE(*NONE)", schema, TABLE,
                schema);
        final CommandCall call = new CommandCall(as400Connect.connection());
        final boolean ok = call.run(command);
        for (final AS400Message message : call.getMessageList()) {
            log.info("{} -> {}", command, message.getText());
        }
        assertTrue(ok, command);
    }
}
