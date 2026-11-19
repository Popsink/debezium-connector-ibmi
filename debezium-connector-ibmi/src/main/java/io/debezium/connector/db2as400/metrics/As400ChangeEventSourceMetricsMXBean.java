/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400.metrics;

import java.util.Map;

import io.debezium.pipeline.metrics.StreamingChangeEventSourceMetricsMXBean;

public interface As400ChangeEventSourceMetricsMXBean extends StreamingChangeEventSourceMetricsMXBean {
    long getJournalBehind();

    long getJournalOffset();

    long getLastProcessedMs();

    /**
     * How many consecutive diagnostics samples saw the journal lag grow while every call came back with a
     * full buffer - see {@code CatchUpTrend}. Zero while the connector is keeping up or gaining ground.
     */
    int getJournalBehindGrowthSamples();

    /**
     * Text values in which control characters were replaced with {@code '?'} since the task started,
     * snapshot included. The row and the column are delivered; only those characters are altered (#58).
     */
    long getSanitisedValues();

    /** {@link #getSanitisedValues()} by {@code LIBRARY.TABLE.COLUMN}. */
    Map<String, Long> getSanitisedValuesByColumn();
}
