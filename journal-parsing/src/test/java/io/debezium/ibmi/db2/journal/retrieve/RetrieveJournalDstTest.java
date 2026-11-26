/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.RetrieveJournal.Block;
import io.debezium.ibmi.db2.journal.retrieve.SystemTimeZoneTest.FakeOffsetReader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.FirstHeader;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.OffsetStatus;

/**
 * An IBM i on CEST whose zone jt400 could only resolve to the fixed {@code GMT+02:00}: the entries read
 * after the autumn change carry CET wall-clock stamps, and must not come out an hour in the past - the
 * whole-hour replication lag of Popsink/data-plane#6171.
 */
class RetrieveJournalDstTest {
    private static final Instant AUTUMN_CHANGE = Instant.parse("2026-10-25T01:00:00Z");
    private static final JournalReceiver RECEIVER = new JournalReceiver("receiver", "receiverLibrary");
    private static final JournalPosition END = new JournalPosition(BigInteger.valueOf(100), RECEIVER);
    /** the RJNE0200 entry header up to and including its bit flags */
    private static final int ENTRY_LENGTH = 219;
    private static final int ENTRY_OFFSET = 64;

    private final AtomicLong clock = new AtomicLong(AUTUMN_CHANGE.minusSeconds(1800).toEpochMilli());
    private final FakeOffsetReader qutcoffset = new FakeOffsetReader();
    private Instant nextStamp;

    @Test
    void entriesEitherSideOfTheAutumnChangeComeOutInUtc() throws Exception {
        final RetrieveJournal rj = retrieveJournal(TimeZone.getTimeZone("GMT+02:00"));

        qutcoffset.answer("+0200");
        assertEquals(Instant.parse("2026-10-25T00:15:00Z"), firstEntryTime(rj, "2026-10-25T02:15:00", 1), "CEST before");

        clock.set(AUTUMN_CHANGE.plusSeconds(1800).toEpochMilli());
        qutcoffset.answer("+0100");
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), firstEntryTime(rj, "2026-10-25T02:30:00", 2),
                "CET after, not an hour of lag");
        assertEquals(2, qutcoffset.reads);
    }

    @Test
    void blocksWithinThePeriodDoNotReadTheOffsetAgain() throws Exception {
        final RetrieveJournal rj = retrieveJournal(TimeZone.getTimeZone("GMT+02:00"));
        qutcoffset.answer("+0200");
        for (int i = 1; i <= 5; i++) {
            firstEntryTime(rj, "2026-10-25T02:15:00", i);
            clock.addAndGet(1000);
        }
        assertEquals(1, qutcoffset.reads);
    }

    private RetrieveJournal retrieveJournal(TimeZone jt400Zone) throws Exception {
        final AS400 as400 = mock(AS400.class);
        when(as400.getTimeZone()).thenReturn(jt400Zone);
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(() -> as400)
                .withTextFactory(As400TextFactory.forCcsid(37))
                .withJournalInfo(new JournalInfo("JRN", "LIB", false)).build();
        return new RetrieveJournal(config, mock(JournalInfoRetrieval.class), (system, from, range) -> block(range),
                clock::get, qutcoffset);
    }

    private Instant firstEntryTime(RetrieveJournal rj, String localWallClock, long sequence) throws Exception {
        nextStamp = Instant.parse(localWallClock + "Z");
        final JournalProcessedPosition from = new JournalProcessedPosition(BigInteger.valueOf(sequence), RECEIVER, Instant.EPOCH, false);
        rj.retrieveJournal(from, new PositionRange(false, from, END));
        assertTrue(rj.nextEntry());
        return rj.getEntryHeader().getTime();
    }

    private Block block(PositionRange range) throws Exception {
        final byte[] data = new byte[ENTRY_OFFSET + ENTRY_LENGTH];
        final ByteBuffer entry = ByteBuffer.wrap(data, ENTRY_OFFSET, ENTRY_LENGTH).slice();
        entry.putLong(24, range.start().getOffset().longValue() + 1);
        entry.put(32, dts(nextStamp));
        // journal code, entry type, names: EBCDIC 'A'
        Arrays.fill(data, ENTRY_OFFSET + 82, ENTRY_OFFSET + 218, (byte) 0xC1);
        final FirstHeader header = new FirstHeader(data.length, ENTRY_OFFSET, 1, OffsetStatus.DATA,
                new JournalProcessedPosition(END, Instant.EPOCH, true));
        return new Block(data, header, new JournalProcessedPosition(END, Instant.EPOCH, true), range, "job");
    }

    /**
     * The IBM i TOD clock: microseconds since 1928-08-23T12:03:06.314752 in the high 52 bits, the clock
     * this system set to its local wall-clock time, which jt400 then reads as if it were UTC.
     */
    private static byte[] dts(Instant wallClockAsUtc) {
        final Instant todEpoch = Instant.parse("1928-08-23T12:03:06.314752Z");
        final long micros = Duration.between(todEpoch, wallClockAsUtc).toNanos() / 1000;
        return ByteBuffer.allocate(8).putLong(micros << 12).array();
    }
}
