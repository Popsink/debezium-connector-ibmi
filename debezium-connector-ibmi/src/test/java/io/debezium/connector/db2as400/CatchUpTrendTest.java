/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class CatchUpTrendTest {

    private static BigInteger behind(long n) {
        return BigInteger.valueOf(n);
    }

    @Test
    void countsConsecutiveGrowthWithAFullBuffer() {
        final CatchUpTrend trend = new CatchUpTrend();
        assertEquals(0, trend.record(behind(100), true), "nothing to compare the first sample with");
        assertEquals(1, trend.record(behind(150), true));
        assertEquals(2, trend.record(behind(200), true));
        assertEquals(3, trend.record(behind(201), true));
    }

    @Test
    void gainingGroundOrHoldingItStartsOver() {
        final CatchUpTrend trend = new CatchUpTrend();
        trend.record(behind(100), true);
        trend.record(behind(150), true);
        assertEquals(0, trend.record(behind(150), true), "not growing");
        assertEquals(1, trend.record(behind(160), true));
        assertEquals(0, trend.record(behind(120), true), "catching up");
    }

    @Test
    void growthWithRoomLeftInTheBufferIsNotStructural() {
        final CatchUpTrend trend = new CatchUpTrend();
        trend.record(behind(100), true);
        assertEquals(0, trend.record(behind(150), false), "the buffer had room, so the read is not the limit");
        assertEquals(1, trend.record(behind(200), true), "counted from the next full one");
    }
}
