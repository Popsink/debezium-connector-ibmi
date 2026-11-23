/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;

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
     * A journal recreated, or its sequence reset, numbers the chain below the lost position: the arithmetic means
     * nothing then, and the warning says it could not measure rather than claiming either answer.
     */
    @Test
    void aLostPositionPastTheChainsHeadIsNotMeasured() {
        final RecoveryCost cost = cost(processed(50_000, "OLDRCV"), Optional.empty());

        assertThat(cost.missingEntries()).isEmpty();
        assertThat(cost.gapStatement()).doesNotContain("unrecoverable").contains("could not be measured", "past the chain's head 3700");
        assertThat(cost.retainedEntriesSkippedByLatest()).as("nothing places it in the chain").isEmpty();
    }

    /**
     * A position reported lost while its sequence number is still inside the chain - #79's false positive, the
     * incident behind issue #87 - is said to look retained, not left as an open question.
     */
    @Test
    void aLostPositionStillInsideTheChainIsSaidToBeRetained() {
        final RecoveryCost cost = cost(processed(2_000, "RCV002"), Optional.empty());

        assertThat(cost.missingEntries()).isEmpty();
        assertThat(cost.gapStatement()).doesNotContain("unrecoverable", "could not be measured")
                .contains("no entry appears missing", "2000 still falls inside the retained chain (101 to 3700)");
    }

    @Test
    void anUnreadableChainIsNotMeasuredEither() {
        assertThat(RecoveryCost.gapStatement(Optional.empty())).doesNotContain("unrecoverable").contains("could not be read");
        assertThat(RecoveryCost.describe(Optional.empty())).contains("could not be read");
    }

    @Test
    void theWarningCarriesTheDistancesTheRetentionAndTheProjectedCatchUp() {
        final RecoveryCost cost = cost(processed(60, "RCV000"), Optional.of(new JournalRates.Rates(100, 300, true)));

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

    /**
     * 'latest' skips the whole retained chain when the lost position comes before it, counted receiver by receiver:
     * sequence arithmetic over a chain whose sequence was reset part-way would come out negative.
     */
    @Test
    void latestSkipsTheWholeChainAcrossASequenceReset() {
        final ReceiverChain reset = ReceiverChain.of(List.of(
                ReceiverChainTest.receiver("RCV001", ReceiverChainTest.NOW.minus(Duration.ofMinutes(60)), 5_001, 6_000,
                        JournalStatus.OnlineSavedDetached),
                ReceiverChainTest.receiver("RCV002", ReceiverChainTest.NOW.minus(Duration.ofMinutes(30)), 1, 500, JournalStatus.Attached)),
                ReceiverChainTest.NOW);

        final RecoveryCost cost = new RecoveryCost(processed(4_000, "RCV000"), reset, Optional.empty());

        assertThat(cost.retainedEntriesSkippedByLatest()).contains(BigInteger.valueOf(1_500));
        assertThat(cost.missingEntries()).contains(BigInteger.valueOf(1_000));
    }

    @Test
    void aConnectorThatWasAtTheHeadProjectsNoCatchUp() {
        // at the head the read rate is the production rate, which says nothing about how fast it could read
        assertThat(cost(processed(60, "RCV000"), Optional.of(new JournalRates.Rates(100, 100, false))).earliestCatchUp())
                .startsWith("not projected: when last measured the connector was keeping up (read 100/s, production 100/s)");
        assertThat(cost(processed(60, "RCV000"), Optional.empty()).earliestCatchUp())
                .isEqualTo("not projected: no read rate observed yet");
    }

    /**
     * A connector at the head that drained a small backlog in the interval shows a few entries per second of
     * closing rate; projecting a 3 600-entry re-read from 3/s would say 20 minutes, from noise.
     */
    @Test
    void aSmallClosingRateAtTheHeadProjectsNothing() {
        assertThat(cost(processed(60, "RCV000"), Optional.of(new JournalRates.Rates(100, 103, false))).earliestCatchUp())
                .startsWith("not projected");
    }

    /** Reading full buffers and still not closing: the connector cannot catch up, and that is stated. */
    @Test
    void aSaturatedReaderThatDoesNotCloseNeverCatchesUp() {
        assertThat(cost(processed(60, "RCV000"), Optional.of(new JournalRates.Rates(300, 200, true))).earliestCatchUp())
                .startsWith("never at the last observed rates: reading full buffers, the connector read 200/s against 300/s produced");
    }
}
