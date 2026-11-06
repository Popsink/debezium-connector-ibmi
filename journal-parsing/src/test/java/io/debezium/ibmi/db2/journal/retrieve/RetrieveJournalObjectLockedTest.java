/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.ServiceProgramCall;

import io.debezium.ibmi.db2.journal.retrieve.exception.ObjectLockedException;
import io.debezium.ibmi.db2.journal.retrieve.exception.RetrieveJournalException;

/**
 * CPF7007 - a member allocated by another job - is transient, and must reach the streaming loop as its own
 * exception rather than as the generic failure, which is retried for minutes only (issue #107).
 */
class RetrieveJournalObjectLockedTest {

    private static final String LOCKED = "Cannot allocate member TABLE1 file TABLE1 in LIB.";
    private static final JournalReceiver RECEIVER = new JournalReceiver("receiver", "receiverLibrary");

    private final RetrieveJournal retrieveJournal = new RetrieveJournal(
            new RetrieveConfigBuilder().withAs400(() -> mock(AS400.class))
                    .withJournalInfo(new JournalInfo("JRN", "LIB", false)).withPrefetch(false).build(),
            mock(JournalInfoRetrieval.class), (as400, from, range) -> {
                throw new UnsupportedOperationException("not called");
            });

    private static ServiceProgramCall failedWith(String id, String text) {
        final AS400Message message = mock(AS400Message.class);
        when(message.getID()).thenReturn(id);
        when(message.getText()).thenReturn(text);
        final ServiceProgramCall spc = mock(ServiceProgramCall.class);
        when(spc.getMessageList()).thenReturn(new AS400Message[]{ message });
        return spc;
    }

    private Exception failure(ServiceProgramCall spc) {
        final JournalProcessedPosition position = new JournalProcessedPosition(BigInteger.ONE, RECEIVER, Instant.EPOCH, true);
        return assertThrows(Exception.class, () -> retrieveJournal.reThrowIfFatal(position, spc, position,
                new PositionRange(false, position, new JournalPosition(BigInteger.TEN, RECEIVER)), "job"));
    }

    @Test
    void aLockedMemberIsReportedAsSuch() {
        final Exception e = failure(failedWith("CPF7007", LOCKED));

        assertEquals(ObjectLockedException.class, e.getClass());
        assertTrue(e.getMessage().contains(LOCKED), "the server's text names the member");
    }

    @Test
    void anUnknownFailureIsStillTheGenericOne() {
        assertEquals(RetrieveJournalException.class, failure(failedWith("CPF9999", "something else")).getClass());
    }
}
