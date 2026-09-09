/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.Job;
import com.ibm.as400.access.MessageFile;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.SecureAS400;
import com.ibm.as400.access.ServiceProgramCall;

import io.debezium.ibmi.db2.journal.data.types.Diagnostics;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalCriteria.JournalCode;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalCriteria.JournalEntryType;
import io.debezium.ibmi.db2.journal.retrieve.exception.FatalException;
import io.debezium.ibmi.db2.journal.retrieve.exception.InvalidJournalFilterException;
import io.debezium.ibmi.db2.journal.retrieve.exception.LostJournalException;
import io.debezium.ibmi.db2.journal.retrieve.exception.RetrieveJournalException;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeaderDecoder;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeaderDecoder;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.OffsetStatus;

/**
 * based on the work of Stanley Vong see
 * https://www.ibm.com/docs/en/i/7.5?topic=ssw_ibm_i_75/apis/QJORJRNE.html
 */
public class RetrieveJournal {
    private static final Logger log = LoggerFactory.getLogger(RetrieveJournal.class);

    private static final JournalCode[] REQUIRED_JOURNAL_CODES = new JournalCode[]{ JournalCode.D, JournalCode.R,
            JournalCode.C };
    private static final JournalEntryType[] REQURED_ENTRY_TYPES = new JournalEntryType[]{ JournalEntryType.PT,
            JournalEntryType.PX, JournalEntryType.UP, JournalEntryType.UB, JournalEntryType.DL, JournalEntryType.DR,
            JournalEntryType.CT, JournalEntryType.CG, JournalEntryType.SC, JournalEntryType.CM };
    private final FirstHeaderDecoder firstHeaderDecoder;
    private final EntryHeaderDecoder entryHeaderDecoder;
    private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyMMdd-hhmm");
    private final ReceiverPagination journalReceivers;
    private final ParameterListBuilder builder;
    private final PointerHandles pointerHandles;

    RetrieveConfig config;
    private byte[] outputData = null;
    private FirstHeader header = null;
    private EntryHeader entryHeader = null;
    private int offset = -1;
    private JournalProcessedPosition position;
    private MoreData moreData = null;
    private long totalTransferred = 0;
    private AtomicReference<Job> ibmiJob = new AtomicReference<>();

    public RetrieveJournal(RetrieveConfig config, JournalInfoRetrieval journalRetrieval) {
        this.config = config;
        firstHeaderDecoder = new FirstHeaderDecoder(config.textFactory());
        entryHeaderDecoder = new EntryHeaderDecoder(config.textFactory(), systemTimeZone(config));
        builder = new ParameterListBuilder(config.textFactory());
        pointerHandles = new PointerHandles(config.pointerHandleThreshold());
        journalReceivers = new ReceiverPagination(journalRetrieval, config.maxServerSideEntries(), config.journalInfo());

        builder.withJournal(config.journalInfo().journalName(), config.journalInfo().journalLibrary());
    }

    // the *DTS journal entry timestamp is decoded assuming GMT even though the IBM i TOD clock is set to the
    // system's local time; the correction needs the real system time zone, falling back to no correction if unreachable
    private static TimeZone systemTimeZone(RetrieveConfig config) {
        try {
            return config.as400().connection().getTimeZone();
        }
        catch (final Exception e) {
            log.error("failed to fetch AS400 system time zone, journal entry timestamps may be off by a fixed offset", e);
            return TimeZone.getTimeZone("GMT");
        }
    }

    /**
     * retrieves a block of journal data
     *
     * @param previousPosition
     * @return true if the journal was read successfully false if there was some
     *         problem reading the journal
     * @throws Exception
     *
     *                   CURAVLCHN - returns only available journals CURCHAIN will
     *                   work though journals that have happened but may no longer
     *                   be available if the journal is no longer available we need
     *                   to capture this and log an error as we may have missed data
     */
    public RetrievalState retrieveJournal(JournalProcessedPosition previousPosition) throws Exception {

        final Optional<PositionRange> continuationOpt = continuationRange(moreData, previousPosition);
        // one hop only: a range we declined, finished or failed on is never reused later
        moreData = null;
        if (continuationOpt.isPresent()) {
            final PositionRange continuation = continuationOpt.get();
            log.debug("continuing within the previous range {}", continuation);
            try {
                return retrieveJournal(previousPosition, continuation);
            }
            catch (LostJournalException e) {
                // a stale range resolves to CPF7053/CPF9801/CPF7054, all of which report as a lost journal and
                // would cost a re-snapshot: recalculate instead, so a bad guess can never be mistaken for data
                // loss. Only the speculative refresh at the start of a block is skipped, never the corrective
                // re-read of the receiver list that findRange does before declaring a position unresolvable
                // (issue #30). Nothing else is caught: cancelled jobs and connection failures also surface here
                // and retrying them fights the cancellation, while the streaming loop already recovers from
                // them by pausing and refetching with a recalculated range
                log.warn("failed to continue within the previous range {}, recalculating the range", continuation, e);
            }
        }

        final Optional<PositionRange> rangeOpt = journalReceivers.findRange(config.as400().connection(), previousPosition);
        if (rangeOpt.isPresent()) {
            // don't wrap in a RuntimeException, callers recover from the fatal journal exceptions
            return retrieveJournal(previousPosition, rangeOpt.get());
        }
        return RetrievalState.NotCalled;
    }

    /** where a call that ran out of buffer said to carry on from, and the end of the range it was given */
    record MoreData(JournalProcessedPosition continuation, JournalPosition end) {
    }

    /**
     * @return what to carry on from when the server left the range unfinished, otherwise null - the range was
     *         read to its end and where to go next has to be worked out from the journal again
     */
    static MoreData moreDataAfter(FirstHeader header, PositionRange range) {
        if (!header.hasFutureDataAvailable()) {
            return null;
        }
        // copy the continuation, the header is only kept until the next call replaces it
        return new MoreData(new JournalProcessedPosition(header.nextPosition()), range.end());
    }

    /**
     * When a call ends with MORE_DATA_NEW_OFFSET the server stopped filling the buffer before reaching the
     * end of the range we asked for, so we already know there is more data waiting and where it ends. Reusing
     * that range lets us carry on downloading without asking the server for the current journal head and the
     * receiver list again.
     *
     * <p>Only valid when we resume from exactly the continuation offset the server handed back, otherwise we
     * can't tell whether the position is still inside the range. {@code moreData} is cleared on every call and
     * only re-armed by a call that again ran out of buffer, so a range is reused at most once: anything
     * unexpected - a failure, or a position moved by recovery - recalculates it.</p>
     *
     * @return the remaining part of the previous range, or empty when the range has to be recalculated
     */
    static Optional<PositionRange> continuationRange(MoreData moreData, JournalProcessedPosition previousPosition) {
        if (moreData == null) {
            return Optional.empty();
        }
        if (!previousPosition.equals(moreData.continuation())) {
            log.debug("position {} is not the continuation offset {}, refreshing the range", previousPosition,
                    moreData.continuation());
            return Optional.empty();
        }
        final JournalPosition end = moreData.end();
        if (previousPosition.getReceiver().equals(end.receiver())
                && previousPosition.getOffset().compareTo(end.offset()) >= 0) {
            // nothing left in this range - offsets are only comparable within a receiver, when the continuation
            // is in an earlier receiver of the range there is by definition still data between it and the end
            return Optional.empty();
        }
        return Optional.of(new PositionRange(false, new JournalProcessedPosition(previousPosition), end));
    }

    public void cancelJob() {
        Job job = ibmiJob.get();
        if (job == null) {
            log.debug("No job to cancel");
            return;
        }
        AS400 killerAs400 = null;
        try {
            AS400 as400 = config.as400().connection();
            killerAs400 = (as400 instanceof SecureAS400) ? new SecureAS400(as400) : new AS400(as400);
            final Job killer = new Job(killerAs400, job.getName(), job.getUser(), job.getNumber());
            log.info("Killing job {}/{}/{}", job.getName(), job.getUser(), job.getNumber());
            killer.end(0);
        }
        catch (Throwable e) {
            log.error("Failed to cancel job name {} user {} number {}", job.getName(), job.getUser(), job.getNumber(), e);
        }
        finally {
            if (killerAs400 != null) {
                try {
                    killerAs400.disconnectAllServices();
                }
                catch (Exception e) {
                    log.warn("Failed to disconnect the job cancellation connection", e);
                }
            }
        }
    }

    public RetrievalState retrieveJournal(JournalProcessedPosition previousPosition, final PositionRange range)
            throws Exception {
        this.offset = -1;
        this.entryHeader = null;
        this.position = new JournalProcessedPosition(previousPosition);
        // only set again when this call comes back with more data left in the range, so anything going wrong
        // here means the next call recalculates the range
        this.moreData = null;

        // will return data for both first entry and last entry
        // but call fails if start == end
        if (range.startEqualsEnd()) {
            this.header = new FirstHeader(0, 0, 0, OffsetStatus.NOT_CALLED,
                    new JournalProcessedPosition(range.end(), Instant.EPOCH, true));

            log.debug("start equals end - range {}", range);
            return RetrievalState.NotCalled;
        }

        // TODO end could be optional for filtering or use same mechanism as non
        // filtering?
        final JournalProcessedPosition end = new JournalProcessedPosition(range.end(), Instant.EPOCH, true);

        final ServiceProgramCall spc = new ServiceProgramCall(config.as400().connection());
        spc.getServerJob().setLoggingLevel(0);
        builder.init();
        builder.withBufferLenth(config.journalBufferSize());
        builder.filterJournalEntryType(REQURED_ENTRY_TYPES);
        builder.filterJournalCodes(REQUIRED_JOURNAL_CODES);
        if (config.filtering() && !config.includeFiles().isEmpty()) {
            builder.withFileFilters(config.includeFiles());
        }
        builder.withRange(range);
        final ProgramParameter[] parameters = builder.build();

        spc.setProgram(JournalInfoRetrieval.JOURNAL_SERVICE_LIB, parameters);
        spc.setProcedureName("QjoRetrieveJournalEntries");
        spc.setAlignOn16Bytes(true);
        spc.setReturnValueFormat(ServiceProgramCall.RETURN_INTEGER);
        final Job serverJob = spc.getServerJob();
        ibmiJob.set(serverJob); // capture so we can asynchronously cancel it
        // handles belong to this job; if it is not the one that allocated the outstanding ones they
        // died with their own job and the budget starts again
        pointerHandles.observeJob(serverJob == null ? null
                : serverJob.getName() + '/' + serverJob.getUser() + '/' + serverJob.getNumber());
        boolean success;
        try {
            success = spc.run();
        }
        finally {
            ibmiJob.set(null); // job finished
        }
        if (success) {
            outputData = parameters[0].getOutputData();
            header = firstHeaderDecoder.decode(outputData, end);
            totalTransferred += header.totalBytes();
            log.debug("retrieve from {} to {} header {}", range.start(), range.end(), header);
            offset = -1;
            if (header.status() == OffsetStatus.MORE_DATA_NEW_OFFSET && header.offset() == 0) {
                log.error("buffer too small need to skip this entry {}", previousPosition);
                this.position.setPosition(header.nextPosition());
            }
            if (!hasData()) {
                this.position.setPosition(end);
            }
        }
        else {
            log.debug("retrieve from {} to {} status {}", range.start(), range.end(), success);
            return reThrowIfFatal(previousPosition, spc, end, builder);
        }
        moreData = moreDataAfter(header, range);
        if (moreData != null) {
            return RetrievalState.MoreDataAvailable;
        }
        return RetrievalState.Success;
    }

    private RetrievalState reThrowIfFatal(JournalProcessedPosition retrievePosition, final ServiceProgramCall spc,
                                          JournalProcessedPosition latestJournalPosition, final ParameterListBuilder builder)
            throws LostJournalException, FatalException {
        for (final AS400Message id : spc.getMessageList()) {
            final String idt = id.getID();
            if (idt == null) {
                log.error("Call failed position {} parameters {} no Id, message: {}", retrievePosition, builder, id.getText());
                continue;
            }
            switch (idt) {
                case "CPF7053": { // sequence number does not exist or break in receivers
                    throw new LostJournalException(String.format("Call failed %s position %s parameters %s failed to find sequence or break in receivers: %s",
                            idt, retrievePosition, builder, getFullAS400MessageText(id)));
                }
                case "CPF9801": { // specify invalid receiver
                    throw new LostJournalException(String.format("Call failed %s position %s parameters %s failed to find sequence or break in receivers: %s",
                            idt, retrievePosition, builder, getFullAS400MessageText(id)));
                }
                case "CPF7054": { // e.g. last < first or using offset that doesn't belong to journal
                    // the offset we hold is not in this journal, which is what a journal that was deleted and
                    // recreated under us looks like, so treat it as a lost journal rather than a bad offset
                    throw new LostJournalException(
                            String.format("Call failed position %s parameters %s failed to find offset or invalid offsets: %s",
                                    retrievePosition, builder, id.getText()));
                }
                case "CPF7060": { // object in filter doesn't exist, or was not journaled
                    throw new InvalidJournalFilterException(
                            String.format("Call failed position %s parameters %s object not found or not journaled: %s", retrievePosition, builder,
                                    getFullAS400MessageText(id)));
                }
                case "CPF7062": {
                    log.debug("Normal when filtering, call failed position {} parameters {} no data received: {}", retrievePosition, builder,
                            id.getText());
                    // if we're filtering we get no continuation offset just an error
                    header = new FirstHeader(0, 0, 0, OffsetStatus.NO_DATA, latestJournalPosition);
                    this.position.setPosition(latestJournalPosition);
                    return RetrievalState.Success;
                }
                default:
                    log.error("Call failed position {} parameters {} with error code {} message {}", retrievePosition, idt,
                            builder, getFullAS400MessageText(id));
            }
        }
        throw new RetrieveJournalException(String.format("Call failed position %s", retrievePosition));
    }

    boolean shouldLimitRange() {
        return config.filtering();
    }

    private String getFullAS400MessageText(AS400Message message) {
        try {
            message.load(MessageFile.RETURN_FORMATTING_CHARACTERS);
            return String.format("%s %s", message.getText(), message.getHelp());
        }
        catch (final Exception e) {
            return message.getText();
        }
    }

    /**
     * @return the current position or the next offset for fetching data when the
     *         end of data is reached
     */
    public JournalProcessedPosition getPosition() {
        return position;
    }

    public void setOutputData(byte[] b, FirstHeader header, JournalProcessedPosition position) {
        outputData = b;
        this.header = header;
        this.position = position;
    }

    // test without moving on
    public boolean hasData() {
        if (header.status() == OffsetStatus.NO_DATA) {
            return false;
        }
        if (offset < 0 && header.size() > 0) {
            return true;
        }
        return (offset > 0 && entryHeader.getNextEntryOffset() > 0);
    }

    public boolean futureDataAvailable() {
        return (header.hasFutureDataAvailable());
    }

    public boolean nextEntry() {
        if (header == null) {
            return false;
        }
        if (offset < 0) {
            if (header.size() > 0) {
                offset = header.offset();
                entryHeader = decodeEntryHeader(offset);
                if (alreadyProcessed(position, entryHeader)) {
                    log.debug("skipping already seen entry {} {}", position, entryHeader);
                    return nextEntry();
                }
                updatePosition(position, entryHeader);
                return true;
            }
            else {
                return false;
            }
        }
        else {
            final long nextOffset = entryHeader.getNextEntryOffset();
            if (nextOffset > 0) {
                offset += (int) nextOffset;
                entryHeader = decodeEntryHeader(offset);
                updatePosition(position, entryHeader);
                return true;
            }

            updateOffsetFromContinuation();
            return false;
        }
    }

    /**
     * Decodes an entry header and notes any pointer handle it came with. The entry's data is already
     * copied into our own buffer and the lob data behind the pointer is read back separately, so the
     * handle itself is of no further use - but the allocation behind it lives until the job ends, so
     * it is counted towards the budget that decides when to end it. See {@link PointerHandles}.
     */
    private EntryHeader decodeEntryHeader(int atOffset) {
        final EntryHeader decoded = entryHeaderDecoder.decode(outputData, atOffset);
        pointerHandles.record(decoded.getPointerHandle());
        return decoded;
    }

    /** The pointer handle budget, which the caller drains between polls by replacing the connection. */
    public PointerHandles pointerHandles() {
        return pointerHandles;
    }

    private void updateOffsetFromContinuation() {
        // after we hit the end use the continuation header for the next offset
        final JournalProcessedPosition nextOffset = header.nextPosition();
        log.debug("Setting continuation offset {}", nextOffset);
        position.setPosition(nextOffset);
    }

    static boolean alreadyProcessed(JournalProcessedPosition position, EntryHeader entryHeader) {
        return position.processed() && position.getOffset().equals(entryHeader.getSequenceNumber()) && (!entryHeader.hasReceiver() ||
                (entryHeader.getReceiverLibrary().equals(position.getReceiver().library()) && entryHeader.getReceiver().equals(position.getReceiver().name())));

    }

    private static void updatePosition(JournalProcessedPosition p, EntryHeader entryHeader) {
        if (entryHeader.hasReceiver()) {
            log.debug("offset with receiver {}", entryHeader.getReceiver());
            p.setJournalReceiver(entryHeader.getSequenceNumber(), entryHeader.getReceiver(),
                    entryHeader.getReceiverLibrary(), entryHeader.getTime(), true);
        }
        else {
            // this happens a lot
            log.debug("offset no receiver {}", entryHeader);
            p.setOffset(entryHeader.getSequenceNumber(), entryHeader.getTime(), true);
        }
    }

    public EntryHeader getEntryHeader() {
        return entryHeader;
    }

    public void dumpEntry() {
        final int start = offset + entryHeader.getEntrySpecificDataOffset();
        final long end = entryHeader.getNextEntryOffset();
        log.debug("total offset {} entry specific offset {} next offset {}", start, entryHeader.getEntrySpecificDataOffset(), end);
    }

    public int getOffset() {
        return offset;
    }

    /** The current entry's record image as hex, capped at {@code maxBytes}; "" without a current entry. */
    public String currentRecordImageHex(int maxBytes) {
        if (entryHeader == null || outputData == null) {
            return "";
        }
        final int start = offset + entryHeader.getEntrySpecificDataOffset() + JournalEntryDeocder.ENTRY_SPECIFIC_DATA_OFFSET;
        return Diagnostics.hex(outputData, start, entryHeader.getLength() - JournalEntryDeocder.ENTRY_SPECIFIC_DATA_OFFSET, maxBytes);
    }

    public <T> T decode(JournalEntryDeocder<T> decoder) throws Exception {
        // Diagnostics.dump(outputData, start);
        try {
            final T t = decoder.decode(entryHeader, outputData, offset);
            return t;
        }
        catch (final Exception e) {
            dumpEntryToFile(config.dumpFolder());
            throw e;
        }
    }

    public void dumpEntryToFile(File path) {
        File dumpFile = null;
        if (path != null) {
            boolean created = false;
            for (int i = 0; !created && i < 100; i++) {

                final String formattedDate = dateFormatter.format(new Date());
                final File f = new File(path, String.format("%s-%s", formattedDate, Integer.toString(i)));
                try {
                    created = f.createNewFile();
                    if (created) {
                        dumpFile = f;
                    }
                }
                catch (final IOException e) {
                    log.error("unable to dump to file", e);
                }
            }
            if (dumpFile != null) {
                try {
                    final int start = offset;
                    final int end = outputData.length;

                    final byte[] bdata = Arrays.copyOfRange(outputData, start, end);
                    Files.write(dumpFile.toPath(), bdata);

                    final File entryInfo = new File(dumpFile.getPath() + ".txt");

                    try (FileWriter fw = new FileWriter(entryInfo, true);
                            BufferedWriter bw = new BufferedWriter(fw);
                            PrintWriter out = new PrintWriter(bw)) {
                        out.println(entryHeader.toString());
                        out.print("dumped: ");
                        out.println(end - start);
                        out.print("total length: ");
                        out.println(outputData.length);
                    }
                }
                catch (final IOException e) {
                    log.error("failed to dump problematic data", e);
                }
            }
            else {
                log.error("failed to create a dump file");
            }
        }
    }

    public FirstHeader getFirstHeader() {
        return header;
    }

    public long getTotalTransferred() {
        return totalTransferred;
    }
}
