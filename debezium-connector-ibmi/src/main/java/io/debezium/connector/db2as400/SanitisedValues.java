/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.relational.TableId;
import io.debezium.util.Clock;

/**
 * Counts and reports the text values whose control characters were replaced with {@code '?'} (#58).
 * <p>
 * The row and the column are both delivered, only characters inside the value change, so this is not a
 * loss - but a consumer cannot tell a substituted {@code '?'} from a genuine one, so every altered value is
 * counted, per column, and exposed through the streaming metrics. The log is bounded: one WARN the first
 * time a column is altered, naming the row, then at most one summary per {@link #SUMMARY_INTERVAL}; each
 * altered value is logged at DEBUG with its row. A deployment with a systematically mis-decoded column
 * used to log one WARN per value, which pushed everything else out of the container's log.
 * <p>
 * Reports under the {@link As400ValueConverters} logger, as the per-value WARN it replaces did, so a level
 * already set on that logger keeps applying.
 */
public class SanitisedValues {

    private static final Logger log = LoggerFactory.getLogger(As400ValueConverters.class);

    static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    private final AtomicLong total = new AtomicLong();
    private final Map<String, LongAdder> byColumn = new ConcurrentHashMap<>();
    private final Set<String> announced = ConcurrentHashMap.newKeySet();

    private final Object windowLock = new Object();
    private final Map<String, Window> window = new LinkedHashMap<>();
    private long windowStartMs;

    public SanitisedValues(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a value of {@code column} in which {@code replaced} characters were substituted. Within one
     * row, a column is counted once however many times it is converted.
     */
    void record(String column, int replaced) {
        final RowAttribution.Binding binding = RowAttribution.current();
        if (binding != null && !binding.firstAlterationOf(column)) {
            return;
        }
        final RowAttribution row = (binding == null) ? null : binding.row();
        final String key = key(row, column);
        total.incrementAndGet();
        byColumn.computeIfAbsent(key, k -> new LongAdder()).increment();

        // the row itself, not its description: SLF4J formats it only for a line that is actually logged
        final Object where = (row == null) ? "row unknown" : row;
        log.debug("replaced {} control character(s) with '?' in column {} ({})", replaced, column, where);
        if (announced.add(key)) {
            log.warn("replaced {} control character(s) with '?' in column {} ({}). The row and the column are "
                    + "delivered; only those characters are altered, and a consumer cannot tell them from a genuine '?'. "
                    + "Further values altered in {} are counted in the SanitisedValues metric and summarised at most "
                    + "every {}s; each one is logged at DEBUG. A recurring rewrite on a fixed set of columns usually "
                    + "means their CCSID is wrong or they hold binary data",
                    replaced, column, where, key, SUMMARY_INTERVAL.toSeconds());
            return;
        }
        final String summary = held(key, row);
        if (summary != null) {
            log.warn(summary);
        }
    }

    /** Holds a value back for the next summary, and returns the summary once the interval is over. */
    private String held(String key, RowAttribution row) {
        final long now = clock.currentTimeInMillis();
        synchronized (windowLock) {
            if (window.isEmpty()) {
                windowStartMs = now;
            }
            window.computeIfAbsent(key, k -> new Window()).add(row);
            final long elapsedMs = now - windowStartMs;
            if (elapsedMs < SUMMARY_INTERVAL.toMillis()) {
                return null;
            }
            final long values = window.values().stream().mapToLong(w -> w.count).sum();
            final String columns = window.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("; "));
            window.clear();
            return String.format("replaced control characters with '?' in %d more value(s) over the last %ds [%s]; "
                    + "%d value(s) altered since the connector started. Rows and columns are delivered, only those "
                    + "characters are altered; each value is logged at DEBUG",
                    values, Duration.ofMillis(elapsedMs).toSeconds(), columns, total.get());
        }
    }

    private static String key(RowAttribution row, String column) {
        final TableId table = (row == null) ? null : row.table();
        if (table == null) {
            return column;
        }
        return (table.schema() == null ? "" : table.schema() + ".") + table.table() + "." + column;
    }

    /** Values altered since the connector started, all columns together. */
    public long total() {
        return total.get();
    }

    /** Values altered since the connector started, by {@code LIBRARY.TABLE.COLUMN}. */
    public Map<String, Long> byColumn() {
        final Map<String, Long> counts = new TreeMap<>();
        byColumn.forEach((column, count) -> counts.put(column, count.sum()));
        return counts;
    }

    private static final class Window {
        private long count;
        private RowAttribution first;
        private RowAttribution last;

        void add(RowAttribution row) {
            if (count++ == 0) {
                first = row;
            }
            last = row;
        }

        @Override
        public String toString() {
            final String firstAt = (first == null) ? null : first.position();
            if (firstAt == null) {
                return count + " value(s)";
            }
            final String lastAt = (last == null) ? null : last.position();
            return count + " value(s), first at " + firstAt + (count > 1 && lastAt != null ? ", last at " + lastAt : "");
        }
    }
}
