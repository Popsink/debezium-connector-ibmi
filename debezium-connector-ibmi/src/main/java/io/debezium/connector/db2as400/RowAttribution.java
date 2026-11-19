/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.util.HashSet;
import java.util.Set;

import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.relational.TableId;

/**
 * The row a value being converted belongs to. Debezium converts a row's values inside the change record
 * emitter, handing the converter nothing but the column; the emitter binds this to the converting thread
 * for the duration, so a converter that alters a value can say which row it altered (#58), the way a
 * skipped journal entry already says which one it skipped (#31).
 * <p>
 * Taken from the journal entry header rather than the offset context: while an entry is being processed
 * the context still holds the previous entry's position, and a record buffered in a transaction is only
 * emitted once its commit arrives.
 */
public final class RowAttribution {

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private final TableId table;
    private final String position;

    private RowAttribution(TableId table, String position) {
        this.table = table;
        this.position = position;
    }

    /** A row read from the journal: its offset, RRN, member, entry and commit cycle. */
    public static RowAttribution ofJournalEntry(TableId table, EntryHeader eheader) {
        final StringBuilder position = new StringBuilder()
                .append("offset ").append(eheader.getSequenceNumber())
                .append(", RRN ").append(eheader.getRelativeRecordNumber());
        final String member = eheader.getMember();
        if (member != null && !member.isEmpty()) {
            position.append(", member ").append(member);
        }
        position.append(", journal code ").append(eheader.getJournalCode())
                .append(", entry type ").append(eheader.getJournalEntryType())
                .append(", commit cycle ").append(eheader.getCommitCycle());
        return new RowAttribution(table, position.toString());
    }

    /** A row read by a snapshot query, which has no journal position. */
    public static RowAttribution ofSnapshot(TableId table) {
        return new RowAttribution(table, "read by snapshot");
    }

    /** A row whose position is not known: at least the table is. */
    public static RowAttribution ofTable(TableId table) {
        return new RowAttribution(table, null);
    }

    public TableId table() {
        return table;
    }

    /** Where in the source the row was read, or {@code null} when that is not known. */
    public String position() {
        return position;
    }

    @Override
    public String toString() {
        final String name = (table == null) ? "unknown table" : "table " + table;
        return (position == null) ? name : name + ", " + position;
    }

    /** Makes this the row being converted on this thread until the binding is closed. */
    public Binding bind() {
        final Binding binding = new Binding(this, CURRENT.get());
        CURRENT.set(binding);
        return binding;
    }

    /** The row being converted on this thread, or {@code null} outside any emitter. */
    static Binding current() {
        return CURRENT.get();
    }

    /** One row's conversion: an emitter converts the key and the value, the old and the new image. */
    public static final class Binding implements AutoCloseable {
        private final RowAttribution row;
        private final Binding previous;
        private final Set<String> altered = new HashSet<>();

        private Binding(RowAttribution row, Binding previous) {
            this.row = row;
            this.previous = previous;
        }

        RowAttribution row() {
            return row;
        }

        /**
         * Whether this is the first value of this column the row has altered: a column that is part of the
         * key is converted once for the key and once for the value, and an update converts both images.
         */
        boolean firstAlterationOf(String column) {
            return altered.add(column);
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        }
    }
}
