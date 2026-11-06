/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.RetryWindow.Kind;
import io.debezium.util.MockClock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Every failure the streaming loop retries goes through one time-bounded window: WARN while it is young,
 * ERROR once it has lasted, and a {@link NotRetriableException} once it has outlived its budget - never
 * retried forever, even when the kinds of failure keep changing.
 */
class RetryWindowTest {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(5);
    private static final String POSITION = "RCV/82";

    private final MockClock clock = new MockClock();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(RetryWindow.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logs);
    }

    /** The production budgets, with the default timeouts. */
    private final RetryWindow window = new RetryWindow(new As400ConnectorConfig(Configuration.create()
            .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
            .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
            .with(CommonConnectorConfig.POLL_INTERVAL_MS, POLL_INTERVAL.toMillis())
            .build()), clock);

    private void at(Duration elapsed) {
        clock.advanceTo(elapsed.toMillis());
    }

    private List<Level> levels() {
        return logs.list.stream().map(ILoggingEvent::getLevel).filter(l -> l != Level.DEBUG).toList();
    }

    @Test
    void backoffGrowsWithEachRetryThenLevelsOffAtTheCap() {
        assertThat(window.backoff(0)).isEqualTo(POLL_INTERVAL);
        assertThat(window.backoff(1)).isEqualTo(POLL_INTERVAL.multipliedBy(2));
        assertThat(window.backoff(2)).isEqualTo(POLL_INTERVAL.multipliedBy(4));
        assertThat(window.backoff(3)).isEqualTo(POLL_INTERVAL.multipliedBy(8));
        assertThat(window.backoff(4)).isEqualTo(POLL_INTERVAL.multipliedBy(8));
        // and no overflow for a shift that would wrap a long
        assertThat(window.backoff(Integer.MAX_VALUE)).isEqualTo(POLL_INTERVAL.multipliedBy(8));
    }

    @Test
    void aFailureIsRetriedUntilItsBudgetIsSpent() {
        final IOException lost = new IOException("connection reset");
        window.failed(Kind.CONNECTION_LOST, POSITION, lost);
        at(Duration.ofMinutes(119));
        window.failed(Kind.CONNECTION_LOST, POSITION, lost);

        at(Duration.ofHours(2));
        assertThatThrownBy(() -> window.failed(Kind.CONNECTION_LOST, POSITION, lost))
                .isInstanceOf(NotRetriableException.class)
                .hasMessageContaining("the connection to the IBM i is lost")
                .hasMessageContaining(As400ConnectorConfig.CONNECTION_RETRY_TIMEOUT.name())
                .hasMessageContaining("connection reset")
                .hasCause(lost);
    }

    @Test
    void theWaitIsAWarningUntilItHasLastedThenAnError() {
        final IOException lost = new IOException("connection reset");
        window.failed(Kind.CONNECTION_LOST, POSITION, lost);
        // failures between two reports are not logged above DEBUG
        at(Duration.ofMinutes(1));
        window.failed(Kind.CONNECTION_LOST, POSITION, lost);
        at(Duration.ofMinutes(5));
        window.failed(Kind.CONNECTION_LOST, POSITION, lost);
        at(Duration.ofMinutes(15));
        window.failed(Kind.CONNECTION_LOST, POSITION, lost);

        assertThat(levels()).containsExactly(Level.WARN, Level.WARN, Level.ERROR);
    }

    @Test
    void anUnknownFailureIsAnErrorFromTheStart() {
        window.failed(Kind.UNKNOWN, POSITION, new IllegalStateException("unexpected"));

        assertThat(levels()).containsExactly(Level.ERROR);
    }

    /** A permanent failure that keeps changing its face still stops, by the largest budget at the latest. */
    @Test
    void alternatingKindsDoNotRestartTheWindow() {
        final IOException lost = new IOException("connection reset");
        final Exception unknown = new IllegalStateException("unexpected");
        window.failed(Kind.UNKNOWN, POSITION, unknown);
        at(Duration.ofMinutes(4));
        window.failed(Kind.CONNECTION_LOST, POSITION, lost);
        at(Duration.ofMinutes(6));

        // measured from the first failure of the window, not from the last change of kind
        assertThatThrownBy(() -> window.failed(Kind.UNKNOWN, POSITION, unknown)).isInstanceOf(NotRetriableException.class);
    }

    @Test
    void aSuccessfulPollClosesTheWindow() {
        final Exception unknown = new IllegalStateException("unexpected");
        window.failed(Kind.UNKNOWN, POSITION, unknown);
        at(Duration.ofMinutes(4));
        window.succeeded(POSITION);
        at(Duration.ofMinutes(10));

        // ten minutes after the first failure, past its five minute budget: a new window, not the old one
        assertThat(window.failed(Kind.UNKNOWN, POSITION, unknown)).as("a new window starts from the shortest backoff")
                .isEqualTo(POLL_INTERVAL);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().startsWith("recovered at offset"));
    }

    @Test
    void aWindowThatNeverOpenedLogsNothingOnSuccess() {
        window.succeeded(POSITION);

        assertThat(logs.list).isEmpty();
    }
}
