/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.ToLongFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.Field;
import io.debezium.util.Clock;

/**
 * The one retry policy of the streaming loop, bounded by time rather than by count; the README's "Streaming
 * retries" section describes it for operators.
 *
 * <p>A window opens at the first failure and closes at the next successful poll. Every failure is measured
 * from the start of the window against the budget of its own {@link Kind}, so a stream that keeps failing -
 * even alternating between kinds - stops by the largest budget, with a {@link NotRetriableException}.</p>
 */
final class RetryWindow {

    private static final Logger log = LoggerFactory.getLogger(RetryWindow.class);

    /** Backoff grows from the poll interval up to this multiple of it, then stays there. */
    private static final long MAX_BACKOFF_MULTIPLIER = 8;
    /** How often a failure that has not cleared yet is reported again. */
    static final long REPORT_INTERVAL_MS = 300_000;

    enum Kind {
        /** CPF7007: a nightly batch job holding a journaled member; clears on its own. */
        MEMBER_LOCKED("a journaled member is locked by another job", Duration.ofHours(1),
                As400ConnectorConfig.JOURNAL_LOCK_TIMEOUT, As400ConnectorConfig::getJournalLockTimeout),
        /** The IBM i is unreachable: network, IPL, a full-system save in restricted state. */
        CONNECTION_LOST("the connection to the IBM i is lost", Duration.ofMinutes(15),
                As400ConnectorConfig.CONNECTION_RETRY_TIMEOUT, As400ConnectorConfig::getConnectionRetryTimeout),
        /** A range the server refused, or a position not found, while the receiver chain says it is still there. */
        POSITION_UNREADABLE("the journal position cannot be read", Duration.ofMinutes(5), null, config -> Duration.ofMinutes(15).toMillis()),
        /** Anything else: no reason to expect it to clear, so it gets the shortest budget. */
        UNKNOWN("the journal cannot be read", Duration.ZERO, null, config -> Duration.ofMinutes(5).toMillis());

        private final String description;
        private final Duration errorAfter;
        /** The setting that bounds it, or null when the budget is fixed. */
        private final Field setting;
        private final ToLongFunction<As400ConnectorConfig> giveUpAfterMs;

        Kind(String description, Duration errorAfter, Field setting, ToLongFunction<As400ConnectorConfig> giveUpAfterMs) {
            this.description = description;
            this.errorAfter = errorAfter;
            this.setting = setting;
            this.giveUpAfterMs = giveUpAfterMs;
        }
    }

    private final Map<Kind, Duration> giveUpAfter = new EnumMap<>(Kind.class);
    private final Duration pollInterval;
    private final Clock clock;

    /** The kind of the last failure, null while the window is closed. */
    private Kind lastKind;
    private long openedAtMs;
    private int attempts;
    private long nextReportMs;

    RetryWindow(As400ConnectorConfig config, Clock clock) {
        for (Kind kind : Kind.values()) {
            giveUpAfter.put(kind, Duration.ofMillis(kind.giveUpAfterMs.applyAsLong(config)));
        }
        this.pollInterval = config.getPollInterval();
        this.clock = clock;
    }

    /**
     * Records a failed poll and returns how long to wait before the next one.
     *
     * @throws NotRetriableException once the failure has outlived the budget of its kind
     */
    Duration failed(Kind kind, Object position, Throwable cause) {
        final long now = clock.currentTimeInMillis();
        if (lastKind == null) {
            openedAtMs = now;
        }
        final long failingForMs = now - openedAtMs;
        final Duration giveUp = giveUpAfter.get(kind);
        if (failingForMs >= giveUp.toMillis()) {
            throw new NotRetriableException(String.format("Gave up at offset %s: %s, and the stream has been failing for %ds, "
                    + "longer than the %ds it is retried for%s: %s", position, kind.description, failingForMs / 1000,
                    giveUp.toSeconds(), kind.setting == null ? "" : " (" + kind.setting.name() + ")", cause.getMessage()), cause);
        }
        final boolean asError = failingForMs >= kind.errorAfter.toMillis();
        if (kind != lastKind) {
            // the first failure of its kind carries the stack trace; the reports after it only the message
            nextReportMs = now + REPORT_INTERVAL_MS;
            log(asError, "{} at offset {}, retrying for up to {}s", kind.description, position, giveUp.toSeconds(), cause);
        }
        else if (now >= nextReportMs) {
            nextReportMs = now + REPORT_INTERVAL_MS;
            log(asError, "{} at offset {}: failing for {}s, retrying for up to {}s: {}", kind.description, position,
                    failingForMs / 1000, giveUp.toSeconds(), cause.getMessage());
        }
        lastKind = kind;
        final Duration backoff = backoff(attempts++);
        log.debug("retrying offset {} in {}ms, attempt {}", position, backoff.toMillis(), attempts);
        return backoff;
    }

    /** Closes the window, if one is open. */
    void succeeded(Object position) {
        if (lastKind == null) {
            return;
        }
        log.info("recovered at offset {} after {}s and {} retries ({})", position,
                (clock.currentTimeInMillis() - openedAtMs) / 1000, attempts, lastKind.description);
        lastKind = null;
        attempts = 0;
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
