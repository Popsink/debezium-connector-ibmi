/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Rates from successive diagnostics samples (issue #87): "will it catch up" readable without a script.
 */
class JournalRatesTest {

    private static BigInteger n(long value) {
        return BigInteger.valueOf(value);
    }

    @Test
    void theFirstSampleMeasuresNothing() {
        assertThat(new JournalRates().record(0, n(1_000), n(500), true)).isEmpty();
    }

    @Test
    void twoSamplesGiveProductionReadAndClosingRates() {
        final JournalRates rates = new JournalRates();
        rates.record(0, n(1_000), n(500), true);

        // 10 s later: the head moved 1 000 entries, the connector 3 000
        final JournalRates.Rates r = rates.record(10_000, n(2_000), n(3_500), true).orElseThrow();

        assertThat(r.production()).isEqualTo(100.0);
        assertThat(r.read()).isEqualTo(300.0);
        assertThat(r.closing()).isEqualTo(200.0);
        // 2 000 behind closing at 200/s
        assertThat(r.timeToClose(n(2_000))).contains(Duration.ofSeconds(10));
        assertThat(rates.lastRates()).contains(r);
    }

    @Test
    void aGrowingLagHasNoCatchUpTime() {
        final JournalRates rates = new JournalRates();
        rates.record(0, n(1_000), n(500), true);
        final JournalRates.Rates r = rates.record(10_000, n(3_000), n(1_000), true).orElseThrow();

        assertThat(r.closing()).isEqualTo(-150.0);
        assertThat(r.timeToClose(n(2_000))).isEmpty();
        assertThat(r.describe(n(2_000))).contains("closing -150/s", "the lag is not closing");
    }

    /**
     * An offset reset by a recovery moves the position backwards: that interval measures no read rate, and the
     * next one is measured from the reset rather than from before it.
     */
    @Test
    void anIntervalAcrossAnOffsetResetMeasuresNothing() {
        final JournalRates rates = new JournalRates();
        rates.record(0, n(10_000), n(9_000), true);
        final JournalRates.Rates before = rates.record(10_000, n(11_000), n(10_500), true).orElseThrow();

        assertThat(rates.record(20_000, n(12_000), n(100), true)).isEmpty();
        assertThat(rates.lastRates()).as("the last measured rates survive").contains(before);

        final JournalRates.Rates after = rates.record(30_000, n(13_000), n(5_100), true).orElseThrow();
        assertThat(after.read()).isEqualTo(500.0);
    }

    @Test
    void theDiagnosticsLineCarriesTheRatesAndTheCatchUpTime() {
        final JournalRates rates = new JournalRates();
        rates.record(0, n(1_000), n(500), true);
        final JournalRates.Rates r = rates.record(10_000, n(2_000), n(3_500), true).orElseThrow();

        assertThat(r.describe(n(2_000 * 60))).isEqualTo("journal production 100/s, read 300/s, closing 200/s, catch-up 10.0min");
        assertThat(r.describe(n(0))).endsWith("catch-up none needed");
    }

    /**
     * An 'earliest' reset leaves a blank position until the first entry is read. Taken as a starting point it
     * would credit the connector with reading the whole journal up to where it resumed in one interval.
     */
    @Test
    void aSampleWithNoPositionIsNoStartingPoint() {
        final JournalRates rates = new JournalRates();
        rates.record(0, n(10_000), n(9_000), true);
        final JournalRates.Rates before = rates.record(10_000, n(11_000), n(10_500), true).orElseThrow();

        assertThat(rates.record(20_000, n(12_000), null, true)).isEmpty();
        assertThat(rates.record(30_000, n(13_000), n(5_000), true)).as("measured from the blank position").isEmpty();
        assertThat(rates.lastRates()).contains(before);

        assertThat(rates.record(40_000, n(14_000), n(6_000), true).orElseThrow().read()).isEqualTo(100.0);
    }

    /** Only an interval read with full buffers at both ends shows how fast the connector can read. */
    @Test
    void anIntervalIsSaturatedOnlyWhenBothSamplesReadAFullBuffer() {
        final JournalRates rates = new JournalRates();
        rates.record(0, n(1_000), n(500), false);
        assertThat(rates.record(10_000, n(2_000), n(1_500), true).orElseThrow().saturated()).isFalse();
        assertThat(rates.record(20_000, n(3_000), n(2_500), true).orElseThrow().saturated()).isTrue();
        assertThat(rates.record(30_000, n(4_000), n(3_500), false).orElseThrow().saturated()).isFalse();
    }
}
