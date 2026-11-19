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
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;

/**
 * The receiver chain summary behind the journal regime line and the recovery warning (issue #87): the figures an
 * operator used to reconstruct by hand from the receiver list a failure dumps.
 */
class ReceiverChainTest {

    static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    static DetailedJournalReceiver receiver(String name, Instant attached, long start, long end, JournalStatus status) {
        return new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver(name, "RCVLIB"), Date.from(attached), status, Optional.empty()),
                BigInteger.valueOf(start), BigInteger.valueOf(end), Optional.empty(), 0, end - start + 1);
    }

    /** Three receivers rolled 10 then 20 minutes apart, the last attached 30 minutes before NOW. */
    static List<DetailedJournalReceiver> chain() {
        return List.of(
                receiver("RCV001", NOW.minus(Duration.ofMinutes(60)), 101, 1_100, JournalStatus.OnlineSavedDetached),
                receiver("RCV002", NOW.minus(Duration.ofMinutes(50)), 1_101, 3_100, JournalStatus.OnlineSavedDetached),
                receiver("RCV003", NOW.minus(Duration.ofMinutes(30)), 3_101, 3_700, JournalStatus.Attached));
    }

    @Test
    void summarisesRetentionEntriesAndRollCadence() {
        final ReceiverChain chain = ReceiverChain.of(chain(), NOW);

        assertThat(chain.receivers()).isEqualTo(3);
        assertThat(chain.oldest()).isEqualTo(new JournalReceiver("RCV001", "RCVLIB"));
        assertThat(chain.attached()).isEqualTo(new JournalReceiver("RCV003", "RCVLIB"));
        assertThat(chain.oldestStart()).isEqualTo(BigInteger.valueOf(101));
        assertThat(chain.head()).isEqualTo(BigInteger.valueOf(3_700));
        assertThat(chain.retainedEntries()).isEqualTo(BigInteger.valueOf(3_600));
        assertThat(chain.retention()).contains(Duration.ofHours(1));
        // rolls of 10 and 20 minutes
        assertThat(chain.medianRoll()).contains(Duration.ofMinutes(15));
        assertThat(chain.averageProductionPerSecond()).contains(1L);
    }

    /**
     * A journal whose sequence was reset (CHGJRN SEQOPT(*RESET)) numbers its newer receivers from 1 again: the
     * entries retained are what each receiver holds, not the distance between the first and the last number.
     */
    @Test
    void countsRetainedEntriesReceiverByReceiverAcrossASequenceReset() {
        final ReceiverChain chain = ReceiverChain.of(List.of(
                receiver("RCV001", NOW.minus(Duration.ofMinutes(60)), 5_001, 6_000, JournalStatus.OnlineSavedDetached),
                receiver("RCV002", NOW.minus(Duration.ofMinutes(30)), 1, 500, JournalStatus.Attached)), NOW);

        assertThat(chain.retainedEntries()).isEqualTo(BigInteger.valueOf(1_500));
    }

    @Test
    void anEmptyReceiverAddsNothing() {
        final ReceiverChain chain = ReceiverChain.of(List.of(
                receiver("RCV001", NOW.minus(Duration.ofMinutes(60)), 1, 1_000, JournalStatus.OnlineSavedDetached),
                // freshly attached, nothing written yet: the last sequence reported is below the first, possibly 0
                receiver("RCV002", NOW.minus(Duration.ofMinutes(1)), 1_001, 0, JournalStatus.Attached)), NOW);

        assertThat(chain.retainedEntries()).isEqualTo(BigInteger.valueOf(1_000));
    }

    @Test
    void theRegimeLineCarriesRetentionAndRetainedEntries() {
        assertThat(ReceiverChain.of(chain(), NOW).describe())
                .contains("receivers=3", "retention=60.0min", "retainedEntries=3600", "averageProduction=1/s", "medianRoll=15.0min",
                        "oldest=RCVLIB/RCV001", "attached=RCVLIB/RCV003");
    }

    @Test
    void anEmptyChainSaysSo() {
        final ReceiverChain chain = ReceiverChain.of(List.of(), NOW);

        assertThat(chain.receivers()).isZero();
        assertThat(chain.describe()).isEqualTo("receivers=0");
    }

    @Test
    void durationsReadInOneUnit() {
        assertThat(ReceiverChain.formatDuration(Duration.ofSeconds(45))).isEqualTo("45s");
        assertThat(ReceiverChain.formatDuration(Duration.ofSeconds(438))).isEqualTo("7.3min");
        assertThat(ReceiverChain.formatDuration(Duration.ofMinutes(3882))).isEqualTo("64.7h");
    }
}
