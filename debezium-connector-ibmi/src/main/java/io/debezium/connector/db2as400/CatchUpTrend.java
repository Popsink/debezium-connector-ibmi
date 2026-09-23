/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.math.BigInteger;

/**
 * Counts consecutive diagnostics samples in which the lag grew while every retrieve came back with a
 * full buffer: reading as fast as the buffer and link allow and still losing ground (issue #68), on
 * the way to losing the journal (issue #30).
 */
public class CatchUpTrend {

    /** samples are minutes apart, enough to rule out a burst */
    public static final int WARN_AFTER = 3;

    private BigInteger lastBehind;
    private int samples;

    /** @return consecutive samples, this one included, in which the lag grew with a full buffer */
    public int record(BigInteger behind, boolean bufferFull) {
        if (bufferFull && lastBehind != null && behind.compareTo(lastBehind) > 0) {
            samples++;
        }
        else {
            samples = 0;
        }
        lastBehind = behind;
        return samples;
    }

    public int samples() {
        return samples;
    }
}
