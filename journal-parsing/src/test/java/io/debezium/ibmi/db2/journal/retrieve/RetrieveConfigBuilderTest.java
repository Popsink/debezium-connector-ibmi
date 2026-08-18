/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The pointer handle threshold travels from connector configuration into {@link PointerHandles}
 * through the builder and {@link RetrieveConfig}. Both ends are covered elsewhere - that the setting
 * survives Debezium's {@code Configuration}, and that the budget honours whatever it is given - but a
 * value dropped in the middle would silently leave every deployment on the default.
 */
class RetrieveConfigBuilderTest {

    @Test
    void carriesThePointerHandleThresholdThrough() {
        assertEquals(1234L, new RetrieveConfigBuilder().withPointerHandleThreshold(1234L)
                .build().pointerHandleThreshold());
    }

    @Test
    void defaultsWhenTheThresholdIsNotSet() {
        assertEquals(PointerHandles.DEFAULT_THRESHOLD, new RetrieveConfigBuilder().build().pointerHandleThreshold());
    }

    /**
     * An unset connector property arrives as null, and a nonsensical one would either recycle the
     * connection on every poll or never; both fall back rather than being honoured.
     */
    @Test
    void ignoresAnAbsentOrNonsensicalThreshold() {
        assertEquals(PointerHandles.DEFAULT_THRESHOLD,
                new RetrieveConfigBuilder().withPointerHandleThreshold(null).build().pointerHandleThreshold());
        assertEquals(PointerHandles.DEFAULT_THRESHOLD,
                new RetrieveConfigBuilder().withPointerHandleThreshold(0L).build().pointerHandleThreshold());
        assertEquals(PointerHandles.DEFAULT_THRESHOLD,
                new RetrieveConfigBuilder().withPointerHandleThreshold(-5L).build().pointerHandleThreshold());
    }
}
