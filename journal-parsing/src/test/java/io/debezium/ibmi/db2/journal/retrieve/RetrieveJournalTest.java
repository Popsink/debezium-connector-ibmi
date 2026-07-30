/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.OffsetStatus;

class RetrieveJournalTest {
    private static final JournalReceiver RECEIVER = new JournalReceiver("receiver", "receiverLibrary");

    private JournalProcessedPosition firstPosition() {
        JournalProcessedPosition positionMatches = new JournalProcessedPosition(BigInteger.ONE, new JournalReceiver("receiver", "receiverLibrary"),
                Instant.ofEpochMilli(1l), true);
        return positionMatches;
    }

    @Test
    public void testAlreadyProcessedOffsetSequenceReceiverAndReceiverLibrary() {
        JournalProcessedPosition positionMatches = firstPosition();
        EntryHeader entryHeader = new EntryHeader(-1, -1, -1l, positionMatches.getOffset(), BigInteger.TWO,
                Instant.ofEpochMilli(2l), 'Z', "UB", "OBJECT", BigInteger.TEN, -1, -1,
                positionMatches.getReceiver().name(), positionMatches.getReceiver().library(), BigInteger.TWO);
        assertTrue(RetrieveJournal.alreadyProcessed(positionMatches, entryHeader), "compare sequence number, library and receiver match");

        JournalProcessedPosition positionIncorrectReceiver = new JournalProcessedPosition(BigInteger.ONE, new JournalReceiver("receiverDoesn'tMatch", "receiverLibrary"),
                Instant.now(), true);
        assertFalse(RetrieveJournal.alreadyProcessed(positionIncorrectReceiver, entryHeader), "compare where only receiver name doesn't match");

        JournalProcessedPosition positionIncorrectReceiverLibrary = new JournalProcessedPosition(BigInteger.ONE,
                new JournalReceiver("receiver", "receiverLibraryDoesn'tMatch"), Instant.now(), true);
        assertFalse(RetrieveJournal.alreadyProcessed(positionIncorrectReceiverLibrary, entryHeader), "compare where only receiver name doesn't match");

        JournalProcessedPosition positionIncorrectSequence = new JournalProcessedPosition(BigInteger.TWO, new JournalReceiver("receiver", "receiverLibrary"),
                Instant.now(), true);
        assertFalse(RetrieveJournal.alreadyProcessed(positionIncorrectSequence, entryHeader), "compare where only receiver name doesn't match");
    }

    @Test
    public void testAlreadyProcessedOffsetSequenceMissingReceiverAndReceiverLibrary() {
        JournalProcessedPosition positionMatches = firstPosition();
        EntryHeader entryHeader = new EntryHeader(-1, -1, -1l, positionMatches.getOffset(), BigInteger.TWO,
                Instant.ofEpochMilli(2l), 'Z', "UB", "OBJECT", BigInteger.TEN, -1, -1,
                positionMatches.getReceiver().name(), positionMatches.getReceiver().library(), BigInteger.TWO);
        assertTrue(RetrieveJournal.alreadyProcessed(positionMatches, entryHeader), "compare only sequence number, library and receiver empty");

        EntryHeader entryHeaderDifferentOffset = new EntryHeader(-1, -1, -1l, positionMatches.getOffset().add(BigInteger.ONE), BigInteger.TWO,
                Instant.ofEpochMilli(2l), 'Z', "UB", "OBJECT", BigInteger.TEN, -1, -1,
                "", "", BigInteger.TWO);
        assertFalse(RetrieveJournal.alreadyProcessed(positionMatches, entryHeaderDifferentOffset), "compare only sequence number, library and receiver empty");

    }

    @Test
    public void testAlreadyProcessedOffsetNotProcessed() {
        JournalProcessedPosition positionMatches = firstPosition();
        JournalProcessedPosition positionMatchesNotProcessed = firstPosition().setProcessed(false);

        EntryHeader entryHeader = new EntryHeader(-1, -1, -1l, positionMatchesNotProcessed.getOffset(), BigInteger.TWO,
                Instant.ofEpochMilli(2l), 'Z', "UB", "OBJECT", BigInteger.TEN, -1, -1,
                positionMatchesNotProcessed.getReceiver().name(), positionMatchesNotProcessed.getReceiver().library(), BigInteger.TWO);

        assertTrue(RetrieveJournal.alreadyProcessed(positionMatches, entryHeader), "compare sequence number, library and receiver match");
        assertFalse(RetrieveJournal.alreadyProcessed(positionMatchesNotProcessed, entryHeader), "compare sequence number, library and receiver match");

        EntryHeader entryHeaderEmptyReceiver = new EntryHeader(-1, -1, -1l, positionMatchesNotProcessed.getOffset(), BigInteger.TWO,
                Instant.ofEpochMilli(2l), 'Z', "UB", "OBJECT", BigInteger.TEN, -1, -1,
                "", "", BigInteger.TWO);

        assertTrue(RetrieveJournal.alreadyProcessed(positionMatches, entryHeaderEmptyReceiver), "compare sequence number, library and receiver match");
        assertFalse(RetrieveJournal.alreadyProcessed(positionMatchesNotProcessed, entryHeaderEmptyReceiver), "compare only sequence number, library and receiver empty");
    }

    private JournalProcessedPosition continuationPosition(long offset) {
        return new JournalProcessedPosition(BigInteger.valueOf(offset), RECEIVER, Instant.EPOCH, false);
    }

    private RetrieveJournal.MoreData moreData(long continuationOffset, long end) {
        return new RetrieveJournal.MoreData(continuationPosition(continuationOffset),
                new JournalPosition(BigInteger.valueOf(end), RECEIVER));
    }

    @Test
    public void testMoreDataOnlyArmedWhenTheRangeWasLeftUnfinished() {
        final JournalProcessedPosition continuation = continuationPosition(10);
        final PositionRange range = new PositionRange(false, continuationPosition(1),
                new JournalPosition(BigInteger.valueOf(100), RECEIVER));

        assertEquals(new RetrieveJournal.MoreData(continuation, range.end()),
                RetrieveJournal.moreDataAfter(new FirstHeader(100, 16, 1, OffsetStatus.MORE_DATA_NEW_OFFSET, continuation), range),
                "carry on from the continuation, up to the end of the range that was asked for");
        assertNull(RetrieveJournal.moreDataAfter(new FirstHeader(100, 16, 1, OffsetStatus.DATA, continuation), range),
                "the whole range was read, work out where to go next from the journal");
        assertNull(RetrieveJournal.moreDataAfter(new FirstHeader(0, 0, 0, OffsetStatus.NO_DATA, continuation), range),
                "no data in the range");
        assertNull(RetrieveJournal.moreDataAfter(new FirstHeader(0, 0, 0, OffsetStatus.NOT_CALLED, continuation), range),
                "the call was never made");
    }

    @Test
    public void testMoreDataCopiesTheContinuation() {
        final JournalProcessedPosition continuation = continuationPosition(10);
        final PositionRange range = new PositionRange(false, continuationPosition(1),
                new JournalPosition(BigInteger.valueOf(100), RECEIVER));
        final RetrieveJournal.MoreData moreData = RetrieveJournal
                .moreDataAfter(new FirstHeader(100, 16, 1, OffsetStatus.MORE_DATA_NEW_OFFSET, continuation), range);

        continuation.setOffset(BigInteger.valueOf(999), Instant.EPOCH, true);

        assertEquals(BigInteger.TEN, moreData.continuation().getOffset(),
                "the header this came from is not kept, so the continuation has to be a copy");
    }

    @Test
    public void testContinuationRangeReusesEndOfPreviousRange() {
        final JournalProcessedPosition position = continuationPosition(10);

        assertEquals(Optional.of(new PositionRange(false, position, new JournalPosition(BigInteger.valueOf(100), RECEIVER))),
                RetrieveJournal.continuationRange(moreData(10, 100), position),
                "carry on from the continuation offset up to the end we already know about");
        assertEquals(Optional.empty(), RetrieveJournal.continuationRange(null, position),
                "no unfinished range, e.g. cleared by a failed call");
    }

    @Test
    public void testContinuationRangeOnlyWhenResumingFromTheContinuationOffset() {
        assertEquals(Optional.empty(),
                RetrieveJournal.continuationRange(moreData(10, 100), continuationPosition(20)),
                "position moved somewhere else, e.g. reset by recovery");
        assertEquals(Optional.empty(),
                RetrieveJournal.continuationRange(moreData(10, 100),
                        new JournalProcessedPosition(BigInteger.TEN, RECEIVER, Instant.EPOCH, true)),
                "entry at the continuation offset was already processed so this is not the continuation");
        assertEquals(Optional.empty(),
                RetrieveJournal.continuationRange(moreData(10, 100),
                        new JournalProcessedPosition(BigInteger.TEN, new JournalReceiver("other", "receiverLibrary"), Instant.EPOCH, false)),
                "continuation offset is in a different receiver");
    }

    @Test
    public void testContinuationRangeNotUsedAtOrPastEndOfRange() {
        assertEquals(Optional.empty(),
                RetrieveJournal.continuationRange(moreData(10, 10), continuationPosition(10)),
                "nothing left in the range, the call would fail with start equal to end");
        assertEquals(Optional.empty(),
                RetrieveJournal.continuationRange(moreData(20, 10), continuationPosition(20)),
                "continuation past the end of the range would be an invalid range");
    }

    @Test
    public void testContinuationRangeAcrossReceivers() {
        // offsets restart with each receiver so a lower end offset in a later receiver is still ahead of us
        final JournalProcessedPosition position = continuationPosition(500);
        final JournalPosition endInLaterReceiver = new JournalPosition(BigInteger.TEN,
                new JournalReceiver("later", "receiverLibrary"));

        assertEquals(Optional.of(new PositionRange(false, position, endInLaterReceiver)),
                RetrieveJournal.continuationRange(new RetrieveJournal.MoreData(position, endInLaterReceiver), position),
                "keep going to the end of the range we already know about");
    }
}
