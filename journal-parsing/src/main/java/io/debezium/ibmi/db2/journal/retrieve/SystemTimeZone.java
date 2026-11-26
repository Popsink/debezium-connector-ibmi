/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.SystemValue;

/**
 * The IBM i system time zone that turns a journal entry's {@code *DTS} timestamp, the system's local wall
 * clock, into UTC (Popsink/data-plane#6171).
 *
 * <p>jt400 resolves {@code QTIMZON} to a zone with daylight saving rules when it can map it. When it cannot,
 * it falls back to {@code QUTCOFFSET}, a fixed {@code GMT±hh:mm} read once, or to the JVM's own zone when
 * that is not set. A fixed offset kept for the life of the worker is one hour wrong after the next DST
 * change. A zone without DST rules is therefore re-read from {@code QUTCOFFSET}, which the system moves at
 * each change, at most every {@link #REFRESH_MS}: on the first block read, then on the first block read
 * once the period is over. An idle journal costs no call.</p>
 */
public class SystemTimeZone {
    private static final Logger log = LoggerFactory.getLogger(SystemTimeZone.class);

    /** How long a fixed offset is trusted: an entry can be off by an hour for at most this long after a DST change. */
    static final long REFRESH_MS = TimeUnit.MINUTES.toMillis(5);

    /** Reads {@code QUTCOFFSET}, e.g. {@code +0100}; replaceable in tests. */
    interface OffsetReader {
        String read(AS400 as400) throws Exception;
    }

    static final OffsetReader QUTCOFFSET = as400 -> (String) new SystemValue(as400, "QUTCOFFSET").getValue();

    private final LongSupplier clockMs;
    private final OffsetReader offsetReader;
    /** false when the zone carries DST rules: those already give each entry the offset of its own date */
    private final boolean followsUtcOffset;
    private ZoneId zone;
    private long nextRefreshMs;

    SystemTimeZone(ZoneId zone, boolean followsUtcOffset, LongSupplier clockMs, OffsetReader offsetReader) {
        this.zone = zone;
        this.followsUtcOffset = followsUtcOffset;
        this.clockMs = clockMs;
        this.offsetReader = offsetReader;
        // the AS400 object caches its zone, so the one resolved here may predate the last DST change
        this.nextRefreshMs = clockMs.getAsLong();
    }

    /** A zone without DST rules (fixed offset, jt400's fallbacks, or ours) is re-read from {@code QUTCOFFSET}. */
    static SystemTimeZone resolve(Connect<AS400, IOException> as400, LongSupplier clockMs, OffsetReader offsetReader) {
        TimeZone resolved;
        try {
            resolved = as400.connection().getTimeZone();
        }
        catch (final Exception e) {
            log.error("failed to fetch the IBM i system time zone, journal entry timestamps are read as GMT until "
                    + "QUTCOFFSET can be read", e);
            resolved = null;
        }
        final boolean fallback = resolved == null;
        final TimeZone zone = fallback ? TimeZone.getTimeZone("GMT") : resolved;
        final boolean followsUtcOffset = fallback || !zone.observesDaylightTime();
        final Instant now = Instant.ofEpochMilli(clockMs.getAsLong());
        final ZoneId zoneId = zone.toZoneId();
        if (followsUtcOffset) {
            final boolean jvmZone = !fallback && zone.getID().equals(TimeZone.getDefault().getID());
            log.warn("IBM i system time zone {} (UTC offset now {}) has no daylight saving rules{}: QUTCOFFSET is "
                    + "re-read at most every {}s while the journal is read", zone.getID(), offset(zoneId, now),
                    jvmZone ? ", it is this JVM's own zone, jt400's fallback when QUTCOFFSET is not set" : "",
                    TimeUnit.MILLISECONDS.toSeconds(REFRESH_MS));
        }
        else {
            log.info("IBM i system time zone {} (UTC offset now {}), with daylight saving rules", zone.getID(),
                    offset(zoneId, now));
        }
        return new SystemTimeZone(zoneId, followsUtcOffset, clockMs, offsetReader);
    }

    /** Re-reads {@code QUTCOFFSET} when due; a failure keeps the current zone until the next period. */
    void refreshIfDue(Connect<AS400, IOException> as400) {
        if (!followsUtcOffset) {
            return;
        }
        final long nowMs = clockMs.getAsLong();
        if (nowMs < nextRefreshMs) {
            return;
        }
        nextRefreshMs = nowMs + REFRESH_MS;
        final Instant now = Instant.ofEpochMilli(nowMs);
        final String value;
        try {
            value = offsetReader.read(as400.connection());
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while reading QUTCOFFSET, keeping the UTC offset {}", offset(zone, now));
            return;
        }
        catch (final Exception e) {
            log.warn("failed to read QUTCOFFSET, keeping the UTC offset {} for another {}s", offset(zone, now),
                    TimeUnit.MILLISECONDS.toSeconds(REFRESH_MS), e);
            return;
        }
        final ZoneOffset read;
        try {
            read = ZoneOffset.of(value == null ? "" : value.trim());
        }
        catch (final DateTimeException e) {
            log.warn("QUTCOFFSET '{}' is not a UTC offset, keeping the UTC offset {}", value, offset(zone, now));
            return;
        }
        final ZoneOffset previous = offset(zone, now);
        if (!previous.equals(read)) {
            zone = read;
            log.info("IBM i UTC offset changed from {} to {} (QUTCOFFSET)", previous, read);
        }
    }

    /**
     * The entry's wall-clock time, read by jt400 as if it were UTC, moved to the real UTC instant. The offset
     * is the one in force at that local time, not at the wall-clock digits read as UTC, which sit an offset
     * away and crossed a DST change an hour early; a local time repeated in autumn takes the summer offset.
     */
    public Instant toUtc(Instant wallClockAsUtc) {
        return LocalDateTime.ofInstant(wallClockAsUtc, ZoneOffset.UTC).atZone(zone).toInstant();
    }

    private static ZoneOffset offset(ZoneId zone, Instant at) {
        return zone.getRules().getOffset(at);
    }
}
