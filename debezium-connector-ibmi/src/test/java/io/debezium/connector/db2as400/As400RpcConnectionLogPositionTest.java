/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400;

import io.debezium.DebeziumException;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400RpcConnection.PositionAvailability;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfoRetrieval;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.exception.JournalReceiverNotFoundException;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;

/**
 * Verifies that {@link As400RpcConnection#checkLogPosition} tells apart a permanently pruned journal
 * receiver from a transient validation failure, instead of collapsing every error into "unavailable".
 */
class As400RpcConnectionLogPositionTest {

    private final AS400 as400 = mock(AS400.class);

    private static As400ConnectorConfig config() {
        return new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .build());
    }

    private static As400OffsetContext offsetWithPosition() {
        return new As400OffsetContext(config(), new JournalProcessedPosition(
                BigInteger.valueOf(82), new JournalReceiver("RECV008", "RCV_LIB"), Instant.ofEpochSecond(123_456L), true));
    }

    @Test
    void existingReceiverIsAvailable() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        when(retrieval.getReceiverDetails(any(), any())).thenReturn(mock(DetailedJournalReceiver.class));

        assertThat(As400RpcConnection.checkLogPosition(retrieval, as400, offsetWithPosition()))
                .isEqualTo(PositionAvailability.AVAILABLE);
    }

    @Test
    void prunedReceiverIsReportedAsPruned() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        when(retrieval.getReceiverDetails(any(), any()))
                .thenThrow(new JournalReceiverNotFoundException("Object RECV008 in library RCV_LIB not found.", "CPF9801"));

        assertThat(As400RpcConnection.checkLogPosition(retrieval, as400, offsetWithPosition()))
                .isEqualTo(PositionAvailability.PRUNED);
    }

    @Test
    void transientFailureIsRethrownNotReportedAsPruned() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        when(retrieval.getReceiverDetails(any(), any())).thenThrow(new Exception("connection reset"));

        assertThatThrownBy(() -> As400RpcConnection.checkLogPosition(retrieval, as400, offsetWithPosition()))
                .isInstanceOf(DebeziumException.class)
                .hasMessageContaining("transient");
    }

    @Test
    void blankPositionIsNotSet() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        final As400OffsetContext blank = new As400OffsetContext(config(), new JournalProcessedPosition());

        assertThat(As400RpcConnection.checkLogPosition(retrieval, as400, blank))
                .isEqualTo(PositionAvailability.NOT_SET);
    }
}
