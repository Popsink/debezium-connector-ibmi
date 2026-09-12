/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.io.IOException;
import java.math.BigInteger;
import java.sql.SQLNonTransientConnectionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.SecureAS400;
import com.ibm.as400.access.SocketProperties;

import io.debezium.DebeziumException;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.connector.db2as400.metrics.As400StreamingChangeEventSourceMetrics;
import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.Connect;
import io.debezium.ibmi.db2.journal.retrieve.FileFilter;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfo;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfoRetrieval;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfoRetrieval.ResolvedJournal;
import io.debezium.ibmi.db2.journal.retrieve.JournalPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.PointerHandles;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.ibmi.db2.journal.retrieve.RetrieveConfig;
import io.debezium.ibmi.db2.journal.retrieve.RetrieveConfigBuilder;
import io.debezium.ibmi.db2.journal.retrieve.RetrieveJournal;
import io.debezium.ibmi.db2.journal.retrieve.exception.InvalidJournalRangeException;
import io.debezium.ibmi.db2.journal.retrieve.exception.JournalReceiverNotFoundException;
import io.debezium.ibmi.db2.journal.retrieve.exception.LostJournalException;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeader;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;

public class As400RpcConnection implements AutoCloseable, Connect<AS400, IOException> {
    private static Logger log = LoggerFactory.getLogger(As400RpcConnection.class);
    private final As400StreamingChangeEventSourceMetrics streamingMetrics;

    private final As400ConnectorConfig config;
    private JournalInfo journalInfo;
    private RetrieveJournal retrieveJournal;
    private AS400 as400;
    private static SocketProperties socketProperties = new SocketProperties();
    private final LogLimmiting periodic = new LogLimmiting(5 * 60 * 1000l);
    private final CatchUpTrend catchUpTrend = new CatchUpTrend();
    /** walking and dispatching the previous block, the part a prefetch hides */
    private long lastConsumedMs;
    private final JournalInfoRetrieval journalInfoRetrieval;
    private final As400TextFactory textFactory;

    private final boolean isSecure;

    public As400RpcConnection(As400ConnectorConfig config, As400StreamingChangeEventSourceMetrics streamingMetrics, List<FileFilter> includes, long cacheWait) {
        super();
        this.config = config;
        this.isSecure = config.getJdbcConfig().getBoolean("secure", config.isSecure());
        this.streamingMetrics = streamingMetrics;
        System.setProperty("com.ibm.as400.access.AS400.guiAvailable", "False");
        // every AS400Text used to encode API parameters and decode journal data is built against
        // this, so the CCSID follows the remote system instead of the connector's own locale
        this.textFactory = createTextFactory();
        this.journalInfoRetrieval = new JournalInfoRetrieval(textFactory, cacheWait, config.cacheAdditionalDelay(), config.getPollInterval().toMillis());
        try {
            final ResolvedJournal resolved = journalInfoRetrieval.resolveJournal(connection(), config.getSchema(),
                    includes, config.skipUncapturableTables());
            journalInfo = resolved.journalInfo();

            boolean transactionMgt = config.isTransactionMgmtEnabled();

            final RetrieveConfig rconfig = new RetrieveConfigBuilder().withAs400(this)
                    .withTextFactory(textFactory)
                    .withJournalBufferSize(config.getJournalBufferSize())
                    .withJournalInfo(journalInfo)
                    .withMaxServerSideEntries(config.getMaxServerSideEntries())
                    .withServerFiltering(!transactionMgt)
                    .withIncludeFiles(resolved.includes()).withDumpFolder(config.diagnosticsFolder())
                    .withPointerHandleThreshold(config.getPointerHandleThreshold())
                    .withPrefetch(config.isJournalPrefetchEnabled())
                    .build();
            retrieveJournal = new RetrieveJournal(rconfig, journalInfoRetrieval);
        }
        catch (final IOException e) {
            log.error("Failed to fetch library", e);
        }
    }

    private As400TextFactory createTextFactory() {
        try {
            return As400TextFactory.forSystem(connection());
        }
        catch (final IOException e) {
            log.error("unable to reach the system to read its ccsid, character conversion will fall back "
                    + "to guessing one from the local locale", e);
            return As400TextFactory.localeDefault();
        }
    }

    @Override
    public void close() {
        if (as400 != null) {
            log.info("Disconnecting");
            if (retrieveJournal != null) {
                // only meaningful while a retrieve is in flight; between polls there is nothing to cancel
                retrieveJournal.cancelJob();
            }
        }
        dropConnection();
    }

    /**
     * Gives up the connection object. {@link #connection()} builds a fresh one the next time it is
     * asked, which is what lands us on a different host server job - see
     * {@link #freePointerHandlesIfDue()}.
     */
    private void dropConnection() {
        try {
            if (as400 != null) {
                this.as400.disconnectAllServices();
            }
        }
        catch (final Exception e) {
            log.debug("Problem closing connection", e);
        }
        this.as400 = null;
    }

    public boolean isValid() {
        return (as400 != null && as400.isConnectionAlive(AS400.COMMAND));

    }

    /**
     * Availability of a stored journal position on the server.
     */
    public enum PositionAvailability {
        /** The receiver referenced by the stored position still exists. */
        AVAILABLE,
        /** No position is stored yet (fresh start / blank offset); streaming begins at the earliest receiver. */
        NOT_SET,
        /** The receiver has been pruned/rotated off the server; the position can never be resolved again. */
        PRUNED
    }

    /**
     * Classify a stored offset without collapsing every failure mode into a single boolean.
     * A pruned receiver ({@link PositionAvailability#PRUNED}) is a permanent condition, whereas a
     * transient RPC/connection failure is rethrown so the caller retries instead of wrongly treating
     * the position as lost.
     *
     * @throws DebeziumException if the position could not be validated for a transient reason
     */
    public PositionAvailability checkLogPosition(OffsetContext offsetContext) {
        return checkLogPosition(journalInfoRetrieval, as400, offsetContext);
    }

    static PositionAvailability checkLogPosition(JournalInfoRetrieval journalInfoRetrieval, AS400 as400, OffsetContext offsetContext) {
        if (!(offsetContext instanceof As400OffsetContext offset) || !offset.isPositionSet()) {
            return PositionAvailability.NOT_SET;
        }
        try {
            JournalReceiverInfo receiver = new JournalReceiverInfo(offset.getPosition().getReceiver(), null, null, Optional.empty());
            DetailedJournalReceiver dr = journalInfoRetrieval.getReceiverDetails(as400, receiver);
            return dr != null ? PositionAvailability.AVAILABLE : PositionAvailability.PRUNED;
        }
        catch (JournalReceiverNotFoundException e) {
            // Non-transient: the receiver has been pruned on the source and will never come back.
            log.warn("stored journal position {} points at a receiver that no longer exists on the server ({})", offsetContext, e.getMessageId());
            return PositionAvailability.PRUNED;
        }
        catch (Exception e) {
            // Transient (connection/RPC): do not misreport as lost, let the caller retry.
            throw new DebeziumException("transient failure while validating stored journal position " + offsetContext, e);
        }
    }

    /**
     * Whether the position streaming holds can still be read: its receiver is in the journal's live receiver
     * list and its offset falls inside that receiver's range.
     *
     * <p>This is the check that has to pass before a failed call is allowed to reset the offset. CPF7053,
     * CPF9801 and CPF7054 all report as a position the server would not read, but only one of them means the
     * receiver is gone; the others are a range we resolved badly or a stale one, and resetting for those
     * re-reads the whole retained journal to recover nothing (issue #79).</p>
     *
     * <p>A failure to read the list is not an answer, and the destructive reading is the one that resets, so
     * it reports the position as still available: the caller then retries, which is what a transient RPC
     * failure needs anyway, and the retry limit still bounds it.</p>
     */
    public boolean isPositionStillAvailable(JournalProcessedPosition position) {
        try {
            return isPositionStillAvailable(journalInfoRetrieval, connection(), journalInfo, position);
        }
        catch (final IOException e) {
            log.warn("no connection to check whether position {} is still available, assuming it is rather than "
                    + "resetting the offset on a failure to look", position, e);
            return true;
        }
    }

    static boolean isPositionStillAvailable(JournalInfoRetrieval journalInfoRetrieval, AS400 as400, JournalInfo journalInfo,
                                            JournalProcessedPosition position) {
        if (position == null || !position.isOffsetSet() || position.getReceiver() == null) {
            return false;
        }
        try {
            final List<DetailedJournalReceiver> receivers = journalInfoRetrieval.getReceivers(as400, journalInfo);
            for (final DetailedJournalReceiver receiver : receivers) {
                if (receiver.isSameReceiver(position)) {
                    final boolean withinRange = position.getOffset().compareTo(receiver.start()) >= 0
                            && position.getOffset().compareTo(receiver.end()) <= 0;
                    if (!withinRange) {
                        log.warn("position {} names a receiver that is still on the server but its offset is outside "
                                + "the receiver's range {}-{}", position, receiver.start(), receiver.end());
                    }
                    return withinRange;
                }
            }
            log.warn("position {} names a receiver that is no longer in the journal's receiver chain {}", position, receivers);
            return false;
        }
        catch (final Exception e) {
            log.warn("could not read the receiver list to check whether position {} is still available, assuming it is "
                    + "rather than resetting the offset on a failure to look", position, e);
            return true;
        }
    }

    public boolean validateLogPosition(Partition partition, OffsetContext offsetContext, CommonConnectorConfig config) {
        // AVAILABLE and NOT_SET are both valid starting points; only a pruned receiver is unavailable.
        return checkLogPosition(offsetContext) != PositionAvailability.PRUNED;
    }

    @Override
    public AS400 connection() throws IOException {
        if (as400 == null || !as400.isConnectionAlive(AS400.COMMAND)) {
            log.info("create new as400 connection");
            try {
                // need to both create a new object and connect
                close();
                if (isSecure) {
                    this.as400 = new SecureAS400(config.getHostname(), config.getUser(),
                            config.getPassword().toCharArray());
                }
                else {
                    this.as400 = new AS400(config.getHostname(), config.getUser(), config.getPassword().toCharArray());
                }
                socketProperties.setSoTimeout(config.getSocketTimeout());
                as400.setSocketProperties(socketProperties);
                as400.connectService(AS400.COMMAND);
            }
            catch (final Exception e) {
                log.error("Failed to reconnect", e);
                throw new IOException("Failed to reconnect", e);
            }
        }
        return as400;
    }

    /** Empty when the journal could not be resolved at startup. */
    public Optional<JournalInfo> getJournalInfo() {
        return Optional.ofNullable(journalInfo);
    }

    public JournalPosition getCurrentPosition() throws RpcException {
        try {
            final JournalPosition position = journalInfoRetrieval.getCurrentPosition(connection(), journalInfo);

            return new JournalPosition(position);
        }
        catch (final Exception e) {
            throw new RpcException("Failed to find offset", e);
        }
    }

    public RetrievalState getJournalEntries(ChangeEventSourceContext context, As400OffsetContext offsetCtx, BlockingReceiverConsumer consumer, WatchDog watchDog)
            throws Exception {
        final JournalProcessedPosition position = offsetCtx.getPosition();
        try {

            RetrievalState state = retrieveJournal.retrieveJournal(position);

            logOffsets(position, state);

            watchDog.alive();

            if (state.hasData()) {
                final long started = System.nanoTime();
                // Also break out when an ad-hoc blocking snapshot has been requested (context.isPaused()).
                // Over a large shared journal a single block can hold a huge run of entries that are mostly
                // filtered out; draining it fully keeps refreshing the watchdog (alive() below) yet never
                // returns to the caller's pause handshake, wedging the connector while it still looks live
                // (issue #27). Leaving the loop early lets the caller acknowledge the pause after one entry;
                // the position persisted below makes the next retrieval resume from here.
                while (context.isRunning() && !context.isPaused() && retrieveJournal.nextEntry()) {
                    watchDog.alive();
                    final EntryHeader eheader = retrieveJournal.getEntryHeader();
                    final BigInteger processingOffset = eheader.getSequenceNumber();

                    consumer.accept(processingOffset, retrieveJournal, eheader);
                    // while processing journal entries getPosistion is the current position
                    position.setPosition(retrieveJournal.getPosition());
                }

                // note that getPosition returns the current position or the next continuation offset after the current block
                offsetCtx.setPosition(retrieveJournal.getPosition());
                lastConsumedMs = (System.nanoTime() - started) / 1_000_000;
            }
            // between polls, with the batch dispatched and the position committed: the only safe moment
            // to swap the connection, which a retrieve in flight would be using
            freePointerHandlesIfDue();
            return state;
        }
        catch (LostJournalException e) {
            // this is bad, we've probably lost data; the caller applies the configured recovery strategy
            logLostJournal(position);
            throw e;
        }
        catch (InvalidJournalRangeException e) {
            // not data loss: the server refused the range we asked for. The chain is what the range was
            // resolved against, so it is the first thing needed to work out why (issue #79)
            log.warn("the server refused the journal range for position {}, receivers {}", position, receiversForLogging());
            throw e;
        }
    }

    /**
     * Frees the pointer handles the retrieves have accumulated, by replacing the connection so that the
     * next retrieve runs in a different host server job.
     *
     * <p>
     * Entries of a table with a LOB column each come back owning an allocation that is only released
     * when the handle is deleted or the job ends. Deleting them individually costs a round trip an
     * entry - 31 ms measured over a wide area link, so 31 seconds for a thousand entry buffer, paid
     * even when the LOB data is never read. Landing on a new job frees all of them at once, for about
     * 1.1 seconds including authentication - 0.02 ms an entry at the default threshold.
     * </p>
     *
     * <p>
     * {@link #connection()} re-establishes everything transparently; only the journal retrieve API's
     * own job-scoped state is affected, and it keeps none across calls.
     * </p>
     */
    private void freePointerHandlesIfDue() {
        final PointerHandles handles = retrieveJournal.pointerHandles();
        if (!handles.shouldCycle()) {
            return;
        }
        if (retrieveJournal.prefetchInFlight()) {
            // no new prefetch starts while the budget is spent, so the next poll can replace it
            log.debug("deferring the replacement of the connection, a journal retrieve is in flight");
            return;
        }
        try {
            log.info("freeing {} outstanding journal pointer handles by replacing the connection",
                    handles.outstanding());
            // dropping the AS400 object, not just disconnecting its service. QZRCSRVS are prestart
            // jobs: disconnecting returns one to a pool and the same object reconnects straight back
            // into it, handles intact, where a new object lands on a different job with a fresh handle
            // space. connection() builds one the next time it is asked.
            dropConnection();
            // the budget clears itself once a retrieve reports a different job, so a recycle that did
            // not take effect shows up as handles that keep accumulating rather than a count silently
            // zeroed on an assumption
        }
        catch (final Exception e) {
            // not fatal: the handles stay outstanding and the next poll tries again. It only becomes a
            // problem if it keeps failing all the way to the API's own maximum.
            log.warn("could not replace the connection to free {} pointer handles", handles.outstanding(), e);
        }
    }

    private void logLostJournal(JournalProcessedPosition position) {
        try {
            final List<DetailedJournalReceiver> receivers = journalInfoRetrieval.getReceivers(connection(), journalInfo);
            log.error("Failed to fetch journal entries '{}'", Map.of("position", position, "receivers", receivers));
        }
        catch (final Exception e) {
            log.error("Failed to fetch journal entries for position {}, receiver list unavailable", position, e);
        }
    }

    /** The receiver chain, or why it could not be read; only for log messages. */
    private Object receiversForLogging() {
        try {
            return journalInfoRetrieval.getReceivers(connection(), journalInfo);
        }
        catch (final Exception e) {
            return "unavailable: " + e;
        }
    }

    private void logOffsets(JournalProcessedPosition position, RetrievalState state) throws IOException, Exception {
        if (periodic.shouldLogRateLimted("offsets")) {
            // moves almost no data: the cost of a round trip
            final long smallCallStarted = System.nanoTime();
            final JournalPosition currentReceiver = getCurrentPosition();
            final long smallCallMs = (System.nanoTime() - smallCallStarted) / 1_000_000;
            final BigInteger behind = currentReceiver.getOffset().subtract(position.getOffset());
            streamingMetrics.setJournalOffset(currentReceiver.getOffset());
            streamingMetrics.setJournalBehind(behind);
            streamingMetrics.setLastProcessedMs(position.getTimeOfLastProcessed().toEpochMilli());
            final boolean bufferFull = state == RetrievalState.MoreDataAvailable;
            final int growthSamples = catchUpTrend.record(behind, bufferFull);
            streamingMetrics.setJournalBehindGrowthSamples(growthSamples);
            final FirstHeader header = retrieveJournal.getFirstHeader();
            log.info("Current position diagnostics last call {}, header {}, behind {}, current receiver {}, "
                    + "last block rpc {}ms waited {}ms consumed {}ms, small call {}ms, {}, lag growth samples {}", state,
                    header, behind, currentReceiver, retrieveJournal.lastRpcMs(), retrieveJournal.lastWaitMs(), lastConsumedMs,
                    smallCallMs, describeLink(header == null ? 0 : header.totalBytes(), retrieveJournal.lastRpcMs(), smallCallMs),
                    growthSamples);
            if (growthSamples >= CatchUpTrend.WARN_AFTER) {
                log.warn("CANNOT CATCH UP: lag grew for {} consecutive samples (now {} behind) with a full {} byte buffer "
                        + "every call; the journal grows faster than this connection reads it and the position will fall out "
                        + "of the retained receivers (issue #30). Check the link rate above, raise buffer.size with "
                        + "max.journal.timeout, or retain receivers for longer.", growthSamples, behind, config.getJournalBufferSize());
            }
        }
    }

    /**
     * Rate the transfer sustained, net of one round trip, and the bytes that fit in a round trip at that
     * rate. A low rate with tens of KB in flight over a long round trip is a TCP window (an IBM i ships
     * with 64 KiB, {@code CHGTCPA TCPSNDBUF}), which no buffer size or prefetch gets past.
     */
    static String describeLink(long blockBytes, long blockMs, long roundTripMs) {
        final long transferMs = blockMs - roundTripMs;
        if (blockBytes <= 0 || transferMs <= 0) {
            return "link rate n/a";
        }
        final long bytesPerSecond = blockBytes * 1000 / transferMs;
        final long inFlight = bytesPerSecond * roundTripMs / 1000;
        return String.format("link rate %d KB/s, %d KB in flight per round trip", bytesPerSecond / 1024, inFlight / 1024);
    }

    public interface BlockingReceiverConsumer {
        void accept(BigInteger offset, RetrieveJournal r, EntryHeader eheader) throws RpcException, InterruptedException, IOException, SQLNonTransientConnectionException;
    }

    public interface BlockingNoDataConsumer {
        void accept() throws InterruptedException;
    }

    public static class RpcException extends Exception {
        public RpcException(String message, Throwable cause) {
            super(message, cause);
        }

        public RpcException(String message) {
            super(message);
        }
    }

    private static class LogLimmiting {
        private final Map<String, Long> lastLogged = new HashMap<>();
        private final long rate;

        LogLimmiting(long rate) {
            this.rate = rate;
        }

        public boolean shouldLogRateLimted(String type) {
            if (lastLogged.containsKey(type)) {
                if (System.currentTimeMillis() > rate + lastLogged.get(type)) {
                    lastLogged.put(type, System.currentTimeMillis());
                    return true;
                }
            }
            else {
                lastLogged.put(type, System.currentTimeMillis());
                return true;
            }
            return false;
        }
    }
}
