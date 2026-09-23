/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task-scoped, thread-safe view of "a snapshot is currently running", shared by
 * {@link As400SnapshotChangeEventSource} (which publishes it) and the streaming side's
 * {@link WatchDog} (which consumes it).
 * <p>
 * The streaming thread pauses for an ad-hoc blocking snapshot on the coordinator's request, and both
 * sides of that handshake then legitimately read "paused" for as long as the snapshot takes - which
 * can be hours. That makes elapsed time alone useless for telling a running snapshot apart from a
 * wedged handshake (issue #74), so the watchdog needs this second signal: paused with no snapshot
 * running is never legitimate for long.
 * <p>
 * A counter rather than a flag, so overlapping snapshots (a blocking snapshot requested while
 * another snapshot is still finishing) cannot clear the signal early.
 */
public class SnapshotActivity {

    private final AtomicInteger running = new AtomicInteger();

    public void snapshotStarted() {
        running.incrementAndGet();
    }

    public void snapshotFinished() {
        running.decrementAndGet();
    }

    public boolean isSnapshotRunning() {
        return running.get() > 0;
    }
}
