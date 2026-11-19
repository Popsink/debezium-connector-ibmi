/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Rates derived from two successive diagnostics samples of the journal head and the connector's position
 * (issue #87): how fast the journal grows, how fast the connector reads it, and how fast the lag closes.
 * "Will it catch up" used to need a script over many log lines.
 *
 * <p>Both offsets are journal sequence numbers. A sample in which either went backwards - an offset reset by
 * a recovery, a journal whose sequence was reset - measures nothing, so it yields no rate and the next sample
 * starts again from it. A sample with no position at all - the blank position an {@code earliest} reset leaves
 * until the first entry is read - is not a starting point either: measured from it, the next interval would
 * credit the connector with reading the whole journal up to where it resumed.</p>
 */
public class JournalRates {

    /**
     * Entries per second over one interval between samples.
     *
     * @param production the journal head's advance
     * @param read       the connector position's advance
     * @param saturated  whether the connector read a full buffer at both ends of the interval: it was behind
     *                   throughout, so {@code read} is how fast it can read. Otherwise it read what there was to
     *                   read, which says how fast the journal grew, not how fast the connector could catch up.
     */
    public record Rates(double production, double read, boolean saturated) {
        /** Positive while the lag shrinks, negative while it grows. */
        public double closing() {
            return read - production;
        }

        /** Time to reach the head at this closing rate; empty if the lag is not closing. */
        public Optional<Duration> timeToClose(BigInteger entries) {
            if (closing() <= 0 || entries.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofMillis((long) (entries.doubleValue() / closing() * 1000)));
        }

        public String describe(BigInteger behind) {
            return String.format(Locale.ROOT, "journal production %.0f/s, read %.0f/s, closing %.0f/s, catch-up %s",
                    production, read, closing(),
                    behind.signum() <= 0 ? "none needed"
                            : timeToClose(behind).map(ReceiverChain::formatDuration).orElse("n/a, the lag is not closing"));
        }
    }

    private long lastMs;
    private BigInteger lastHead;
    private BigInteger lastPosition;
    private boolean lastBufferFull;
    private Rates last;

    /**
     * @param position   the connector's position; null while it has none, which drops the previous sample
     * @param bufferFull whether the call this sample follows returned a full buffer
     * @return the rates since the previous sample; empty for the first one, or when the interval measured
     *         nothing
     */
    public Optional<Rates> record(long nowMs, BigInteger head, BigInteger position, boolean bufferFull) {
        if (head == null || position == null) {
            lastHead = null;
            lastPosition = null;
            return Optional.empty();
        }
        Optional<Rates> rates = Optional.empty();
        if (lastHead != null && nowMs > lastMs) {
            final BigInteger produced = head.subtract(lastHead);
            final BigInteger read = position.subtract(lastPosition);
            if (produced.signum() >= 0 && read.signum() >= 0) {
                final double seconds = (nowMs - lastMs) / 1000.0;
                last = new Rates(produced.doubleValue() / seconds, read.doubleValue() / seconds, lastBufferFull && bufferFull);
                rates = Optional.of(last);
            }
        }
        lastMs = nowMs;
        lastHead = head;
        lastPosition = position;
        lastBufferFull = bufferFull;
        return rates;
    }

    /** The rates the last interval that measured anything saw; what a recovery projects its catch-up from. */
    public Optional<Rates> lastRates() {
        return Optional.ofNullable(last);
    }
}
