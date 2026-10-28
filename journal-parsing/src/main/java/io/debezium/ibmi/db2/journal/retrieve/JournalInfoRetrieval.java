/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Bin4;
import com.ibm.as400.access.AS400Bin8;
import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.AS400Structure;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.FileAttributes;
import com.ibm.as400.access.ProgramCall;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.QSYSObjectPathName;
import com.ibm.as400.access.ServiceProgramCall;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.exception.JournalReceiverNotFoundException;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.KeyDecoder;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.KeyHeader;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.ReceiverDecoder;

/**
 * @see http://www.setgetweb.com/p/i5/rzakiwrkjrna.htm
 * @author sillencem
 *
 */
public class JournalInfoRetrieval {
    private static final Logger log = LoggerFactory.getLogger(JournalInfoRetrieval.class);

    public static final String JOURNAL_SERVICE_LIB = "/QSYS.LIB/QJOURNAL.SRVPGM";

    /**
     * IBM i message ids that identify a permanently missing object (typically a pruned journal
     * receiver or its library). When a service program call fails with one of these, the stored
     * position is gone for good and the failure must be treated as non-transient rather than retried.
     * <ul>
     * <li>CPF9801 - Object &amp;2 in library &amp;3 not found</li>
     * <li>CPF9810 - Library &amp;1 not found</li>
     * <li>CPF9812 - File &amp;1 in library &amp;2 not found</li>
     * <li>CPF7025 - Object cannot be found (journal API)</li>
     * </ul>
     */
    static final Set<String> RECEIVER_NOT_FOUND_MESSAGE_IDS = Set.of("CPF9801", "CPF9810", "CPF9812", "CPF7025");

    private final As400TextFactory textFactory;
    private final byte[] emptyAs400Text;
    private final AS400Text as400Text8;
    private final AS400Text as400Text20;
    private final AS400Text as400Text1;
    private final AS400Text as400Text10;
    private final AS400Bin8 as400Bin8 = new AS400Bin8();
    private final AS400Bin4 as400Bin4 = new AS400Bin4();
    private static final int KEY_HEADER_LENGTH = 20;
    private final DetailedJournalReceiverCache cache = new DetailedJournalReceiverCache();
    /** the caching flag as last read from the server: at startup, then with every poll's attached receiver */
    private final Map<String, Boolean> isJournalCaching = new ConcurrentHashMap<>();
    private Map<JournalInfo, DelayedDetailedJournalReceiver> delayedCache = new HashMap<>();
    private final Map<JournalInfo, VerifiedHead> verifiedHeads = new HashMap<>();
    /**
     * the regime {@link #getDelayedDetailedJournalReceiver} last chose for the journal: a snapshot of
     * {@link #isJournalCaching}, taken once per resolved range, so that a flag read in between (by
     * {@link #getCurrentPosition} for instance) changes nothing until the next range is resolved
     */
    private final Map<String, Boolean> cachingInForce = new HashMap<>();
    private final long journalCacheDelay;
    private final long additionalJournalDelay;
    private final long pollInterval;

    public JournalInfoRetrieval(As400TextFactory textFactory, long journalCacheDelay, long additionalJournalDelay,
                                long pollInterval) {
        super();
        this.textFactory = textFactory;
        this.emptyAs400Text = textFactory.text(0).toBytes("");
        this.as400Text8 = textFactory.text(8);
        this.as400Text20 = textFactory.text(20);
        this.as400Text1 = textFactory.text(1);
        this.as400Text10 = textFactory.text(10);
        this.journalCacheDelay = journalCacheDelay;
        this.additionalJournalDelay = additionalJournalDelay;
        this.pollInterval = pollInterval;
    }

    /**
     * How far behind live {@link #getDelayedDetailedJournalReceiver} may hold the journal head: the configured
     * additional delay for a journal that does not cache, plus the cache wait time for one that does, whose
     * head is proven readable rather than aged (see {@link #verifiedHead}) and can lag live by up to that long.
     */
    public long headDelayMs(JournalInfo journalLib) {
        return additionalJournalDelay + (isCaching(journalLib) ? journalCacheDelay : 0L);
    }

    /**
     * Whether the journal is read as caching: the regime chosen when the current range was resolved, or the
     * flag read at startup until a range has been.
     */
    private boolean isCaching(JournalInfo journal) {
        return cachingInForce.getOrDefault(journalKey(journal.journalName(), journal.journalLibrary()), journal.isCaching());
    }

    /**
     * Records the journal's caching flag as just read from the server. It takes effect with the next range
     * resolved, see {@link #cachingRegime}.
     */
    void observeCaching(JournalInfo journal, boolean caching) {
        isJournalCaching.put(journalKey(journal.journalName(), journal.journalLibrary()), caching);
    }

    private static String journalKey(String journalName, String journalLib) {
        return journalName + journalLib;
    }

    /**
     * The regime to read the journal head with for the range being resolved: the caching flag read with this
     * poll's attached receiver, so that a journal switched to {@code JRNCACHE(*YES)} while the connector runs
     * is no longer read on the timer, which loses the cached tail of every range (issue #103). A switch either
     * way is logged, and what the other regime knew about the head is dropped: the timer starts again from
     * now, the proven head from nothing, and nothing is read until the new regime has a head.
     */
    private boolean cachingRegime(JournalInfo journal) {
        final String key = journalKey(journal.journalName(), journal.journalLibrary());
        final boolean caching = isJournalCaching.getOrDefault(key, journal.isCaching());
        final Boolean previous = cachingInForce.put(key, caching);
        final boolean wasCaching = previous == null ? journal.isCaching() : previous;
        if (caching != wasCaching) {
            verifiedHeads.remove(journal);
            delayedCache.remove(journal);
            if (caching) {
                log.warn("journal {}/{} now caches: reading up to the head the server proves readable instead of {}ms "
                        + "behind live (issue #103)", journal.journalLibrary(), journal.journalName(), additionalJournalDelay);
            }
            else {
                log.warn("journal {}/{} no longer caches: reading its head {}ms behind live", journal.journalLibrary(),
                        journal.journalName(), additionalJournalDelay);
            }
        }
        return caching;
    }

    public JournalPosition getCurrentPosition(AS400 as400, JournalInfo journalLib) throws Exception {
        final JournalReceiver ji = getReceiver(as400, journalLib);
        final BigInteger offset = getOffset(as400, ji).end();
        return new JournalPosition(offset, ji);
    }

    static final Pattern JOURNAL_REGEX = Pattern.compile("\\/[^/]*\\/([^.]*).LIB\\/(.*).JRN");

    public JournalInfo getJournal(AS400 as400, String schema) throws IllegalStateException {
        // https://stackoverflow.com/questions/43061626/as-400-create-journal-jt400-java
        // note each file can have it's own different journal
        try {
            final FileAttributes fa = new FileAttributes(as400, String.format("/QSYS.LIB/%s.LIB", schema));
            final Matcher m = JOURNAL_REGEX.matcher(fa.getJournal());
            if (m.matches()) {
                String journalName = m.group(2);
                String journalLib = m.group(1);
                boolean isCaching = isJournalCaching(as400, journalName, journalLib);
                return new JournalInfo(journalName, journalLib, isCaching);
            }
            else {
                log.error("no match searching for journal filename {}", fa.getJournal());
            }
        }
        catch (final Exception e) {
            throw new IllegalStateException("Journal not found", e);
        }
        throw new IllegalStateException("Journal not found");
    }

    /**
     * The head of the journal that it is safe to read up to.
     *
     * <p>On a caching journal the receiver reports entries as soon as they are deposited, but
     * {@code QjoRetrieveJournalEntries} only returns them once the cache has been written to disk, up to the
     * system's cache wait time later, and answers "no entries" in between (issue #104). The head is therefore
     * not aged by a timer but proven: see {@link #verifiedHead}. The cache wait time and
     * {@code journal.additional.delay} are not applied to it.</p>
     *
     * <p>A journal that does not cache has nothing to wait for; the configured additional delay is still
     * honoured for it as before.</p>
     */
    public Optional<DetailedJournalReceiver> getDelayedDetailedJournalReceiver(AS400 as400, JournalInfo journalLib)
            throws Exception {
        DetailedJournalReceiver dr = getCurrentDetailedJournalReceiver(as400, journalLib);
        if (cachingRegime(journalLib)) {
            return verifiedHead(as400, journalLib, dr);
        }
        long totalDelay = headDelayMs(journalLib);
        if (totalDelay > 0) {
            if (!delayedCache.containsKey(journalLib)) {
                DelayedDetailedJournalReceiver ddr = new DelayedDetailedJournalReceiver(totalDelay, pollInterval);
                delayedCache.put(journalLib, ddr);
            }
            DelayedDetailedJournalReceiver cached = delayedCache.get(journalLib);
            cached.addDetailedReceiver(dr);
            return Optional.ofNullable(cached.getDelayedReceiver());
        }
        else {
            return Optional.of(dr);
        }
    }

    /**
     * What the server has proven readable for one caching journal, and where the search for more starts
     * while nothing on the current receiver is proven yet (the head first seen on it).
     */
    static final class VerifiedHead {
        DetailedJournalReceiver proven;
        DetailedJournalReceiver anchor;
    }

    /**
     * Receiver variable of the stretch retrieve in {@link #newestRetrievable}, in bytes. It returns the
     * flushed entries themselves, so this bounds how far the proven head can move per call. The cache is
     * written out a whole buffer at a time (850 entries of a small row on PUB400, 285 KB in this format,
     * every 9-13 s at 100 rows/s), so a call that takes one flush whole keeps the head one poll behind the
     * flush; a block cut short still ends on a readable entry and the rest comes with the next poll.
     * Allocated per call, and only when the head has moved past what is proven.
     */
    static final int RANGE_PROBE_BUFFER = 1024 * 1024;

    /**
     * The newest head the server has proven it can read, for a caching journal.
     *
     * <p>Entries are written from the cache to the receiver in sequence order, so if an entry is
     * retrievable every entry below it is too, and a range ending there can be trusted when the server
     * says it is exhausted. The server is asked for the whole stretch from the proven head to live,
     * unfiltered: it returns the entries flushed so far and stops at the cache, and the last entry it
     * returns is the newest readable one. No entry is guessed at and probed on its own: the cache is
     * flushed a whole buffer at a time, and the newest entry deposited is held back until the journal has
     * been idle for a while (~23 s on PUB400), so while anything is being written the live head is
     * almost never readable and no fixed point below it can be trusted either. When nothing has flushed
     * since the previous poll the proven head is kept, and the range simply does not grow until the cache
     * is flushed. One call per poll, none when the head has not moved past what is already proven.</p>
     *
     * @return the head to resolve the range against, empty until a first head has been proven
     */
    Optional<DetailedJournalReceiver> verifiedHead(AS400 as400, JournalInfo journal, DetailedJournalReceiver live)
            throws Exception {
        final VerifiedHead state = verifiedHeads.computeIfAbsent(journal, j -> new VerifiedHead());
        if (isProvenUpTo(state.proven, live)) {
            return Optional.of(state.proven);
        }
        if (state.anchor == null || !state.anchor.isSameReceiver(live)) {
            state.anchor = live;
        }
        final BigInteger from = (state.proven != null && state.proven.isSameReceiver(live))
                ? state.proven.end().add(BigInteger.ONE)
                : state.anchor.end();
        final Optional<BigInteger> newest = newestRetrievable(as400, journal, live.info().receiver(), from, live.end());
        if (newest.isPresent()) {
            if (newest.get().compareTo(live.end()) < 0) {
                log.debug("journal head {} is still in the cache, reading up to {}, the newest entry flushed", live.end(),
                        newest.get());
            }
            state.proven = live.withEnd(newest.get());
            return Optional.of(state.proven);
        }
        if (state.proven == null) {
            log.debug("journal head {} is still in the cache and nothing below it has been proven readable yet", live.end());
            return Optional.empty();
        }
        log.debug("journal head {} is still in the cache, reading up to the proven head {}", live.end(), state.proven.end());
        return Optional.of(state.proven);
    }

    private static boolean isProvenUpTo(DetailedJournalReceiver proven, DetailedJournalReceiver head) {
        return proven != null && proven.isSameReceiver(head) && proven.end().compareTo(head.end()) >= 0;
    }

    /**
     * The newest entry in {@code [from, to]} the server can retrieve now: one unfiltered
     * {@code QjoRetrieveJournalEntries} call over the range, which returns the entries already flushed from
     * the journal cache and stops at the first that is not, so the last entry returned is the answer. Empty
     * when none of the range has flushed yet. A block cut short by {@link #RANGE_PROBE_BUFFER} still ends on
     * a readable entry; it is just not the newest.
     */
    Optional<BigInteger> newestRetrievable(AS400 as400, JournalInfo journal, JournalReceiver receiver, BigInteger from,
                                           BigInteger to)
            throws Exception {
        final Optional<byte[]> data = retrieveUnfiltered(as400, journal, receiver, from, to, RANGE_PROBE_BUFFER);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        // RJNE0100: offset to the first entry header at 4, entries retrieved at 8; each entry header holds the
        // displacement to the next at 0 and, after four displacements, the sequence number as 20 characters
        // at 16 (RJNE0200 has six displacements and its sequence number at 24: the two formats differ here)
        final byte[] block = data.get();
        final int entries = decodeInt(block, 8);
        int offset = decodeInt(block, 4);
        if (entries == 0 || offset <= 0) {
            return Optional.empty();
        }
        for (int i = 1; i < entries; i++) {
            offset += decodeInt(block, offset);
        }
        return Optional.of(decodeBigIntFromString(block, offset + 16));
    }

    /**
     * One unfiltered {@code QjoRetrieveJournalEntries} call over {@code [from, to]} of {@code receiver}, in
     * the {@code RJNE0100} format so that an entry with lob data allocates no pointer handle in the host job.
     * "No entries" (CPF7062) means the range is still in the journal cache; a sequence the receiver does not
     * hold (CPF7053) is not readable either and is left to the receiver-chain checks to explain. Both come
     * back empty; anything else is a failure.
     */
    private Optional<byte[]> retrieveUnfiltered(AS400 as400, JournalInfo journal, JournalReceiver receiver, BigInteger from,
                                                BigInteger to, int bufferLength)
            throws Exception {
        final RetrievalCriteria criteria = new RetrievalCriteria(textFactory);
        criteria.reset();
        criteria.withReceiverRange(receiver.name(), receiver.library(), receiver.name(), receiver.library());
        criteria.withFromEnt(from);
        criteria.withEnd(to);
        final String journalName = padRight(journal.journalName(), 10) + padRight(journal.journalLibrary(), 10);
        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, bufferLength),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(bufferLength)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text20.toBytes(journalName)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text8.toBytes("RJNE0100")),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE,
                        new AS400Structure(criteria.getStructure()).toBytes(criteria.getObject())),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(0)) };
        final ServiceProgramCall spc = new ServiceProgramCall(as400);
        spc.getServerJob().setLoggingLevel(0);
        spc.setProgram(JOURNAL_SERVICE_LIB, parameters);
        spc.setProcedureName("QjoRetrieveJournalEntries");
        spc.setAlignOn16Bytes(true);
        spc.setReturnValueFormat(ServiceProgramCall.RETURN_INTEGER);
        if (spc.run()) {
            return Optional.of(parameters[0].getOutputData());
        }
        for (final AS400Message message : spc.getMessageList()) {
            final String id = message.getID();
            if ("CPF7062".equals(id) || "CPF7053".equals(id)) {
                log.debug("entries {}-{} of {} are not retrievable yet: {} {}", from, to, receiver, id, message.getText());
                return Optional.empty();
            }
        }
        throw classifyServiceProgramFailure(JOURNAL_SERVICE_LIB, "QjoRetrieveJournalEntries", spc.getMessageList());
    }

    public static long getJournalCacheDurationInMilliseconds(Connect<Connection, SQLException> jdbcConnection) {
        try {
            Connection connection = jdbcConnection.connection();
            String sql = "SELECT JOURNAL_CACHE_WAIT_TIME FROM QSYS2.SYSTEM_STATUS_INFO";
            try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("JOURNAL_CACHE_WAIT_TIME") * 1000L;
                    }
                    else {
                        log.error("unable to retrieve journal cache wait time defaulting to 30 seconds");
                        return 30000L;
                    }
                }
            }
        }
        catch (SQLException e) {
            log.error("unable to retrieve journal cache wait time defaulting to 30 seconds", e);
            return 30000L;
        }

    }

    public DetailedJournalReceiver getCurrentDetailedJournalReceiver(AS400 as400, JournalInfo journalLib)
            throws Exception {
        final JournalReceiver ji = getReceiver(as400, journalLib);
        return getOffset(as400, ji);
    }

    /**
     * Resolve the single journal shared by all included tables.
     *
     * <p>Tables may live in several libraries (schemas): each table's journal is resolved using
     * its own library, so a single connector can capture tables spread across multiple libraries
     * as long as they all journal to the <em>same</em> journal. The multi-journal case is rejected
     * because the offset model is single-journal.</p>
     *
     * @param schema the connector's default library, used only when {@code includes} is empty
     * @param includes the qualified table filters (each carries its own library)
     * @return the journal common to every included table
     * @throws IllegalStateException if the journal cannot be retrieved, or if the tables span more
     *         than one journal
     */
    public JournalInfo getJournal(AS400 as400, String schema, List<FileFilter> includes) throws IllegalStateException {
        return resolveJournal(as400, schema, includes, false).journalInfo();
    }

    /** The journal shared by the included tables, and the filters that resolved to it. */
    public record ResolvedJournal(JournalInfo journalInfo, List<FileFilter> includes) {
    }

    /**
     * Resolve the journal shared by all included tables, as {@link #getJournal(AS400, String, List)}, but
     * optionally tolerating tables that have no journal.
     *
     * @param skipUncapturable when true ({@code errors.tolerance=all}) a table whose journal cannot be
     *        retrieved is logged at ERROR level and left out of the returned filters instead of failing;
     *        the multi-journal case and an include list where nothing resolves stay fatal either way
     */
    public ResolvedJournal resolveJournal(AS400 as400, String schema, List<FileFilter> includes, boolean skipUncapturable)
            throws IllegalStateException {
        if (includes.isEmpty()) {
            return new ResolvedJournal(getJournal(as400, schema), includes);
        }
        final Set<JournalInfo> jis = new HashSet<>();
        final Set<String> libraries = new TreeSet<>();
        final List<FileFilter> journaled = new ArrayList<>();
        final Map<JournalInfo, List<String>> tablesByJournal = new HashMap<>();
        for (final FileFilter f : includes) {
            libraries.add(f.schema());
            try {
                final JournalInfo ji = getJournal(as400, f.schema(), f.table());
                jis.add(ji);
                tablesByJournal.computeIfAbsent(ji, k -> new ArrayList<>()).add(f.schema() + "." + f.table());
                journaled.add(f);
            }
            catch (final Exception e) {
                if (!skipUncapturable) {
                    throw new IllegalStateException(String.format(
                            "unable to retrieve journal details for %s.%s - set errors.tolerance=all to skip it and "
                                    + "capture the remaining tables",
                            f.schema(), f.table()), e);
                }
                log.error("errors.tolerance=all: dropping {}.{} from the journal filters, it has no journal - "
                        + "nothing will be captured for it", f.schema(), f.table(), e);
            }
        }
        if (jis.size() > 1) {
            log.debug("tables span more than one journal, full breakdown of tables by journal: {}", tablesByJournal);
            final String breakdown = tablesByJournal.entrySet().stream()
                    .map(e -> String.format("%s/%s: %d tables (%s%s)",
                            e.getKey().journalLibrary(), e.getKey().journalName(), e.getValue().size(),
                            String.join(", ", e.getValue().subList(0, Math.min(5, e.getValue().size()))),
                            e.getValue().size() > 5 ? ", ..." : ""))
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException(String.format(
                    "tables span more than one journal, which is not supported: a single connector must "
                            + "capture tables that all journal to the same journal. Libraries requested: %s; "
                            + "tables by journal : %s",
                    libraries, breakdown));
        }
        if (jis.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "none of the included tables has a journal, there is nothing to capture. Libraries requested: %s",
                    libraries));
        }
        return new ResolvedJournal(jis.iterator().next(), journaled);
    }

    public JournalInfo getJournal(AS400 as400, String schema, String table) throws Exception {
        final int rcvLen = 32768;
        final String filename = padRight(table.toUpperCase(), 10) + padRight(schema.toUpperCase(), 10);

        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, rcvLen), // 1
                                                                                  // Receiver
                                                                                  // variable
                                                                                  // (output)
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(rcvLen)), // 2
                                                                                                     // Length
                                                                                                     // of
                                                                                                     // receiver
                                                                                                     // variable
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, 20), // 3
                                                                              // Qualified
                                                                              // returned
                                                                              // file
                                                                              // name
                                                                              // (output)
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text8.toBytes("FILD0100")), // 4
                                                                                                          // Format
                                                                                                          // name
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text20.toBytes(filename)), // 5
                                                                                                         // Qualified
                                                                                                         // file
                                                                                                         // name
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text10.toBytes("*FIRST")), // 6
                                                                                                         // Record
                                                                                                         // format
                                                                                                         // name
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text1.toBytes("0")), // 7
                                                                                                   // Override
                                                                                                   // processing
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text10.toBytes("*LCL")), // 8
                                                                                                       // System
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text10.toBytes("*INT")), // 9
                                                                                                       // Format
                                                                                                       // type
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(0)), // 10
                                                                                                // Error
                                                                                                // Code
        };

        final ProgramCall pc = new ProgramCall();
        pc.setSystem(as400);
        final QSYSObjectPathName programName = new QSYSObjectPathName("QSYS", "QDBRTVFD", "PGM");
        pc.setProgram(programName.getPath(), parameters);
        final boolean success = pc.run();
        if (success) {
            final byte[] data = parameters[0].getOutputData();
            final int offsetJournalHeader = decodeInt(data, 378);
            int offsetJournalOrn = decodeInt(data, offsetJournalHeader + 378);
            offsetJournalOrn += offsetJournalHeader;

            final String journalName = decodeString(data, offsetJournalOrn, 10);
            final String journalLib = decodeString(data, offsetJournalOrn + 10, 10);

            boolean isCaching = isJournalCaching(as400, journalName, journalLib);

            return new JournalInfo(journalName, journalLib, isCaching);
        }
        else {
            final String msg = Arrays.asList(pc.getMessageList()).stream().map(AS400Message::getText).reduce("",
                    (a, s) -> a + s);
            throw new IllegalStateException(String.format("Journal not found for %s.%s error %s", schema, table, msg));
        }
    }

    private boolean isJournalCaching(AS400 as400, final String journalName, final String journalLib) throws Exception {
        String key = journalKey(journalName, journalLib);
        if (isJournalCaching.containsKey(key)) {
            return isJournalCaching.get(key);
        }
        boolean isCaching = this._getReceiver(as400, new JournalInfo(journalName, journalLib, false), b -> {
            String cache = decodeString(b, 195, 1);
            return "1".equals(cache);
        });
        isJournalCaching.put(key, isCaching);
        return isCaching;
    }

    public static class JournalRetrievalCriteria {
        private static final Integer ZERO_INT = Integer.valueOf(0);
        private static final Integer TWELVE_INT = Integer.valueOf(12);
        private static final Integer ONE_INT = Integer.valueOf(1);
        private final ArrayList<AS400DataType> structure = new ArrayList<>();
        private final ArrayList<Object> data = new ArrayList<>();

        public JournalRetrievalCriteria(As400TextFactory textFactory) {
            // first element is the number of variable length records
            structure.add(new AS400Bin4());
            structure.add(new AS400Bin4());
            structure.add(new AS400Bin4());
            structure.add(new AS400Bin4());
            structure.add(textFactory.text(1));
            data.add(ONE_INT); // number of records
            data.add(TWELVE_INT); // data length
            data.add(ONE_INT); // 1 = journal directory info
            data.add(ZERO_INT);
            data.add("");
        }

        public AS400DataType[] getStructure() {
            return structure.toArray(new AS400DataType[structure.size()]);
        }

        public Object[] getObject() {
            return data.toArray(new Object[0]);
        }
    }

    public JournalReceiver getReceiver(AS400 as400, JournalInfo journalLib) throws Exception {
        return _getReceiver(as400, journalLib, b -> {
            // Attached journal receiver name. The name of the journal
            // receiver that is
            // currently attached to this journal. This field will be
            // blank if no journal
            // receivers are attached.
            final String journalReceiver = decodeString(b, 200, 10);
            final String journalLibrary = decodeString(b, 210, 10);
            // journal cache, same field isJournalCaching reads: free to refresh with every poll's receiver
            observeCaching(journalLib, "1".equals(decodeString(b, 195, 1)));
            return new JournalReceiver(journalReceiver, journalLibrary);
        });
    }

    /**
     * uses the current attached journal information in the header
     *
     * @see https://www.ibm.com/support/knowledgecenter/ssw_ibm_i_74/apis/QJORJRNI.htm
     * @param as400
     * @param journalLibrary
     * @param journalFile
     * @return
     * @throws Exception
     */
    private <R> R _getReceiver(AS400 as400, JournalInfo journalLib, Function<byte[], R> f) throws Exception {
        final int rcvLen = 4096;
        final String jrnLib = padRight(journalLib.journalName(), 10) + padRight(journalLib.journalLibrary(), 10);
        final String format = "RJRN0200";
        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, rcvLen),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(rcvLen / 4096)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text20.toBytes(jrnLib)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text8.toBytes(format)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, emptyAs400Text),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(0)) };

        return callServiceProgram(as400, JOURNAL_SERVICE_LIB, "QjoRetrieveJournalInformation", parameters, f::apply);
    }

    private byte[] getReceiversForJournal(AS400 as400, JournalInfo journalLib, int bufSize) throws Exception {
        final String jrnLib = padRight(journalLib.journalName(), 10) + padRight(journalLib.journalLibrary(), 10);
        final String format = "RJRN0200";

        final JournalRetrievalCriteria criteria = new JournalRetrievalCriteria(textFactory);
        final byte[] toRetrieve = new AS400Structure(criteria.getStructure()).toBytes(criteria.getObject());
        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, bufSize),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(bufSize / 4096)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text20.toBytes(jrnLib)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text8.toBytes(format)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, toRetrieve),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(0)) };
        return callServiceProgram(as400, JOURNAL_SERVICE_LIB, "QjoRetrieveJournalInformation", parameters,
                (byte[] data) -> data);
    }

    /**
     * requests the list of receivers and orders them in attach time
     *
     * @see https://www.ibm.com/support/knowledgecenter/ssw_ibm_i_74/apis/QJORJRNI.htm
     * @param as400
     * @param journalLibrary
     * @param journalFile
     * @return
     * @throws Exception
     */
    public List<DetailedJournalReceiver> getReceivers(AS400 as400, JournalInfo journalLib) throws Exception {
        final int defaultSize = 32768; // 4k takes 31ms 32k takes 85ms must be a
                                       // multiple of 4k and 0 not allowed
        byte[] data = getReceiversForJournal(as400, journalLib, defaultSize);
        final int actualSizeRequired = decodeInt(data, 4) * 4096; // bytes
                                                                  // available -
                                                                  // value
                                                                  // returned
                                                                  // for
                                                                  // rjrn0200 is
                                                                  // 4k
        // pages
        if (actualSizeRequired > defaultSize) {
            data = getReceiversForJournal(as400, journalLib, actualSizeRequired + 4096); // allow
                                                                                         // for
                                                                                         // any
                                                                                         // growth
                                                                                         // since
                                                                                         // call
        }

        final Integer keyOffset = decodeInt(data, 8) + 4;
        final Integer totalKeys = decodeInt(data, keyOffset);

        final KeyDecoder keyDecoder = new KeyDecoder();

        final List<DetailedJournalReceiver> l = new ArrayList<>();

        for (int k = 0; k < totalKeys; k++) {
            final KeyHeader kheader = keyDecoder.decode(data, keyOffset + k * KEY_HEADER_LENGTH);
            if (kheader.getKey() == 1) {

                final ReceiverDecoder dec = new ReceiverDecoder(textFactory);
                for (int i = 0; i < kheader.getNumberOfEntries(); i++) {
                    final int kioffset = keyOffset + kheader.getOffset() + kheader.getLengthOfHeader()
                            + i * kheader.getLengthOfKeyInfo();

                    final JournalReceiverInfo r = dec.decode(data, kioffset);
                    final DetailedJournalReceiver details = getReceiverDetails(as400, r);

                    l.add(details);
                }
            }
        }
        cache.keepOnly(l);

        return DetailedJournalReceiver.lastJoined(l);
    }

    DetailedJournalReceiver getOffset(AS400 as400, JournalReceiver receiver) throws Exception {
        return getReceiverDetails(as400, new JournalReceiverInfo(receiver, null, null, Optional.empty()));
    }

    /**
     * @see https://www.ibm.com/support/knowledgecenter/ssw_ibm_i_74/apis/QJORRCVI.htm
     * @param as400
     * @param receiverInfo
     * @return
     * @throws Exception
     */
    public DetailedJournalReceiver getReceiverDetails(AS400 as400, JournalReceiverInfo receiverInfo) throws Exception {
        final int rcvLen = 32768;
        final String receiverNameLib = padRight(receiverInfo.receiver().name(), 10)
                + padRight(receiverInfo.receiver().library(), 10);
        if (cache.containsKey(receiverInfo)) {
            final DetailedJournalReceiver r = cache.getUpdatingStatus(receiverInfo);
            if (!r.isAttached()) { // don't use attached journal cache
                return r;
            }
        }
        final String format = "RRCV0100";
        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, rcvLen),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(rcvLen)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text20.toBytes(receiverNameLib)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Text8.toBytes(format)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, as400Bin4.toBytes(0)) };

        return callServiceProgram(as400, JOURNAL_SERVICE_LIB, "QjoRtvJrnReceiverInformation", parameters,
                (byte[] data) -> {
                    final String journalName = decodeString(data, 8, 10);
                    final String attachTimeStr = decodeString(data, 95, 13);
                    final String nextReceiverName = decodeString(data, 332, 10);
                    String nextReceiverLib = decodeString(data, 342, 10);
                    nextReceiverLib = (nextReceiverLib == null) ? receiverInfo.receiver().library() : nextReceiverLib;
                    final Long numberOfEntries = Long.valueOf(decodeString(data, 372, 20));
                    final Long maxEntryLength = Long.valueOf(decodeString(data, 392, 20));
                    final BigInteger firstSequence = decodeBigIntFromString(data, 412);
                    final BigInteger lastSequence = decodeBigIntFromString(data, 432);
                    final JournalStatus status = JournalStatus.valueOfString(decodeString(data, 88, 1));

                    if (!journalName.equals(receiverInfo.receiver().name())) {
                        final String msg = String.format("journal names don't match requested %s got %s",
                                receiverInfo.receiver().name(), journalName);
                        throw new Exception(msg);
                    }
                    final Date attachTime = ReceiverDecoder.toDate(attachTimeStr);
                    final JournalReceiverInfo updatedInfo = new JournalReceiverInfo(receiverInfo.receiver(), attachTime,
                            status, receiverInfo.chain());

                    final Optional<JournalReceiver> nextReceiver = (nextReceiverName == null) ? Optional.empty()
                            : Optional.of(new JournalReceiver(nextReceiverName, nextReceiverLib));

                    final DetailedJournalReceiver dr = new DetailedJournalReceiver(updatedInfo, firstSequence,
                            lastSequence, nextReceiver, maxEntryLength, numberOfEntries);

                    cache.put(dr);
                    return dr;
                });
    }

    /**
     *
     * @param <T>            return type of processor
     * @param as400
     * @param programLibrary
     * @param program
     * @param parameters     assumes first parameter is output
     * @param processor
     * @return output of processor
     * @throws Exception
     */
    public static <T> T callServiceProgramParams(AS400 as400, String programLibrary, String program,
                                                 ProgramParameter[] parameters, ProcessDataAllParamaterData<T> processor)
            throws Exception {
        final ServiceProgramCall spc = new ServiceProgramCall(as400);

        spc.getServerJob().setLoggingLevel(0);
        spc.setProgram(programLibrary, parameters);
        spc.setProcedureName(program);
        spc.setAlignOn16Bytes(true);
        spc.setReturnValueFormat(ServiceProgramCall.RETURN_INTEGER);
        final boolean success = spc.run();
        if (success) {
            return processor.process(parameters);
        }
        else {
            throw classifyServiceProgramFailure(programLibrary, program, spc.getMessageList());
        }
    }

    /**
     *
     * @param <T>            return type of processor
     * @param as400
     * @param programLibrary
     * @param program
     * @param parameters     assumes first parameter is output
     * @param processor
     * @return output of processor
     * @throws Exception
     */
    public static <T> T callServiceProgram(AS400 as400, String programLibrary, String program,
                                           ProgramParameter[] parameters, ProcessFirstParamaterData<T> processor)
            throws Exception {
        return callServiceProgramParams(as400, programLibrary, program, parameters,
                p -> processor.process(p[0].getOutputData()));
    }

    /**
     * Build the exception for a failed service program call, distinguishing a permanently missing
     * object (pruned receiver / library -> {@link JournalReceiverNotFoundException}) from any other,
     * potentially transient, failure (generic {@link Exception}).
     */
    static Exception classifyServiceProgramFailure(String programLibrary, String program, AS400Message[] messages) {
        final String msg = Arrays.stream(messages).map(AS400Message::getText).reduce("", (a, s) -> a + s);
        log.error(String.format("service program %s/%s call failed %s", programLibrary, program, msg));
        final Optional<String> notFoundId = Arrays.stream(messages)
                .map(AS400Message::getID)
                .filter(id -> id != null && RECEIVER_NOT_FOUND_MESSAGE_IDS.contains(id.toUpperCase()))
                .findFirst();
        if (notFoundId.isPresent()) {
            // Non-transient: the referenced object (pruned receiver / library) is gone for good.
            return new JournalReceiverNotFoundException(msg, notFoundId.get());
        }
        return new Exception(msg);
    }

    public Integer decodeInt(byte[] data, int offset) {
        final byte[] b = Arrays.copyOfRange(data, offset, offset + 4);
        return Integer.valueOf(as400Bin4.toInt(b));
    }

    public Long decodeLong(byte[] data, int offset) {
        final byte[] b = Arrays.copyOfRange(data, offset, offset + 8);
        return Long.valueOf(as400Bin8.toLong(b));
    }

    public String decodeString(byte[] data, int offset, int length) {
        final byte[] b = Arrays.copyOfRange(data, offset, offset + length);
        return StringHelpers.safeTrim(textFactory.decode(b, 0, length));
    }

    public static String padRight(String s, int n) {
        return String.format("%1$-" + n + "s", s);
    }

    public interface ProcessDataAllParamaterData<T> {
        T process(ProgramParameter[] parameters) throws Exception;
    }

    public interface ProcessFirstParamaterData<T> {
        T process(byte[] data) throws Exception;
    }

    public BigInteger decodeBigIntFromString(byte[] data, int offset) {
        final byte[] b = Arrays.copyOfRange(data, offset, offset + 20);
        final String s = (String) as400Text20.toObject(b);
        return new BigInteger(s);
    }
}
