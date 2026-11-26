/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400;

/**
 * A journal entry's {@code *DTS} is the IBM i local wall clock. When jt400 hands back a fixed offset
 * ({@code QUTCOFFSET}) instead of a zone with DST rules, that offset has to follow the system across a DST
 * change, or every entry after it is an hour off (Popsink/data-plane#6171). Dates are the 2026 EU changes,
 * both at 01:00 UTC.
 */
class SystemTimeZoneTest {
    private static final Instant SPRING_CHANGE = Instant.parse("2026-03-29T01:00:00Z");
    private static final Instant AUTUMN_CHANGE = Instant.parse("2026-10-25T01:00:00Z");

    private final AtomicLong clock = new AtomicLong();
    private final FakeOffsetReader qutcoffset = new FakeOffsetReader();

    private SystemTimeZone resolve(TimeZone jt400Zone) throws Exception {
        final AS400 as400 = mock(AS400.class);
        when(as400.getTimeZone()).thenReturn(jt400Zone);
        return SystemTimeZone.resolve(() -> as400, clock::get, qutcoffset);
    }

    private static Connect<AS400, IOException> as400() {
        return () -> mock(AS400.class);
    }

    /** an entry stamped at this local wall-clock time, as jt400 decodes it: the digits read as UTC */
    private static Instant stamped(String localWallClock) {
        return Instant.parse(localWallClock + "Z");
    }

    private void at(Instant now) {
        clock.set(now.toEpochMilli());
    }

    @Test
    void aFixedOffsetFollowsTheSpringChange() throws Exception {
        at(SPRING_CHANGE.minusSeconds(1800));
        final SystemTimeZone tz = resolve(TimeZone.getTimeZone("GMT+01:00"));
        qutcoffset.answer("+0100");
        tz.refreshIfDue(as400());
        assertEquals(Instant.parse("2026-03-29T00:45:00Z"), tz.toUtc(stamped("2026-03-29T01:45:00")), "CET before the change");

        at(SPRING_CHANGE.plusSeconds(1800));
        qutcoffset.answer("+0200");
        tz.refreshIfDue(as400());
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), tz.toUtc(stamped("2026-03-29T03:30:00")),
                "CEST after the change, not an hour in the future");
    }

    @Test
    void aFixedOffsetFollowsTheAutumnChange() throws Exception {
        at(AUTUMN_CHANGE.minusSeconds(1800));
        final SystemTimeZone tz = resolve(TimeZone.getTimeZone("GMT+02:00"));
        qutcoffset.answer("+0200");
        tz.refreshIfDue(as400());
        assertEquals(Instant.parse("2026-10-25T00:15:00Z"), tz.toUtc(stamped("2026-10-25T02:15:00")), "CEST before the change");

        at(AUTUMN_CHANGE.plusSeconds(1800));
        qutcoffset.answer("+0100");
        tz.refreshIfDue(as400());
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), tz.toUtc(stamped("2026-10-25T02:30:00")),
                "CET after the change, not an hour of lag");
    }

    @Test
    void theOffsetIsReadOncePerPeriodNotPerEntry() throws Exception {
        at(AUTUMN_CHANGE);
        final SystemTimeZone tz = resolve(TimeZone.getTimeZone("GMT+02:00"));
        qutcoffset.answer("+0100");
        for (int i = 0; i < 1000; i++) {
            tz.refreshIfDue(as400());
            clock.addAndGet(100);
        }
        assertEquals(1, qutcoffset.reads, "the first block reads it, the next ones within the period do not");

        at(AUTUMN_CHANGE.plusMillis(SystemTimeZone.REFRESH_MS));
        tz.refreshIfDue(as400());
        assertEquals(2, qutcoffset.reads, "read again once the period is over");
    }

    @Test
    void aZoneWithDaylightSavingRulesIsNeverReRead() throws Exception {
        at(SPRING_CHANGE.minusSeconds(1800));
        final SystemTimeZone tz = resolve(TimeZone.getTimeZone("Europe/Paris"));
        tz.refreshIfDue(as400());
        at(SPRING_CHANGE.plusSeconds(3600));
        tz.refreshIfDue(as400());

        assertEquals(0, qutcoffset.reads);
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), tz.toUtc(stamped("2026-03-29T03:30:00")), "CEST after the change");
    }

    @Test
    void theLastHourBeforeTheSpringChangeTakesTheOffsetOfItsLocalTime() throws Exception {
        // 01:45 CET is 00:45Z; its digits read as UTC, 01:45Z, are already past the change
        final SystemTimeZone tz = resolve(TimeZone.getTimeZone("Europe/Paris"));
        assertEquals(Instant.parse("2026-03-29T00:45:00Z"), tz.toUtc(stamped("2026-03-29T01:45:00")));
    }

    @Test
    void theJvmZoneJt400FallsBackToFollowsTheSystemOffset() throws Exception {
        // QUTCOFFSET was empty when jt400 resolved the zone, it gave the pod's UTC; the system is on CET
        at(AUTUMN_CHANGE.plusSeconds(3600));
        final SystemTimeZone tz = resolve(TimeZone.getTimeZone("UTC"));
        qutcoffset.answer("+0100");
        tz.refreshIfDue(as400());
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), tz.toUtc(stamped("2026-10-25T10:00:00")));
    }

    @Test
    void aSystemUnreachableAtStartReadsGmtUntilTheOffsetIsRead() throws Exception {
        at(AUTUMN_CHANGE.plusSeconds(3600));
        final SystemTimeZone tz = SystemTimeZone.resolve(() -> {
            throw new IOException("connection refused");
        }, clock::get, qutcoffset);
        assertEquals(stamped("2026-10-25T10:00:00"), tz.toUtc(stamped("2026-10-25T10:00:00")), "no correction yet");

        qutcoffset.answer("+0100");
        tz.refreshIfDue(as400());
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), tz.toUtc(stamped("2026-10-25T10:00:00")));
    }

    @Test
    void aFailedOrUnreadableOffsetKeepsTheLastOneUntilTheNextPeriod() throws Exception {
        at(AUTUMN_CHANGE.minusSeconds(1800));
        final SystemTimeZone tz = resolve(TimeZone.getTimeZone("GMT+02:00"));
        qutcoffset.fail(new IOException("connection reset"));
        tz.refreshIfDue(as400());
        assertEquals(Instant.parse("2026-10-25T00:15:00Z"), tz.toUtc(stamped("2026-10-25T02:15:00")), "kept after a failure");
        clock.addAndGet(1000);
        tz.refreshIfDue(as400());
        assertEquals(1, qutcoffset.reads, "a failure waits for the next period, it is not retried on the next block");

        at(AUTUMN_CHANGE.minusSeconds(1800).plusMillis(SystemTimeZone.REFRESH_MS));
        qutcoffset.answer("");
        tz.refreshIfDue(as400());
        assertEquals(Instant.parse("2026-10-25T00:15:00Z"), tz.toUtc(stamped("2026-10-25T02:15:00")), "kept when not set");

        at(AUTUMN_CHANGE.plusSeconds(3600));
        qutcoffset.answer("+0100");
        tz.refreshIfDue(as400());
        assertEquals(3, qutcoffset.reads);
        assertEquals(Instant.parse("2026-10-25T02:00:00Z"), tz.toUtc(stamped("2026-10-25T03:00:00")));
    }

    /** answers {@code QUTCOFFSET} reads in order */
    static class FakeOffsetReader implements SystemTimeZone.OffsetReader {
        private final Deque<Object> answers = new ArrayDeque<>();
        int reads;

        void answer(String value) {
            answers.add(value);
        }

        void fail(Exception e) {
            answers.add(e);
        }

        @Override
        public String read(AS400 as400) throws Exception {
            reads++;
            final Object next = answers.isEmpty() ? null : answers.poll();
            if (next instanceof Exception e) {
                throw e;
            }
            return (String) next;
        }
    }
}
