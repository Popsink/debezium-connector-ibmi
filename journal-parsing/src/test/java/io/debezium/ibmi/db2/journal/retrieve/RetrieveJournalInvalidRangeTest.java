/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.retrieve.exception.InvalidJournalRangeException;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;

/**
 * CPF7054 - the server refusing the range - is a range we resolved badly, not a pruned receiver: reporting
 * it to the streaming loop as a lost journal is what reset production offsets to the earliest retained
 * receiver (issue #79). A range this class resolved itself is therefore recalculated on the next poll
 * rather than raised, up to the point where the rejection is clearly not the transient disagreement between
 * the receiver list and the delayed journal head.
 */
class RetrieveJournalInvalidRangeTest {

    private static final JournalReceiver RECEIVER = new JournalReceiver("receiver", "receiverLibrary");

    private final JournalInfoRetrieval journalInfoRetrieval = mock(JournalInfoRetrieval.class);
    private int calls = 0;

    private final DetailedJournalReceiver head = new DetailedJournalReceiver(
            new JournalReceiverInfo(RECEIVER, new Date(1), JournalStatus.Attached, Optional.of(1)),
            BigInteger.ONE, BigInteger.valueOf(100), Optional.empty(), 1, 1);

    @BeforeEach
    void journalHasOneReceiverWithEntriesToRead() throws Exception {
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(head));
        // a live read each time, as the real one is
        when(journalInfoRetrieval.getReceivers(any(), any())).thenAnswer(invocation -> new ArrayList<>(List.of(head)));
    }

    /** Every call is refused by the server, as an inverted range is. */
    private RetrieveJournal retrieveJournal() {
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(() -> mock(AS400.class))
                .withJournalInfo(new JournalInfo("JRN", "LIB", false)).withPrefetch(false)
                .build();
        return new RetrieveJournal(config, journalInfoRetrieval, (as400, from, range) -> {
            calls++;
            throw new InvalidJournalRangeException("Call failed position " + from + " failed to find offset or invalid offsets: CPF7054");
        });
    }

    private static JournalProcessedPosition position() {
        return new JournalProcessedPosition(BigInteger.ONE, RECEIVER, Instant.EPOCH, true);
    }

    @Test
    void aRefusedRangeIsRecalculatedRatherThanRaisedAsALostJournal() throws Exception {
        final RetrieveJournal rj = retrieveJournal();

        assertEquals(RetrievalState.NotCalled, rj.retrieveJournal(position()),
                "the range this class resolved was refused: nothing was read, and nothing is reported as lost");
        assertEquals(1, calls, "the call was made and failed");
        assertEquals(position(), rj.getPosition(), "the position is left exactly where it was");
    }

    @Test
    void aRefusalThatKeepsHappeningReachesTheCaller() throws Exception {
        final RetrieveJournal rj = retrieveJournal();

        // a transient inversion clears within the delayed head's window, so the first few are absorbed
        for (int i = 0; i < 4; i++) {
            assertEquals(RetrievalState.NotCalled, rj.retrieveJournal(position()));
        }

        // this one is not transient: only the caller can tell an offset that no longer belongs to the journal
        // from a range that was resolved badly, by looking the position up in the receiver list
        assertThrows(InvalidJournalRangeException.class, () -> rj.retrieveJournal(position()));
        assertEquals(5, calls);
    }

    @Test
    void aSuccessfulPollForgetsThePreviousRefusals() throws Exception {
        // four refusals, a poll that reads its range, then four more: the delayed head caught up in between,
        // so these are two short transients rather than one refusal that is not going away
        final List<Boolean> script = Arrays.asList(false, false, false, false, true,
                false, false, false, false);
        final RetrieveJournal rj = new RetrieveJournal(
                new RetrieveConfigBuilder().withAs400(() -> mock(AS400.class))
                        .withJournalInfo(new JournalInfo("JRN", "LIB", false)).withPrefetch(false).build(),
                journalInfoRetrieval, new FlakyFetcher(script));

        for (int i = 0; i < script.size(); i++) {
            final int poll = i;
            assertDoesNotThrow(() -> rj.retrieveJournal(position()),
                    "poll " + poll + " is part of a transient that a good poll already broke");
        }
    }

    /** Refuses or answers according to a script, so a refusal followed by a good poll can be played out. */
    private static final class FlakyFetcher implements RetrieveJournal.BlockFetcher {
        private final List<Boolean> succeeds;
        private int call = 0;

        FlakyFetcher(List<Boolean> succeeds) {
            this.succeeds = succeeds;
        }

        @Override
        public RetrieveJournal.Block fetch(AS400 as400, JournalProcessedPosition from, PositionRange range) throws Exception {
            final boolean ok = succeeds.get(Math.min(call++, succeeds.size() - 1));
            if (!ok) {
                throw new InvalidJournalRangeException("CPF7054 invalid offsets");
            }
            return new RetrieveJournal.Block(new byte[0],
                    new io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeader(0, 0, 0,
                            io.debezium.ibmi.db2.journal.retrieve.rjne0200.OffsetStatus.NO_DATA,
                            new JournalProcessedPosition(range.end(), Instant.EPOCH, true)),
                    new JournalProcessedPosition(range.end(), Instant.EPOCH, true), range, null);
        }
    }
}
