/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.sql.SQLNonTransientConnectionException;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.debezium.DebeziumException;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.pipeline.DataChangeEvent;

/**
 * The streaming loop gives up after a bounded number of retries, so what the error handler makes of the
 * exception it throws decides whether the connector restarts or the task stops. A lost connection has to
 * restart; a journal position that no longer exists has to stop.
 */
class As400ErrorHandlerTest {

    private static As400ConnectorConfig config() {
        return new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .build());
    }

    @SuppressWarnings("unchecked")
    private static RuntimeException reportedFor(Throwable producerThrowable) {
        final ChangeEventQueue<DataChangeEvent> queue = mock(ChangeEventQueue.class);
        new As400ErrorHandler(config(), queue, null).setProducerThrowable(producerThrowable);

        final ArgumentCaptor<RuntimeException> reported = ArgumentCaptor.forClass(RuntimeException.class);
        verify(queue).producerException(reported.capture());
        return reported.getValue();
    }

    @Test
    void jt400ConnectionFailureIsRetriable() {
        assertThat(reportedFor(new DebeziumException("Failed to process offset X after 20 retries",
                new SQLNonTransientConnectionException("connection reset"))))
                .isInstanceOf(RetriableException.class);
    }

    @Test
    void ioFailureIsRetriable() {
        assertThat(reportedFor(new DebeziumException("Failed to process offset X after 20 retries",
                new IOException("Failed to reconnect"))))
                .isInstanceOf(RetriableException.class);
    }

    @Test
    void unavailableJournalPositionIsNotRetriable() {
        assertThat(reportedFor(new OffsetNoLongerAvailableException("journal position X was pruned")))
                .isInstanceOf(ConnectException.class)
                .isNotInstanceOf(RetriableException.class);
    }
}
