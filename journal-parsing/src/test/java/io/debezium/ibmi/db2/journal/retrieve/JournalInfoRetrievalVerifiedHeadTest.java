/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;

/**
 * On a caching journal the range end is the newest entry the server has proven readable, not a head aged
 * by the cache wait time (issue #104): the stretch from the proven head to live is asked for and the last
 * entry returned becomes the proven head.
 */
@ExtendWith(MockitoExtension.class)
class JournalInfoRetrievalVerifiedHeadTest {
    @Mock
    AS400 as400;
    private final JournalInfo caching = new JournalInfo("JRN", "LIB", true);
    private final JournalReceiver receiver = new JournalReceiver("RCV1", "LIB");
    private JournalInfoRetrieval retrieval;

    @BeforeEach
    void setup() {
        // a cache wait and an additional delay that would hold the head back a long way if they were applied
        retrieval = spy(new JournalInfoRetrieval(As400TextFactory.forCcsid(37), 30_000L, 5_000L, 250L));
    }

    private DetailedJournalReceiver head(long end) {
        return head(receiver, end);
    }

    private DetailedJournalReceiver head(JournalReceiver rcv, long end) {
        return new DetailedJournalReceiver(new JournalReceiverInfo(rcv, new Date(), JournalStatus.Attached, Optional.of(1)),
                BigInteger.ONE, BigInteger.valueOf(end), Optional.empty(), 1000, end);
    }

    private void live(DetailedJournalReceiver head) throws Exception {
        doReturn(head).when(retrieval).getCurrentDetailedJournalReceiver(as400, caching);
    }

    /** what the server returns for the unfiltered stretch {@code [from, to]}: the newest entry flushed, or nothing */
    private void flushedUpTo(JournalReceiver rcv, long from, long to, Long newest) throws Exception {
        doReturn(Optional.ofNullable(newest).map(BigInteger::valueOf)).when(retrieval)
                .newestRetrievable(as400, caching, rcv, BigInteger.valueOf(from), BigInteger.valueOf(to));
    }

    private BigInteger end(Optional<DetailedJournalReceiver> head) {
        return head.orElseThrow().end();
    }

    @Test
    void aFlushedLiveHeadIsUsedAsIsWithNoDelay() throws Exception {
        final DetailedJournalReceiver live = head(100);
        live(live);
        flushedUpTo(receiver, 100, 100, 100L);

        assertEquals(Optional.of(live), retrieval.getDelayedDetailedJournalReceiver(as400, caching));
    }

    @Test
    void aCachedLiveHeadIsReadUpToTheNewestEntryFlushed() throws Exception {
        final DetailedJournalReceiver first = head(100);
        live(first);
        flushedUpTo(receiver, 100, 100, 100L);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        final DetailedJournalReceiver second = head(120);
        live(second);
        flushedUpTo(receiver, 101, 120, 112L);
        // the stretch above the proven head is asked for once; the range grows to the last entry it held
        final Optional<DetailedJournalReceiver> grown = retrieval.getDelayedDetailedJournalReceiver(as400, caching);
        assertEquals(BigInteger.valueOf(112), end(grown));
        assertTrue(grown.get().isSameReceiver(second));

        final DetailedJournalReceiver third = head(140);
        live(third);
        flushedUpTo(receiver, 113, 140, 140L);
        // everything below the live head has flushed meanwhile: the range reaches it
        assertEquals(BigInteger.valueOf(140), end(retrieval.getDelayedDetailedJournalReceiver(as400, caching)));
        verify(retrieval, times(3)).newestRetrievable(any(), any(), any(), any(), any());
    }

    @Test
    void nothingProvenYetMeansNothingToReadUntilTheFirstHeadSeenFlushes() throws Exception {
        final DetailedJournalReceiver first = head(100);
        live(first);
        flushedUpTo(receiver, 100, 100, null);
        assertTrue(retrieval.getDelayedDetailedJournalReceiver(as400, caching).isEmpty());

        // the stretch keeps starting at the first head seen until something in it flushes
        final DetailedJournalReceiver second = head(120);
        live(second);
        flushedUpTo(receiver, 100, 120, null);
        assertTrue(retrieval.getDelayedDetailedJournalReceiver(as400, caching).isEmpty());

        flushedUpTo(receiver, 100, 120, 110L);
        assertEquals(BigInteger.valueOf(110), end(retrieval.getDelayedDetailedJournalReceiver(as400, caching)));
    }

    @Test
    void anUnmovedHeadIsNotAskedForAgainOnceProven() throws Exception {
        final DetailedJournalReceiver live = head(100);
        live(live);
        flushedUpTo(receiver, 100, 100, 100L);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        live(head(100));
        assertEquals(BigInteger.valueOf(100), end(retrieval.getDelayedDetailedJournalReceiver(as400, caching)));
        verify(retrieval, times(1)).newestRetrievable(any(), any(), any(), any(), any());
    }

    @Test
    void theProvenHeadIsKeptWhileNothingAboveItHasFlushed() throws Exception {
        final DetailedJournalReceiver first = head(100);
        live(first);
        flushedUpTo(receiver, 100, 100, 100L);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        final DetailedJournalReceiver second = head(120);
        live(second);
        flushedUpTo(receiver, 101, 120, null);
        assertEquals(Optional.of(first), retrieval.getDelayedDetailedJournalReceiver(as400, caching));
    }

    @Test
    void aStretchCutShortByTheBufferStillAdvancesAndIsAskedForAgain() throws Exception {
        final DetailedJournalReceiver first = head(100);
        live(first);
        flushedUpTo(receiver, 100, 100, 100L);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        final DetailedJournalReceiver burst = head(1000);
        live(burst);
        flushedUpTo(receiver, 101, 1000, 500L);
        assertEquals(BigInteger.valueOf(500), end(retrieval.getDelayedDetailedJournalReceiver(as400, caching)));

        // the head has not moved but is not proven yet: the rest of the stretch is asked for on the next poll
        flushedUpTo(receiver, 501, 1000, 1000L);
        assertEquals(BigInteger.valueOf(1000), end(retrieval.getDelayedDetailedJournalReceiver(as400, caching)));
    }

    @Test
    void aRolledReceiverStartsProvingAgainOnTheNewOne() throws Exception {
        final DetailedJournalReceiver old = head(100);
        live(old);
        flushedUpTo(receiver, 100, 100, 100L);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        final JournalReceiver next = new JournalReceiver("RCV2", "LIB");
        final DetailedJournalReceiver rolled = head(next, 5);
        live(rolled);
        flushedUpTo(next, 5, 5, null);
        // the proven head is on another receiver and cannot anchor a stretch on this one: it is kept as is
        assertEquals(Optional.of(old), retrieval.getDelayedDetailedJournalReceiver(as400, caching));

        final DetailedJournalReceiver grown = head(next, 50);
        live(grown);
        flushedUpTo(next, 5, 50, 30L);
        final Optional<DetailedJournalReceiver> proven = retrieval.getDelayedDetailedJournalReceiver(as400, caching);
        assertEquals(BigInteger.valueOf(30), end(proven));
        assertTrue(proven.get().isSameReceiver(grown));
    }

    @Test
    void aJournalThatDoesNotCacheIsNotAskedAnything() throws Exception {
        final JournalInfo plain = new JournalInfo("JRN", "LIB", false);
        doReturn(head(100)).when(retrieval).getCurrentDetailedJournalReceiver(as400, plain);

        retrieval.getDelayedDetailedJournalReceiver(as400, plain);
        verify(retrieval, never()).newestRetrievable(any(), any(), any(), any(), any());
    }

    private static JournalProcessedPosition at(JournalReceiver rcv, long offset) {
        return new JournalProcessedPosition(BigInteger.valueOf(offset), rcv, Instant.EPOCH, true);
    }

    @Test
    void aJournalSwitchedToCachingWhileRunningIsReadUpToTheProvenHead() throws Exception {
        final JournalInfo startedPlain = new JournalInfo("JRN", "LIB", false);
        doReturn(head(100)).when(retrieval).getCurrentDetailedJournalReceiver(as400, startedPlain);
        retrieval.getDelayedDetailedJournalReceiver(as400, startedPlain);
        verify(retrieval, never()).newestRetrievable(any(), any(), any(), any(), any());
        assertEquals(5_000L, retrieval.headDelayMs(startedPlain));

        // CHGJRN JRNCACHE(*YES) while running: the flag read with the next poll's receiver switches the regime
        retrieval.observeCaching(startedPlain, true);
        final DetailedJournalReceiver live = head(120);
        doReturn(live).when(retrieval).getCurrentDetailedJournalReceiver(as400, startedPlain);
        doReturn(Optional.of(BigInteger.valueOf(120))).when(retrieval)
                .newestRetrievable(as400, startedPlain, receiver, BigInteger.valueOf(120), BigInteger.valueOf(120));

        assertEquals(Optional.of(live), retrieval.getDelayedDetailedJournalReceiver(as400, startedPlain));
        assertEquals(35_000L, retrieval.headDelayMs(startedPlain));
        assertTrue(retrieval.isProvenReadable(startedPlain, at(receiver, 120)));
        assertFalse(retrieval.isProvenReadable(startedPlain, at(receiver, 121)));
    }

    @Test
    void aJournalThatStopsCachingWhileRunningGoesBackToTheTimer() throws Exception {
        live(head(100));
        flushedUpTo(receiver, 100, 100, 100L);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        retrieval.observeCaching(caching, false);
        live(head(120));
        // the timer starts again from now, so nothing is old enough yet, and nothing is asked of the server
        assertTrue(retrieval.getDelayedDetailedJournalReceiver(as400, caching).isEmpty());
        verify(retrieval, times(1)).newestRetrievable(any(), any(), any(), any(), any());
        assertEquals(5_000L, retrieval.headDelayMs(caching));
        assertTrue(retrieval.isProvenReadable(caching, at(receiver, 1_000)), "a journal that does not cache has nothing to prove");
    }

    @Test
    void onlyARangeEndUpToTheProvenHeadIsProvenReadable() throws Exception {
        assertFalse(retrieval.isProvenReadable(caching, at(receiver, 100)), "nothing proven yet");

        live(head(100));
        flushedUpTo(receiver, 100, 100, 100L);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        assertTrue(retrieval.isProvenReadable(caching, at(receiver, 100)));
        assertFalse(retrieval.isProvenReadable(caching, at(receiver, 101)));
        assertTrue(retrieval.isProvenReadable(caching, at(new JournalReceiver("RCV0", "LIB"), 5_000)),
                "an older receiver has been written out whole");
    }
}
