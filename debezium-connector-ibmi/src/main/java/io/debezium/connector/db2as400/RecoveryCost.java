/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;

import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;

/**
 * What recovering from a lost journal position costs, measured against the receiver chain at the moment it is
 * decided (issue #87): the entries no receiver holds any more, what {@code earliest} re-reads, what
 * {@code latest} skips, the retention window, and the time the re-read takes at the rates last observed.
 *
 * <p>A recovery warning used to assert a data loss unconditionally. Whether there is one is arithmetic over the
 * chain: the entries between the lost position and the first one still retained. When the sequence numbers do
 * not place the lost position before the chain - a journal recreated or its sequence reset - that arithmetic
 * means nothing, and the warning says it could not be measured instead of claiming either answer.</p>
 *
 * @param lost  the position that could not be read
 * @param chain the receiver chain as it is now
 * @param rates the rates the periodic diagnostics last measured, if any
 */
public record RecoveryCost(JournalProcessedPosition lost, ReceiverChain chain, Optional<JournalRates.Rates> rates) {

    /** The first entry the connector still needed. */
    private BigInteger nextNeeded() {
        return lost.processed() ? lost.getOffset().add(BigInteger.ONE) : lost.getOffset();
    }

    /**
     * Entries after the lost position that no retained receiver holds: lost under any strategy. Empty when that
     * cannot be measured: no offset, no chain, or a lost position the chain's sequence numbers do not come after.
     */
    public Optional<BigInteger> missingEntries() {
        if (!lost.isOffsetSet() || chain.receivers() == 0) {
            return Optional.empty();
        }
        final BigInteger missing = chain.oldestStart().subtract(nextNeeded());
        return missing.signum() < 0 ? Optional.empty() : Optional.of(missing);
    }

    /** Entries between the lost position and the head that are still retained, which {@code latest} skips. */
    public Optional<BigInteger> retainedEntriesSkippedByLatest() {
        if (!lost.isOffsetSet() || chain.receivers() == 0) {
            return Optional.empty();
        }
        final BigInteger from = chain.oldestStart().max(nextNeeded());
        final BigInteger skipped = chain.head().subtract(from).add(BigInteger.ONE);
        return Optional.of(skipped.max(BigInteger.ZERO));
    }

    /**
     * What a reset to the earliest receiver leaves the connector with, and how long it takes to come back at the
     * rates last observed. A connector at the head reads only what the journal produces, so a read rate no
     * higher than production projects nothing rather than "never".
     */
    String earliestCatchUp() {
        final BigInteger reread = chain.retainedEntries();
        return rates.map(r -> r.timeToClose(reread)
                .map(d -> String.format(Locale.ROOT, "%s at the last observed rates (read %.0f/s, production %.0f/s)",
                        ReceiverChain.formatDuration(d), r.read(), r.production()))
                .orElse(String.format(Locale.ROOT, "not projected: the last observed read rate (%.0f/s) did not exceed "
                        + "production (%.0f/s), which is all a connector at the head can read", r.read(), r.production())))
                .orElse("not projected: no read rate observed yet");
    }

    /**
     * The facts for the recovery warning, logged before any strategy is applied.
     */
    public String describe() {
        return String.format("lost position %s offset %s; %s; earliest re-reads %s entries, catch-up %s; latest skips %s "
                + "retained entries; %s", ReceiverChain.name(lost.getReceiver()), lost.isOffsetSet() ? lost.getOffset() : "n/a",
                gapStatement(), chain.retainedEntries(), earliestCatchUp(),
                retainedEntriesSkippedByLatest().map(BigInteger::toString).orElse("n/a"), chain.describe());
    }

    /**
     * Whether the entries between the lost position and the earliest retained receiver are gone, stated only as
     * far as the chain shows it.
     */
    public String gapStatement() {
        return missingEntries().map(missing -> missing.signum() == 0
                ? "no entry is missing between the lost position and the earliest available receiver, which starts right after it"
                : missing + " entries between the lost position and the earliest available receiver are unrecoverable")
                .orElse("whether entries are missing between the lost position and the earliest available receiver could not be "
                        + "measured: the chain's sequence numbers do not come after the lost position");
    }

    /** {@link #gapStatement()}, or why there is no measurement at all. */
    public static String gapStatement(Optional<RecoveryCost> cost) {
        return cost.map(RecoveryCost::gapStatement)
                .orElse("whether entries are missing between the lost position and the earliest available receiver could not be "
                        + "measured: the receiver chain could not be read");
    }

    /** {@link #describe()}, or why there is nothing to describe. */
    public static String describe(Optional<RecoveryCost> cost) {
        return cost.map(RecoveryCost::describe).orElse("recovery cost unknown: the receiver chain could not be read");
    }
}
