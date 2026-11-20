/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;

/**
 * What the journal's retained receiver chain says about the cost of a recovery (issue #87): how far back it
 * reaches in time and in entries, and how fast the journal rolls and grows.
 *
 * <p>Every figure here used to be readable only from the receiver list dumped in the {@code ERROR} of a failed
 * call, by hand. It is the same list the streaming loop walks to resolve its ranges; this only summarises it.</p>
 *
 * @param receivers       receivers in the chain
 * @param oldest          the earliest retained receiver, where {@code earliest} recovery resumes; null if none
 * @param attached        the receiver the journal writes to; null if none
 * @param oldestStart     first sequence number still retained
 * @param head            last sequence number of the attached receiver
 * @param retainedEntries entries the chain holds, summed receiver by receiver so a sequence reset does not
 *                        distort it: what {@code earliest} re-reads
 * @param retention       from the oldest receiver's attach time to now; empty without attach times
 * @param medianRoll      median time between two receivers being attached; empty with fewer than two
 */
public record ReceiverChain(int receivers, JournalReceiver oldest, JournalReceiver attached, BigInteger oldestStart,
        BigInteger head, BigInteger retainedEntries, Optional<Duration> retention, Optional<Duration> medianRoll) {

    /**
     * @param chain the receiver list as {@code JournalInfoRetrieval.getReceivers} returns it, oldest first
     */
    public static ReceiverChain of(List<DetailedJournalReceiver> chain, Instant now) {
        final List<DetailedJournalReceiver> receivers = chain.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ReceiverChain::attachTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        if (receivers.isEmpty()) {
            return new ReceiverChain(0, null, null, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, Optional.empty(),
                    Optional.empty());
        }
        final DetailedJournalReceiver first = receivers.get(0);
        final DetailedJournalReceiver last = receivers.stream().filter(DetailedJournalReceiver::isAttached).findFirst()
                .orElse(receivers.get(receivers.size() - 1));

        BigInteger retained = BigInteger.ZERO;
        for (final DetailedJournalReceiver r : receivers) {
            retained = retained.add(span(r));
        }

        final Optional<Duration> retention = Optional.ofNullable(attachTime(first))
                .map(attach -> Duration.between(attach.toInstant(), now))
                .filter(d -> !d.isNegative());

        return new ReceiverChain(receivers.size(), receiver(first), receiver(last), nullToZero(first.start()),
                nullToZero(last.end()), retained, retention, medianRoll(receivers));
    }

    /** Entries one receiver holds; an empty receiver reports an end below its start. */
    private static BigInteger span(DetailedJournalReceiver r) {
        if (r.start() == null || r.end() == null || r.end().compareTo(r.start()) < 0) {
            return BigInteger.ZERO;
        }
        return r.end().subtract(r.start()).add(BigInteger.ONE);
    }

    private static Optional<Duration> medianRoll(List<DetailedJournalReceiver> receivers) {
        final List<Duration> rolls = new ArrayList<>();
        for (int i = 1; i < receivers.size(); i++) {
            final Date previous = attachTime(receivers.get(i - 1));
            final Date current = attachTime(receivers.get(i));
            if (previous != null && current != null) {
                rolls.add(Duration.between(previous.toInstant(), current.toInstant()));
            }
        }
        if (rolls.isEmpty()) {
            return Optional.empty();
        }
        rolls.sort(Comparator.naturalOrder());
        final int middle = rolls.size() / 2;
        if (rolls.size() % 2 == 1) {
            return Optional.of(rolls.get(middle));
        }
        return Optional.of(rolls.get(middle - 1).plus(rolls.get(middle)).dividedBy(2));
    }

    private static Date attachTime(DetailedJournalReceiver r) {
        return r.info() == null ? null : r.info().attachTime();
    }

    private static JournalReceiver receiver(DetailedJournalReceiver r) {
        return r.info() == null ? null : r.info().receiver();
    }

    private static BigInteger nullToZero(BigInteger value) {
        return value == null ? BigInteger.ZERO : value;
    }

    /** Entries per second the chain was written at, averaged over the retention window. */
    public Optional<Long> averageProductionPerSecond() {
        return retention.filter(d -> d.toMillis() > 0)
                .map(d -> retainedEntries.multiply(BigInteger.valueOf(1000)).divide(BigInteger.valueOf(d.toMillis())).longValue());
    }

    /**
     * For the regime line: {@code receivers=424, retention=64.7h, retainedEntries=3525020805, ...}.
     */
    public String describe() {
        if (receivers == 0) {
            return "receivers=0";
        }
        return String.format("receivers=%d, retention=%s, retainedEntries=%s, averageProduction=%s, medianRoll=%s, oldest=%s, "
                + "attached=%s", receivers, retention.map(ReceiverChain::formatDuration).orElse("n/a"), retainedEntries,
                averageProductionPerSecond().map(r -> r + "/s").orElse("n/a"), medianRoll.map(ReceiverChain::formatDuration).orElse("n/a"),
                name(oldest), name(attached));
    }

    static String name(JournalReceiver receiver) {
        return receiver == null ? "n/a" : receiver.library() + "/" + receiver.name();
    }

    /** {@code 45s}, {@code 7.3min}, {@code 64.7h}: one unit, readable at a glance. */
    static String formatDuration(Duration d) {
        final long seconds = d.getSeconds();
        if (seconds < 120) {
            return seconds + "s";
        }
        if (seconds < 2 * 3600) {
            return String.format(Locale.ROOT, "%.1fmin", seconds / 60.0);
        }
        return String.format(Locale.ROOT, "%.1fh", seconds / 3600.0);
    }
}
