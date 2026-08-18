/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

/**
 * Counts the pointer handles {@code QjoRetrieveJournalEntries} hands out, so they can be freed in bulk
 * by moving to a different host server job rather than one round trip at a time.
 *
 * <p>
 * A journal entry belonging to a table with a LOB column comes back with a handle owning the
 * allocation behind its pointer, and those handles have to be given back: "if the handles are not
 * deleted, the maximum number allowed can be reached, which will prevent further retrieval of journal
 * entries". {@code QjoDeletePointerHandle} takes one handle per call; the manual's bulk alternative is
 * that "the pointer handles will be implicitly deleted when the process that requested the journal
 * entries is ended".
 * </p>
 *
 * <p>
 * This class only counts. Freeing them is the caller's business - see
 * {@code As400RpcConnection.freePointerHandlesIfDue} for how, what it costs, and why it can only
 * happen between polls.
 * </p>
 */
public class PointerHandles {

    /** No handle: the entry holds all its data inline, so nothing was allocated for it. */
    private static final long NONE = 0;

    /**
     * Far below any ceiling that could be reached - retrieval was still working with 650,994 handles
     * outstanding, and with 3 GB of LOB data addressed by them - while still bounding what a
     * constrained system is asked to hold.
     */
    public static final long DEFAULT_THRESHOLD = 50_000;

    private final long threshold;
    /** The job the outstanding handles belong to - see {@link #observeJob}. */
    private String job;
    // read from outside the streaming thread by diagnostics
    private volatile long outstanding;
    private volatile long jobChanges;

    public PointerHandles(long threshold) {
        this.threshold = threshold > 0 ? threshold : DEFAULT_THRESHOLD;
    }

    /**
     * Notes a handle the retrieve allocated. Entries without one cost nothing, which is every entry of
     * every table that has no LOB column.
     */
    public void record(long handle) {
        if (handle != NONE) {
            outstanding++;
        }
    }

    /**
     * Notes which job the retrieve ran in, and starts the count again when that is a different one.
     *
     * <p>
     * Handles die with the job that allocated them, and the job ends for reasons the budget never
     * initiates: the watchdog cancelling a hung retrieve, a dropped connection reconnecting, or the
     * deliberate recycle once this budget is spent. Rather than have each of those remember to reset
     * the count, the budget notices the job changed and corrects itself - which also makes a recycle
     * that did not take effect visible instead of assumed, since the count only clears when the system
     * really is serving us from somewhere new.
     * </p>
     */
    public void observeJob(String job) {
        if (job == null || job.equals(this.job)) {
            return;
        }
        this.job = job;
        outstanding = 0;
        jobChanges++;
    }

    /** Whether enough handles have accumulated to be worth moving to another job for. */
    public boolean shouldCycle() {
        return outstanding >= threshold;
    }

    /** Handles allocated since the job was last cycled. */
    public long outstanding() {
        return outstanding;
    }

    /** The job the outstanding handles belong to, for diagnostics. */
    public String job() {
        return job;
    }

    /** How many times the job serving us has changed, for diagnostics. */
    public long jobChanges() {
        return jobChanges;
    }

    public long threshold() {
        return threshold;
    }
}
