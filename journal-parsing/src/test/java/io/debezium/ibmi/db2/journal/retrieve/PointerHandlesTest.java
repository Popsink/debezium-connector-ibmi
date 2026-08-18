/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PointerHandlesTest {

    /** Entries of a table with no lob column carry no handle, and must not move the budget at all. */
    @Test
    void entriesWithoutAHandleCostNothing() {
        final PointerHandles handles = new PointerHandles(10);

        for (int i = 0; i < 1000; i++) {
            handles.record(0);
        }

        assertEquals(0, handles.outstanding());
        assertFalse(handles.shouldCycle(), "a journal with no lob tables should never recycle the job");
    }

    @Test
    void countsOneHandlePerLobBearingEntry() {
        final PointerHandles handles = new PointerHandles(10);

        handles.record(13);
        handles.record(14);
        handles.record(0);
        handles.record(15);

        assertEquals(3, handles.outstanding());
    }

    @Test
    void asksToCycleOnlyOnceTheThresholdIsReached() {
        final PointerHandles handles = new PointerHandles(3);

        handles.record(1);
        handles.record(2);
        assertFalse(handles.shouldCycle());

        handles.record(3);
        assertTrue(handles.shouldCycle());
    }

    /**
     * Ending the job frees every handle it held. Nothing tells the budget that directly - it finds out
     * when the next retrieve reports a different job, which is what makes an ineffective recycle
     * visible rather than assumed away.
     */
    @Test
    void recyclingTheJobIsWhatEmptiesTheBudget() {
        final PointerHandles handles = new PointerHandles(2);
        handles.observeJob("QZRCSRVS/QUSER/111111");
        handles.record(1);
        handles.record(2);
        assertTrue(handles.shouldCycle());

        // the disconnect took effect, so the next retrieve runs somewhere new
        handles.observeJob("QZRCSRVS/QUSER/222222");

        assertEquals(0, handles.outstanding());
        assertFalse(handles.shouldCycle());
        assertEquals(2, handles.jobChanges());
    }

    /** A disconnect that did not take effect leaves the handles counted, so it is retried. */
    @Test
    void anIneffectiveRecycleLeavesTheBudgetSpent() {
        final PointerHandles handles = new PointerHandles(2);
        handles.observeJob("QZRCSRVS/QUSER/111111");
        handles.record(1);
        handles.record(2);

        handles.observeJob("QZRCSRVS/QUSER/111111");

        assertTrue(handles.shouldCycle(), "the same job still holds them, so it should be tried again");
    }

    /**
     * Handles belong to the job that allocated them and die with it. The job ends for reasons the budget
     * does not initiate - the watchdog cancelling a hung retrieve, a reconnect after a dropped
     * connection - and after any of those the handles are gone whether or not anyone said so. Counting
     * against the job identity makes the budget correct itself instead of carrying a phantom debt.
     */
    @Test
    void aNewJobStartsWithAnEmptyBudget() {
        final PointerHandles handles = new PointerHandles(10);
        handles.observeJob("QZRCSRVS/QUSER/123456");
        handles.record(1);
        handles.record(2);
        assertEquals(2, handles.outstanding());

        // the watchdog killed that job, or the connection dropped and a new one now serves us
        handles.observeJob("QZRCSRVS/QUSER/999999");

        assertEquals(0, handles.outstanding(), "the old job's handles died with it");
    }

    /** Seeing the same job again must not wipe the count that job is still holding. */
    @Test
    void theSameJobKeepsItsBudget() {
        final PointerHandles handles = new PointerHandles(10);
        handles.observeJob("QZRCSRVS/QUSER/123456");
        handles.record(1);
        handles.observeJob("QZRCSRVS/QUSER/123456");
        handles.record(2);

        assertEquals(2, handles.outstanding());
    }

    /** A nonsensical threshold would either recycle constantly or never; fall back to the default. */
    @Test
    void aNonPositiveThresholdFallsBackToTheDefault() {
        assertEquals(PointerHandles.DEFAULT_THRESHOLD, new PointerHandles(0).threshold());
        assertEquals(PointerHandles.DEFAULT_THRESHOLD, new PointerHandles(-1).threshold());
    }
}
