/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import io.debezium.connector.db2as400.metrics.As400StreamingChangeEventSourceMetrics;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfo;

/**
 * The journal regime line at task start and the rates in the periodic diagnostics (issue #87).
 */
class As400RpcConnectionRegimeTest {

    @Test
    void aCachingJournalReportsItsCacheWaitAndEffectiveHeadDelay() {
        final String line = As400RpcConnection.describeRegime(new JournalInfo("QSQJRN", "LIB", true), 25_000, 0, 25_000,
                "receivers=424, retention=64.7h");

        assertThat(line).isEqualTo("Journal LIB/QSQJRN: caching=true, head=proven by probe (timer if the probe is refused), "
                + "cacheWait=25000ms, additionalDelay=0ms, effectiveHeadDelay=25000ms, receivers=424, retention=64.7h");
    }

    /** JRNCACHE(*NO): read on the timer, and only the additional delay behind live - structurally immune to #79. */
    @Test
    void aJournalThatDoesNotCacheReportsTheTimer() {
        final String line = As400RpcConnection.describeRegime(new JournalInfo("QSQJRN", "LIB", false), 30_000, 0, 0,
                "receivers=3");

        assertThat(line).contains("caching=false", "head=timer", "cacheWait=30000ms", "effectiveHeadDelay=0ms", "receivers=3");
    }

    @Test
    void ratesArePublishedAsGaugesOnceASampleMeasuresThem() {
        final JournalRates rates = new JournalRates();
        final As400StreamingChangeEventSourceMetrics metrics = mock(As400StreamingChangeEventSourceMetrics.class);

        assertThat(As400RpcConnection.recordRates(rates, metrics, 0, BigInteger.valueOf(3_000), BigInteger.valueOf(500),
                BigInteger.valueOf(2_500), true)).isEqualTo("rates n/a until the next sample");
        verify(metrics, never()).setJournalProductionRate(anyLong());

        final String line = As400RpcConnection.recordRates(rates, metrics, 10_000, BigInteger.valueOf(4_000),
                BigInteger.valueOf(3_500), BigInteger.valueOf(500), true);

        assertThat(line).startsWith("journal production 100/s, read 300/s, closing 200/s");
        verify(metrics).setJournalProductionRate(100);
        verify(metrics).setJournalReadRate(300);
        verify(metrics).setJournalClosingRate(200);
    }

    /** A blank position (an 'earliest' reset not yet read from) publishes nothing and is no baseline. */
    @Test
    void aSampleWithNoPositionPublishesNoRate() {
        final JournalRates rates = new JournalRates();
        final As400StreamingChangeEventSourceMetrics metrics = mock(As400StreamingChangeEventSourceMetrics.class);

        As400RpcConnection.recordRates(rates, metrics, 0, BigInteger.valueOf(3_000), null, BigInteger.valueOf(3_000), true);
        assertThat(As400RpcConnection.recordRates(rates, metrics, 10_000, BigInteger.valueOf(4_000), BigInteger.valueOf(2_000),
                BigInteger.valueOf(2_000), true)).isEqualTo("rates n/a until the next sample");
        verify(metrics, never()).setJournalReadRate(anyLong());
    }
}
