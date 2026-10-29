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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfoRetrieval.HeadProbeRefusedException;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfoRetrieval.ProbeFailure;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;

/**
 * The head probe of a caching journal asks for no more than the read, so that a CDC user holding only
 * {@code *USE} on the journal can run it; and a refusal for lack of authority falls back to the timer
 * instead of crash-looping the source (issue #5680).
 */
class JournalHeadProbeAuthorityTest {
    private final AS400 as400 = mock(AS400.class);
    private final JournalInfo caching = new JournalInfo("JRN", "LIB", true);
    private final JournalReceiver receiver = new JournalReceiver("RCV1", "LIB");
    private final JournalInfoRetrieval retrieval = spy(new JournalInfoRetrieval(As400TextFactory.forCcsid(37), 30_000L, 5_000L, 250L));

    private DetailedJournalReceiver head(long end) {
        return new DetailedJournalReceiver(new JournalReceiverInfo(receiver, new Date(), JournalStatus.Attached, Optional.of(1)),
                BigInteger.ONE, BigInteger.valueOf(end), Optional.empty(), 1000, end);
    }

    private static AS400Message message(String id) {
        final AS400Message m = mock(AS400Message.class);
        lenient().when(m.getID()).thenReturn(id);
        return m;
    }

    /** whether the criteria carry key 16 (FILE): records of four values each follow the record count */
    private static boolean filtersFiles(RetrievalCriteria criteria) {
        final Object[] data = criteria.getObject();
        for (int key = 2; key < data.length; key += 4) {
            if (Integer.valueOf(RetrievalCriteria.RetrieveKey.FILE.getKey()).equals(data[key])) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theProbeIsRestrictedToTheFilesTheReadIsRestrictedTo() {
        final BigInteger from = BigInteger.ONE;
        final BigInteger to = BigInteger.TEN;
        assertFalse(filtersFiles(retrieval.probeCriteria(receiver, from, to)), "no filter until the read sets one");

        retrieval.filterHeadProbe(List.of(new FileFilter("LIB", "ORDERS")));
        assertTrue(filtersFiles(retrieval.probeCriteria(receiver, from, to)));

        retrieval.filterHeadProbe(List.of());
        assertFalse(filtersFiles(retrieval.probeCriteria(receiver, from, to)));
    }

    @Test
    void aRefusedProbeFallsBackToTheTimerInsteadOfFailingThePoll() throws Exception {
        doReturn(head(100)).when(retrieval).getCurrentDetailedJournalReceiver(as400, caching);
        doThrow(new HeadProbeRefusedException("CPF9802 Not authorized to object JRN in LIB.")).when(retrieval)
                .newestRetrievable(any(), any(), any(), any(), any());

        // the timer starts now: nothing is old enough to read yet, and nothing is thrown
        assertTrue(retrieval.getDelayedDetailedJournalReceiver(as400, caching).isEmpty());
        doReturn(head(120)).when(retrieval).getCurrentDetailedJournalReceiver(as400, caching);
        assertTrue(retrieval.getDelayedDetailedJournalReceiver(as400, caching).isEmpty());

        verify(retrieval, times(1)).newestRetrievable(any(), any(), any(), any(), any());
        assertEquals(35_000L, retrieval.headDelayMs(caching), "the cache wait plus the additional delay, as before #104");
    }

    @Test
    void anAuthorityRefusalIsToldApartFromTheOtherFailures() {
        assertEquals(ProbeFailure.NOT_AUTHORIZED, JournalInfoRetrieval.classifyProbeFailure(new AS400Message[]{ message("CPF9802") }));
        assertEquals(ProbeFailure.NOT_AUTHORIZED,
                JournalInfoRetrieval.classifyProbeFailure(new AS400Message[]{ message("CPF2189"), message("CPF9999") }));
        assertEquals(ProbeFailure.NOTHING_PROVEN, JournalInfoRetrieval.classifyProbeFailure(new AS400Message[]{ message("CPF7062") }));
        assertEquals(ProbeFailure.NOTHING_PROVEN, JournalInfoRetrieval.classifyProbeFailure(new AS400Message[]{ message("CPF7053") }));
        assertEquals(ProbeFailure.FILTER_INVALID, JournalInfoRetrieval.classifyProbeFailure(new AS400Message[]{ message("CPF7060") }));
        assertEquals(ProbeFailure.OTHER, JournalInfoRetrieval.classifyProbeFailure(new AS400Message[]{ message("CPF9999"), message(null) }));
    }

    @Test
    void theReadHandsItsFileFilterToTheProbe() {
        final JournalInfoRetrieval mocked = mock(JournalInfoRetrieval.class);
        final List<FileFilter> files = List.of(new FileFilter("LIB", "ORDERS"));

        new RetrieveJournal(config(true, files), mocked);
        verify(mocked).filterHeadProbe(files);

        // no server-side filtering (transaction management): the read itself is unfiltered
        final JournalInfoRetrieval unfiltered = mock(JournalInfoRetrieval.class);
        new RetrieveJournal(config(false, files), unfiltered);
        verify(unfiltered).filterHeadProbe(List.of());
    }

    private static RetrieveConfig config(boolean filtering, List<FileFilter> files) {
        return new RetrieveConfigBuilder().withAs400(() -> mock(AS400.class)).withJournalInfo(new JournalInfo("JRN", "LIB", true))
                .withServerFiltering(filtering).withIncludeFiles(files).withPointerHandleThreshold(1L).build();
    }
}
