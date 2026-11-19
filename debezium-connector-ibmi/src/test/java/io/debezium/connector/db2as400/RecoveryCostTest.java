/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;

/**
 * The recovery warning states what it measured (issue #87): the distance re-read or skipped, the retention
 * window, the projected catch-up, and a data loss only where the chain shows one.
 */
class RecoveryCostTest {

    /** retained 101..3 700 in three receivers, one hour of retention */
    private static final ReceiverChain CHAIN = ReceiverChain.of(ReceiverChainTest.chain(), ReceiverChainTest.NOW);

    private static JournalProcessedPosition processed(long offset, String receiver) {
        return new JournalProcessedPosition(BigInteger.valueOf(offset), new JournalReceiver(receiver, "RCVLIB"), Instant.EPOCH, true);
    }

    private static RecoveryCost cost(JournalProcessedPosition lost, Optional<JournalRates.Rates> rates) {
        return new RecoveryCost(lost, CHAIN, rates);
    }

    @Test
    void entriesPrunedPastTheLostPositionAreUnrecoverable() {
        // last processed 60, oldest retained 101: 61..100 are in no receiver any more
        final RecoveryCost cost = cost(processed(60, "RCV000"), Optional.empty());

        assertThat(cost.missingEntries()).contains(BigInteger.valueOf(40));
        assertThat(cost.gapStatement()).isEqualTo(
                "40 entries between the lost position and the earliest available receiver are unrecoverable");
    }

    /**
     * The receiver the position named has gone, but the oldest retained one starts with the very next entry:
     * nothing is lost, and the warning must not say otherwise.
     */
    @Test
    void aChainStartingRightAfterTheLostPositionClaimsNoLoss() {
        final RecoveryCost cost = cost(processed(100, "RCV000"), Optional.empty());

        assertThat(cost.missingEntries()).contains(BigInteger.ZERO);
        assertThat(cost.gapStatement()).doesNotContain("unrecoverable").contains("no entry is missing");
    }

    /** A position not yet processed still needs its own entry. */
    @Test
    void anUnprocessedPositionStillNeedsItsOwnEntry() {
        final JournalProcessedPosition lost = new JournalProcessedPosition(BigInteger.valueOf(100),
                new JournalReceiver("RCV000", "RCVLIB"), Instant.EPOCH, false);

        assertThat(cost(lost, Optional.empty()).missingEntries()).contains(BigInteger.ONE);
    }

    /**
     * A journal recreated, or its sequence reset, numbers the chain past the lost position: the arithmetic means
     * nothing then, and the warning says it could not measure rather than claiming either answer.
     */
    @Test
    void aLostPositionTheChainDoesNotComeAfterIsNotMeasured() {
        final RecoveryCost cost = cost(processed(2_000, "OLDRCV"), Optional.empty());

        assertThat(cost.missingEntries()).isEmpty();
        assertThat(cost.gapStatement()).doesNotContain("unrecoverable").contains("could not be measured");
    }

    @Test
    void anUnreadableChainIsNotMeasuredEither() {
        assertThat(RecoveryCost.gapStatement(Optional.empty())).doesNotContain("unrecoverable").contains("could not be read");
        assertThat(RecoveryCost.describe(Optional.empty())).contains("could not be read");
    }

    @Test
    void theWarningCarriesTheDistancesTheRetentionAndTheProjectedCatchUp() {
        final RecoveryCost cost = cost(processed(60, "RCV000"), Optional.of(new JournalRates.Rates(100, 300)));

        assertThat(cost.retainedEntriesSkippedByLatest()).contains(BigInteger.valueOf(3_600));
        // 3 600 entries to re-read, closing at 200/s
        assertThat(cost.describe()).contains(
                "lost position RCVLIB/RCV000 offset 60",
                "40 entries between the lost position and the earliest available receiver are unrecoverable",
                "earliest re-reads 3600 entries, catch-up 18s at the last observed rates (read 300/s, production 100/s)",
                "latest skips 3600 retained entries",
                "retention=60.0min");
    }

    @Test
    void latestSkipsOnlyWhatComesAfterTheLostPosition() {
        // a lost position inside the retained range (a stale offset that no longer matches its receiver)
        assertThat(cost(processed(3_000, "RCV002"), Optional.empty()).retainedEntriesSkippedByLatest())
                .contains(BigInteger.valueOf(700));
    }

    @Test
    void aConnectorThatWasAtTheHeadProjectsNoCatchUp() {
        // at the head the read rate is the production rate, which says nothing about how fast it could read
        assertThat(cost(processed(60, "RCV000"), Optional.of(new JournalRates.Rates(100, 100))).earliestCatchUp())
                .startsWith("not projected: the last observed read rate (100/s) did not exceed production (100/s)");
        assertThat(cost(processed(60, "RCV000"), Optional.empty()).earliestCatchUp())
                .isEqualTo("not projected: no read rate observed yet");
    }
}
