/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per untagged (CCSID 65535) text column, whether it is read as text and in which CCSID, or kept
 * binary - keyed {@code LIB.TABLE.COLUMN} on the long names (Popsink/data-plane#5831).
 *
 * <p>
 * A decision is sticky: it travels in the offset ({@link #serialized()} / {@link #load(String)}) so a
 * restart or a schema cache clear never re-samples a column and flips its Kafka type. One instance per
 * task, shared by the decoder that makes the decisions and the offset that persists them.
 * </p>
 */
public class UntaggedDecisions {
    private static final Logger log = LoggerFactory.getLogger(UntaggedDecisions.class);

    /** The value of a column kept binary; a CCSID is never 0. */
    public static final int HEX = 0;
    private static final String BINARY_TEXT = "binary";
    /**
     * How a binary decision was written before Popsink/data-plane#5989, under a rule one damaged value
     * failed: dropped on load, so the column is sampled once more. A text decision is kept as it was, so
     * no column flips from string back to bytes.
     */
    private static final String STALE_BINARY_TEXT = "hex";

    private final Map<String, Integer> decisions = new ConcurrentHashMap<>();
    /** Rebuilt only when a decision is added: the offset reads it once per record. */
    private volatile String serialized = "";

    public static String key(String schema, String table, String column) {
        return schema + "." + table + "." + column;
    }

    /** @return the CCSID the column is read in, {@link #HEX} when kept binary, empty when undecided */
    public OptionalInt get(String key) {
        final Integer decision = decisions.get(key);
        return (decision == null) ? OptionalInt.empty() : OptionalInt.of(decision);
    }

    /** Records a decision unless the column already has one, which wins. */
    public void putIfAbsent(String key, int ccsidOrHex) {
        if (decisions.putIfAbsent(key, ccsidOrHex) == null) {
            reserialize();
        }
    }

    /** Reads what {@link #serialized()} wrote; a decision already held is kept. */
    public void load(String persisted) {
        if (persisted == null || persisted.isBlank()) {
            return;
        }
        int stale = 0;
        for (final String entry : persisted.split(";")) {
            final int at = entry.lastIndexOf('=');
            if (at <= 0) {
                log.warn("ignoring malformed untagged column decision '{}' in the offset", entry);
                continue;
            }
            final String value = entry.substring(at + 1);
            if (STALE_BINARY_TEXT.equals(value)) {
                stale++;
                continue;
            }
            try {
                putIfAbsent(entry.substring(0, at), BINARY_TEXT.equals(value) ? HEX : Integer.parseInt(value));
            }
            catch (final NumberFormatException e) {
                log.warn("ignoring malformed untagged column decision '{}' in the offset", entry);
            }
        }
        if (stale > 0) {
            log.warn("dropped {} untagged column binary decisions taken before a few damaged values were "
                    + "tolerated: those columns are sampled again, and one now read as text streams as a string",
                    stale);
        }
        log.info("loaded {} untagged column decisions from the offset", decisions.size());
    }

    /** {@code LIB.TABLE.COL=1388;LIB.TABLE.COL2=binary}, sorted; empty when nothing is decided. */
    public String serialized() {
        return serialized;
    }

    private synchronized void reserialize() {
        final StringBuilder out = new StringBuilder();
        for (final Map.Entry<String, Integer> entry : new TreeMap<>(decisions).entrySet()) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(entry.getKey()).append('=')
                    .append(entry.getValue() == HEX ? BINARY_TEXT : entry.getValue().toString());
        }
        serialized = out.toString();
    }
}
