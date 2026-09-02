/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.retrieve.RetrieveJournal.Block;
import io.debezium.ibmi.db2.journal.retrieve.exception.LostJournalException;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.OffsetStatus;

/**
 * When a call runs out of buffer the rest of the range is fetched in the background and the next
 * {@link RetrieveJournal#retrieveJournal(JournalProcessedPosition)} takes that block (issue #68). The
 * real call is stood in for; what is checked is when a prefetch starts, is used, is thrown away, and
 * that it never overlaps with a foreground call.
 */
@Timeout(10)
class RetrieveJournalPrefetchTest {
    private static final JournalReceiver RECEIVER = new JournalReceiver("receiver", "receiverLibrary");
    private static final JournalPosition END = new JournalPosition(BigInteger.valueOf(100), RECEIVER);

    private final JournalInfoRetrieval journalInfoRetrieval = mock(JournalInfoRetrieval.class);
    private final FakeFetcher fetcher = new FakeFetcher();

    @BeforeEach
    void nothingToRecalculateFrom() throws Exception {
        // an empty journal head makes a recalculated range visible without a call being made
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.empty());
    }

    private RetrieveJournal retrieveJournal(boolean prefetch) {
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(() -> mock(AS400.class))
                .withJournalInfo(new JournalInfo("JRN", "LIB", false)).withPrefetch(prefetch).withPointerHandleThreshold(1L)
                .build();
        return new RetrieveJournal(config, journalInfoRetrieval, fetcher);
    }

    private static JournalProcessedPosition position(long offset) {
        return new JournalProcessedPosition(BigInteger.valueOf(offset), RECEIVER, Instant.EPOCH, false);
    }

    private static PositionRange range(long from) {
        return new PositionRange(false, position(from), END);
    }

    /** the buffer filled up before the range was read; the rest starts at {@code next} */
    private static FirstHeader bufferFull(long next) {
        return new FirstHeader(1000, 64, 10, OffsetStatus.MORE_DATA_NEW_OFFSET, position(next));
    }

    /** the whole range fitted */
    private static FirstHeader rangeRead() {
        return new FirstHeader(1000, 64, 10, OffsetStatus.DATA, new JournalProcessedPosition(END, Instant.EPOCH, true));
    }

    @Test
    void theRestOfTheRangeIsFetchedInTheBackgroundAndUsedNext() throws Exception {
        final RetrieveJournal rj = retrieveJournal(true);
        final CountDownLatch gate = new CountDownLatch(1);
        fetcher.answer(bufferFull(10));
        fetcher.answerAfter(gate, rangeRead());

        assertEquals(RetrievalState.MoreDataAvailable, rj.retrieveJournal(position(1), range(1)));
        assertTrue(fetcher.awaitCalls(2), "the call for the rest is made before anyone asks for it");
        assertTrue(rj.prefetchInFlight(), "still running until the gate opens");
        assertEquals(range(10), fetcher.ranges.get(1), "from the continuation to the end of the range that was asked for");

        gate.countDown();
        assertEquals(RetrievalState.Success, rj.retrieveJournal(position(10)));
        assertFalse(rj.prefetchInFlight());
        assertEquals(2, fetcher.ranges.size(), "the block already fetched is used, no third call");
        verifyNoInteractions(journalInfoRetrieval);
    }

    @Test
    void aPrefetchedBlockForSomewhereElseIsThrownAway() throws Exception {
        final RetrieveJournal rj = retrieveJournal(true);
        fetcher.answer(bufferFull(10));
        fetcher.answer(rangeRead());

        rj.retrieveJournal(position(1), range(1));
        assertTrue(fetcher.awaitCalls(2));

        // e.g. the position was reset by recovery, or the block was left part way for a blocking snapshot
        assertEquals(RetrievalState.NotCalled, rj.retrieveJournal(position(20)),
                "the range is worked out from the journal again, and there is nothing there");
        verify(journalInfoRetrieval).getDelayedDetailedJournalReceiver(any(), any());
        assertEquals(2, fetcher.ranges.size(), "the discarded block did not cost a call, the recalculation found nothing to fetch");
    }

    @Test
    void aPrefetchThatLostTheJournalRecalculatesTheRangeInsteadOfFailing() throws Exception {
        final RetrieveJournal rj = retrieveJournal(true);
        fetcher.answer(bufferFull(10));
        fetcher.failWith(new LostJournalException("CPF7053 stale range"));

        rj.retrieveJournal(position(1), range(1));
        assertTrue(fetcher.awaitCalls(2));

        // a stale range must never be mistaken for data loss: same policy as the synchronous continuation
        assertEquals(RetrievalState.NotCalled, rj.retrieveJournal(position(10)));
        verify(journalInfoRetrieval).getDelayedDetailedJournalReceiver(any(), any());
    }

    @Test
    void anyOtherPrefetchFailureReachesTheCallerAsIs() throws Exception {
        final RetrieveJournal rj = retrieveJournal(true);
        fetcher.answer(bufferFull(10));
        fetcher.failWith(new IOException("connection reset"));

        rj.retrieveJournal(position(1), range(1));
        assertTrue(fetcher.awaitCalls(2));

        final IOException e = assertThrows(IOException.class, () -> rj.retrieveJournal(position(10)),
                "unwrapped, so the streaming loop's connection failure handling applies");
        assertEquals("connection reset", e.getMessage());
        verifyNoInteractions(journalInfoRetrieval);
    }

    @Test
    void noPrefetchWhenSwitchedOff() throws Exception {
        final RetrieveJournal rj = retrieveJournal(false);
        fetcher.answer(bufferFull(10));
        fetcher.answer(rangeRead());

        assertEquals(RetrievalState.MoreDataAvailable, rj.retrieveJournal(position(1), range(1)));
        assertFalse(rj.prefetchInFlight());
        assertEquals(1, fetcher.ranges.size());

        // the continuation is still remembered and reused without asking the journal (#38)
        assertEquals(RetrievalState.Success, rj.retrieveJournal(position(10)));
        assertEquals(List.of(range(1), range(10)), fetcher.ranges);
        verifyNoInteractions(journalInfoRetrieval);
    }

    @Test
    void noPrefetchWhileTheConnectionIsAboutToBeReplaced() throws Exception {
        final RetrieveJournal rj = retrieveJournal(true);
        // the pointer handle budget (threshold 1) is spent: the caller will drop the connection between polls
        rj.pointerHandles().record(42L);
        fetcher.answer(bufferFull(10));
        fetcher.answer(rangeRead());

        assertEquals(RetrievalState.MoreDataAvailable, rj.retrieveJournal(position(1), range(1)));
        assertFalse(rj.prefetchInFlight());
        assertEquals(1, fetcher.ranges.size(), "nothing may be using the connection when it is replaced");

        assertEquals(RetrievalState.Success, rj.retrieveJournal(position(10)));
        assertEquals(List.of(range(1), range(10)), fetcher.ranges, "the continuation is still reused synchronously");
    }

    @Test
    void cancellingTheJobAbandonsThePrefetchAndAForegroundCallWaitsForIt() throws Exception {
        final RetrieveJournal rj = retrieveJournal(true);
        final CountDownLatch gate = new CountDownLatch(1);
        fetcher.answer(bufferFull(10));
        fetcher.answerAfter(gate, rangeRead());
        fetcher.answer(rangeRead());

        rj.retrieveJournal(position(1), range(1));
        assertTrue(fetcher.awaitCalls(2));
        assertTrue(rj.prefetchInFlight());

        rj.cancelJob();
        assertFalse(rj.prefetchInFlight(), "its block is not wanted once the job is being killed");

        // a foreground call issued now must not run alongside the abandoned one
        final Thread foreground = new Thread(() -> {
            try {
                rj.retrieveJournal(position(50), range(50));
            }
            catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
        foreground.start();
        assertFalse(fetcher.awaitCalls(3, 300), "held back while the abandoned call is still running");

        gate.countDown();
        foreground.join(5000);
        assertEquals(List.of(range(1), range(10), range(50)), fetcher.ranges);
    }

    /** Answers in order, from whichever thread asks. */
    static final class FakeFetcher implements RetrieveJournal.BlockFetcher {
        final List<PositionRange> ranges = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedDeque<Callable<FirstHeader>> answers = new ConcurrentLinkedDeque<>();

        void answer(FirstHeader header) {
            answers.add(() -> header);
        }

        void answerAfter(CountDownLatch gate, FirstHeader header) {
            answers.add(() -> {
                gate.await();
                return header;
            });
        }

        void failWith(Exception e) {
            answers.add(() -> {
                throw e;
            });
        }

        boolean awaitCalls(int n) throws InterruptedException {
            return awaitCalls(n, 5000);
        }

        boolean awaitCalls(int n, long timeoutMs) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + timeoutMs;
            while (ranges.size() < n) {
                if (System.currentTimeMillis() > deadline) {
                    return false;
                }
                TimeUnit.MILLISECONDS.sleep(5);
            }
            return true;
        }

        @Override
        public Block fetch(AS400 as400, JournalProcessedPosition from, PositionRange range) throws Exception {
            ranges.add(range);
            final Callable<FirstHeader> answer = answers.pollFirst();
            if (answer == null) {
                throw new IllegalStateException("no answer prepared for " + range);
            }
            // no job: observing one resets the pointer handle budget, which one test spends on purpose
            return new Block(new byte[0], answer.call(), new JournalProcessedPosition(range.end(), Instant.EPOCH, true), range, null);
        }
    }
}
