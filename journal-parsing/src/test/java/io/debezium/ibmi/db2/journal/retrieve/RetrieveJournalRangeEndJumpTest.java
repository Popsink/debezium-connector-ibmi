/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.retrieve.RetrieveJournal.Block;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.OffsetStatus;

/**
 * A block the server answered with nothing moves the position to the end of the range asked for. That is
 * normal when nothing there matched the filters, and silent data loss when the server could not read the
 * range yet (issue #103): every such jump is counted, and one to an end the server had not proven readable
 * is counted apart.
 */
class RetrieveJournalRangeEndJumpTest {
    private static final JournalReceiver RECEIVER = new JournalReceiver("receiver", "receiverLibrary");
    private static final JournalInfo JOURNAL = new JournalInfo("JRN", "LIB", true);

    private final JournalInfoRetrieval journalInfoRetrieval = mock(JournalInfoRetrieval.class);

    private static JournalProcessedPosition position(long offset) {
        return new JournalProcessedPosition(BigInteger.valueOf(offset), RECEIVER, Instant.EPOCH, true);
    }

    private static PositionRange range(long from, long to) {
        return new PositionRange(false, position(from), new JournalPosition(BigInteger.valueOf(to), RECEIVER));
    }

    private RetrieveJournal retrieveJournal(OffsetStatus status) {
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(() -> mock(AS400.class))
                .withJournalInfo(JOURNAL).withPrefetch(false).build();
        return new RetrieveJournal(config, journalInfoRetrieval, (as400, from, range) -> {
            final JournalProcessedPosition end = new JournalProcessedPosition(range.end(), Instant.EPOCH, true);
            // a block that ran out of buffer holds entries, an empty answer none
            final FirstHeader header = status == OffsetStatus.NO_DATA ? new FirstHeader(0, 0, 0, status, end)
                    : new FirstHeader(1000, 64, 10, status, position(30));
            return new Block(new byte[0], header, end, range, null);
        });
    }

    @Test
    void anEmptyAnswerUpToAProvenEndIsCountedButNotUnproven() throws Exception {
        when(journalInfoRetrieval.isProvenReadable(any(), any())).thenReturn(true);
        final RetrieveJournal rj = retrieveJournal(OffsetStatus.NO_DATA);

        rj.retrieveJournal(position(10), range(10, 50));

        assertEquals(position(50), rj.getPosition());
        assertEquals(1, rj.rangeEndJumps());
        assertEquals(0, rj.unprovenRangeEndJumps());
    }

    @Test
    void anEmptyAnswerUpToAnUnprovenEndIsCountedAsUnproven() throws Exception {
        when(journalInfoRetrieval.isProvenReadable(any(), any())).thenReturn(false);
        final RetrieveJournal rj = retrieveJournal(OffsetStatus.NO_DATA);

        rj.retrieveJournal(position(10), range(10, 50));
        rj.retrieveJournal(position(50), range(50, 80));

        assertEquals(position(80), rj.getPosition(), "the position still moves: counting is all this does");
        assertEquals(2, rj.rangeEndJumps());
        assertEquals(2, rj.unprovenRangeEndJumps());
    }

    @Test
    void aBlockThatRanOutOfBufferIsNotAJump() throws Exception {
        final RetrieveJournal rj = retrieveJournal(OffsetStatus.MORE_DATA_NEW_OFFSET);

        rj.retrieveJournal(position(10), range(10, 50));

        assertEquals(0, rj.rangeEndJumps());
    }
}
