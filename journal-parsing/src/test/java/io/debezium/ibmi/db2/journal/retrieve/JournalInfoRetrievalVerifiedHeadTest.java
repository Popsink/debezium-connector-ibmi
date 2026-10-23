/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;
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
 * On a caching journal the range end is the newest head the server has proven readable, not a head aged
 * by the cache wait time (issue #104).
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

    private void readable(DetailedJournalReceiver head, boolean readable) throws Exception {
        doReturn(readable).when(retrieval).isEntryRetrievable(as400, caching, head.info().receiver(), head.end());
    }

    @Test
    void aReadableLiveHeadIsUsedAsIsWithNoDelay() throws Exception {
        final DetailedJournalReceiver live = head(100);
        live(live);
        readable(live, true);

        assertEquals(Optional.of(live), retrieval.getDelayedDetailedJournalReceiver(as400, caching));
    }

    @Test
    void aCachedLiveHeadFallsBackToThePreviousPollsHead() throws Exception {
        final DetailedJournalReceiver first = head(100);
        live(first);
        readable(first, true);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        final DetailedJournalReceiver second = head(120);
        live(second);
        readable(second, false);
        // the head of the previous poll is already proven: no second probe, and no step back
        assertEquals(Optional.of(first), retrieval.getDelayedDetailedJournalReceiver(as400, caching));
        verify(retrieval, times(1)).isEntryRetrievable(eq(as400), eq(caching), any(), eq(BigInteger.valueOf(100)));

        final DetailedJournalReceiver third = head(140);
        live(third);
        readable(third, false);
        readable(second, true);
        // the live head is still cached, the one before it has flushed meanwhile: the range grows to it
        assertEquals(Optional.of(second), retrieval.getDelayedDetailedJournalReceiver(as400, caching));
    }

    @Test
    void nothingProvenYetMeansNothingToRead() throws Exception {
        final DetailedJournalReceiver live = head(100);
        live(live);
        readable(live, false);

        assertTrue(retrieval.getDelayedDetailedJournalReceiver(as400, caching).isEmpty());
        // one probe only: there is no previous head to try
        verify(retrieval, times(1)).isEntryRetrievable(any(), any(), any(), any());
    }

    @Test
    void anUnmovedHeadIsNotProbedAgainOnceProven() throws Exception {
        final DetailedJournalReceiver live = head(100);
        live(live);
        readable(live, true);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        live(head(100));
        assertEquals(BigInteger.valueOf(100), retrieval.getDelayedDetailedJournalReceiver(as400, caching).get().end());
        verify(retrieval, times(1)).isEntryRetrievable(any(), any(), any(), any());
    }

    @Test
    void theProvenHeadIsKeptWhileBothCandidatesAreStillCached() throws Exception {
        final DetailedJournalReceiver first = head(100);
        live(first);
        readable(first, true);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        final DetailedJournalReceiver second = head(120);
        live(second);
        readable(second, false);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        final DetailedJournalReceiver third = head(140);
        live(third);
        readable(third, false);
        readable(second, false);
        assertEquals(Optional.of(first), retrieval.getDelayedDetailedJournalReceiver(as400, caching));
    }

    @Test
    void aRolledReceiverStartsProvingAgainOnTheNewOne() throws Exception {
        final DetailedJournalReceiver old = head(100);
        live(old);
        readable(old, true);
        retrieval.getDelayedDetailedJournalReceiver(as400, caching);

        final JournalReceiver next = new JournalReceiver("RCV2", "LIB");
        final DetailedJournalReceiver rolled = head(next, 5);
        live(rolled);
        readable(rolled, false);
        // the previous head is on another receiver: it cannot stand in, the old proven head is kept
        assertEquals(Optional.of(old), retrieval.getDelayedDetailedJournalReceiver(as400, caching));
        verify(retrieval, times(1)).isEntryRetrievable(as400, caching, receiver, BigInteger.valueOf(100));

        readable(rolled, true);
        assertEquals(Optional.of(rolled), retrieval.getDelayedDetailedJournalReceiver(as400, caching));
    }

    @Test
    void aJournalThatDoesNotCacheIsNotProbed() throws Exception {
        final JournalInfo plain = new JournalInfo("JRN", "LIB", false);
        doReturn(head(100)).when(retrieval).getCurrentDetailedJournalReceiver(as400, plain);

        retrieval.getDelayedDetailedJournalReceiver(as400, plain);
        verify(retrieval, never()).isEntryRetrievable(any(), any(), any(), any());
    }
}
