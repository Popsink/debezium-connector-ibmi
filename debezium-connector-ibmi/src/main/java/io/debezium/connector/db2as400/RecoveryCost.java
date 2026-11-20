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
 * chain: the entries between the lost position and the first one still retained. When the lost position's
 * sequence number falls inside the chain, its entries appear to be retained and the warning says so; when it is
 * past the chain's head - a journal recreated or its sequence reset - that arithmetic means nothing, and the
 * warning says it could not be measured instead of claiming either answer.</p>
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

    /**
     * Entries {@code latest} skips: the whole retained chain when the lost position comes before it, counted
     * receiver by receiver like {@link ReceiverChain#retainedEntries()} so a sequence reset inside the chain does
     * not distort it; what follows the lost position when its sequence number still falls inside the chain.
     * Empty when the lost position is past the chain's head - a journal recreated or its sequence reset - as
     * then nothing places it in the chain.
     */
    public Optional<BigInteger> retainedEntriesSkippedByLatest() {
        if (!lost.isOffsetSet() || chain.receivers() == 0) {
            return Optional.empty();
        }
        final BigInteger next = nextNeeded();
        if (next.compareTo(chain.oldestStart()) <= 0) {
            return Optional.of(chain.retainedEntries());
        }
        if (next.compareTo(chain.head().add(BigInteger.ONE)) <= 0) {
            return Optional.of(chain.head().subtract(next).add(BigInteger.ONE));
        }
        return Optional.empty();
    }

    /**
     * What a reset to the earliest receiver leaves the connector with, and how long it takes to come back at the
     * rates last observed. Only a connector that was reading full buffers - behind, reading as fast as it can -
     * shows how fast it reads; one that was keeping up read what the journal produced, and a projection from
     * that would be noise (a few entries per second of closing rate turn a re-read into years).
     */
    String earliestCatchUp() {
        final BigInteger reread = chain.retainedEntries();
        if (reread.signum() <= 0) {
            return "none needed: nothing is retained to re-read";
        }
        return rates.map(r -> {
            if (!r.saturated()) {
                return String.format(Locale.ROOT, "not projected: when last measured the connector was keeping up (read %.0f/s, "
                        + "production %.0f/s), so its read rate shows what the journal produced, not how fast it can re-read",
                        r.read(), r.production());
            }
            return r.timeToClose(reread)
                    .map(d -> String.format(Locale.ROOT, "%s at the last observed rates (read %.0f/s, production %.0f/s)",
                            ReceiverChain.formatDuration(d), r.read(), r.production()))
                    .orElse(String.format(Locale.ROOT, "never at the last observed rates: reading full buffers, the connector "
                            + "read %.0f/s against %.0f/s produced", r.read(), r.production()));
        }).orElse("not projected: no read rate observed yet");
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
     * far as the chain shows it. A lost position whose sequence number still falls inside the chain - a position
     * reported lost while its entries are retained (#79) - is said to be so, not left as unmeasured.
     */
    public String gapStatement() {
        final Optional<BigInteger> missing = missingEntries();
        if (missing.isPresent()) {
            return missing.get().signum() == 0
                    ? "no entry is missing between the lost position and the earliest available receiver, which starts right after it"
                    : missing.get() + " entries between the lost position and the earliest available receiver are unrecoverable";
        }
        if (!lost.isOffsetSet() || chain.receivers() == 0) {
            return "whether entries are missing between the lost position and the earliest available receiver could not be "
                    + "measured: " + (lost.isOffsetSet() ? "no receiver is retained" : "the lost position has no sequence number");
        }
        final BigInteger next = nextNeeded();
        if (next.compareTo(chain.head().add(BigInteger.ONE)) <= 0) {
            return "no entry appears missing: the lost position's sequence number " + lost.getOffset() + " still falls inside "
                    + "the retained chain (" + chain.oldestStart() + " to " + chain.head() + "), unless the journal's sequence was reset";
        }
        return "whether entries are missing between the lost position and the earliest available receiver could not be "
                + "measured: the lost position's sequence number " + lost.getOffset() + " is past the chain's head " + chain.head()
                + ", so the journal was recreated or its sequence reset";
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
