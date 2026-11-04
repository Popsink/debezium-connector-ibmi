/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.util.Clock;

/**
 * The one retry policy of the streaming loop: how long each kind of failure is retried, when waiting for it
 * becomes an ERROR, and when the loop gives up.
 *
 * <p>Retries are bounded by time, not by count. A window opens at the first failure and closes at the next
 * successful poll; every failure is measured from the start of the window against the budget of its own
 * {@link Kind}. Each budget is finite, so a stream that keeps failing - even alternating between kinds -
 * stops by the largest one: a permanent failure that looks transient cannot be retried forever.</p>
 *
 * <p>Waiting is reported at WARN when the window opens (ERROR for a kind with no grace period), again
 * every {@value #REPORT_INTERVAL_MS} ms, at ERROR once the wait passes the kind's {@link Budget#errorAfter},
 * and at INFO when the stream recovers. Past {@link Budget#giveUpAfter} a {@link NotRetriableException}
 * stops the task, and the orchestrator restarts it or not.</p>
 */
final class RetryWindow {

    private static final Logger log = LoggerFactory.getLogger(RetryWindow.class);

    /** Backoff grows from the poll interval up to this multiple of it, then stays there. */
    private static final long MAX_BACKOFF_MULTIPLIER = 8;
    /** How often a failure that has not cleared yet is reported again. */
    static final long REPORT_INTERVAL_MS = 300_000;

    enum Kind {
        /** CPF7007: a nightly batch job holding a journaled member; clears on its own. */
        MEMBER_LOCKED("a journaled member is locked by another job", As400ConnectorConfig.JOURNAL_LOCK_TIMEOUT.name()),
        /** The IBM i is unreachable: network, IPL, a full-system save in restricted state. */
        CONNECTION_LOST("the connection to the IBM i is lost", As400ConnectorConfig.CONNECTION_RETRY_TIMEOUT.name()),
        /** A range the server refused, or a position not found, while the receiver chain says it is still there. */
        POSITION_UNREADABLE("the journal position cannot be read", null),
        /** Anything else: no reason to expect it to clear, so it gets the shortest budget. */
        UNKNOWN("the journal cannot be read", null);

        private final String description;
        /** The setting that bounds it, or null when the budget is fixed. */
        private final String setting;

        Kind(String description, String setting) {
            this.description = description;
            this.setting = setting;
        }
    }

    record Budget(Duration errorAfter, Duration giveUpAfter) {
    }

    static Map<Kind, Budget> budgets(As400ConnectorConfig config) {
        final Map<Kind, Budget> budgets = new EnumMap<>(Kind.class);
        budgets.put(Kind.MEMBER_LOCKED, new Budget(Duration.ofHours(1), Duration.ofMillis(config.getJournalLockTimeout())));
        budgets.put(Kind.CONNECTION_LOST, new Budget(Duration.ofMinutes(15), Duration.ofMillis(config.getConnectionRetryTimeout())));
        budgets.put(Kind.POSITION_UNREADABLE, new Budget(Duration.ofMinutes(5), Duration.ofMinutes(15)));
        budgets.put(Kind.UNKNOWN, new Budget(Duration.ZERO, Duration.ofMinutes(5)));
        return budgets;
    }

    private final Map<Kind, Budget> budgets;
    private final Duration pollInterval;
    private final Clock clock;

    private long openedAtMs = -1;
    private int attempts;
    private long nextReportMs;
    private Kind lastKind;

    RetryWindow(Map<Kind, Budget> budgets, Duration pollInterval, Clock clock) {
        this.budgets = budgets;
        this.pollInterval = pollInterval;
        this.clock = clock;
    }

    /**
     * Records a failed poll and returns how long to wait before the next one.
     *
     * @throws NotRetriableException once the failure has outlived the budget of its kind
     */
    Duration failed(Kind kind, Object position, Throwable cause) {
        final long now = clock.currentTimeInMillis();
        final Budget budget = budgets.get(kind);
        final boolean opening = openedAtMs < 0;
        if (opening) {
            openedAtMs = now;
            attempts = 0;
        }
        final long failingForMs = now - openedAtMs;
        if (failingForMs >= budget.giveUpAfter().toMillis()) {
            throw new NotRetriableException(String.format("Gave up at offset %s: %s, and the stream has been failing for %ds, "
                    + "longer than the %ds it is retried for%s: %s", position, kind.description, failingForMs / 1000,
                    budget.giveUpAfter().toSeconds(), kind.setting == null ? "" : " (" + kind.setting + ")",
                    cause.getMessage()), cause);
        }
        final boolean asError = failingForMs >= budget.errorAfter().toMillis();
        if (opening || kind != lastKind) {
            // the first failure of its kind carries the stack trace; the reports after it only the message
            nextReportMs = now + REPORT_INTERVAL_MS;
            log(asError, "{} at offset {}, retrying for up to {}s", kind.description, position, budget.giveUpAfter().toSeconds(), cause);
        }
        else if (now >= nextReportMs) {
            nextReportMs = now + REPORT_INTERVAL_MS;
            log(asError, "{} at offset {}: failing for {}s, retrying for up to {}s: {}", kind.description, position,
                    failingForMs / 1000, budget.giveUpAfter().toSeconds(), cause.getMessage());
        }
        lastKind = kind;
        final Duration backoff = backoff(attempts++);
        log.debug("retrying offset {} in {}ms, attempt {}", position, backoff.toMillis(), attempts);
        return backoff;
    }

    /** Closes the window, if one is open. */
    void succeeded(Object position) {
        if (openedAtMs < 0) {
            return;
        }
        log.info("recovered at offset {} after {}s and {} retries ({})", position,
                (clock.currentTimeInMillis() - openedAtMs) / 1000, attempts, lastKind.description);
        openedAtMs = -1;
        lastKind = null;
    }

    /**
     * {@code pollInterval * 2^attempts}, capped at {@value #MAX_BACKOFF_MULTIPLIER} times the poll interval so
     * the wait stays proportional to how often the connector was asked to poll.
     */
    Duration backoff(int attempts) {
        // 1L << 30 already dwarfs the cap; clamping the shift keeps it out of overflow territory
        final long multiplier = Math.min(1L << Math.min(attempts, 30), MAX_BACKOFF_MULTIPLIER);
        return pollInterval.multipliedBy(multiplier);
    }

    private static void log(boolean asError, String format, Object... args) {
        if (asError) {
            log.error(format, args);
        }
        else {
            log.warn(format, args);
        }
    }
}
