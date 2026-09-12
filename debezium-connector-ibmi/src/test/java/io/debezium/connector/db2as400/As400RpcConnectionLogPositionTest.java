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
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400;

import io.debezium.DebeziumException;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400RpcConnection.PositionAvailability;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfo;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfoRetrieval;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.exception.JournalReceiverNotFoundException;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;

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

    // ---- issue #79: only a position that really has gone may lead to an offset reset ----

    private static final JournalInfo JOURNAL = new JournalInfo("JRN", "JRNLIB", false);

    private static DetailedJournalReceiver receiver(String name, long start, long end) {
        return new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver(name, "RCV_LIB"), new Date(1), JournalStatus.Attached, Optional.of(1)),
                BigInteger.valueOf(start), BigInteger.valueOf(end), Optional.empty(), 1, 1);
    }

    private static JournalProcessedPosition position(long offset) {
        return new JournalProcessedPosition(BigInteger.valueOf(offset), new JournalReceiver("RECV008", "RCV_LIB"),
                Instant.ofEpochSecond(123_456L), true);
    }

    @Test
    void aPositionInsideAReceiverThatIsStillInTheChainIsAvailable() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        when(retrieval.getReceivers(any(), any()))
                .thenReturn(List.of(receiver("RECV007", 1, 50), receiver("RECV008", 51, 100)));

        assertThat(As400RpcConnection.isPositionStillAvailable(retrieval, as400, JOURNAL, position(82))).isTrue();
    }

    @Test
    void aPositionWhoseReceiverHasBeenPrunedIsNotAvailable() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        when(retrieval.getReceivers(any(), any())).thenReturn(List.of(receiver("RECV009", 101, 150)));

        assertThat(As400RpcConnection.isPositionStillAvailable(retrieval, as400, JOURNAL, position(82))).isFalse();
    }

    @Test
    void anOffsetOutsideItsOwnReceiverIsNotAvailable() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        when(retrieval.getReceivers(any(), any())).thenReturn(List.of(receiver("RECV008", 51, 100)));

        // the receiver is there but holds nothing at that sequence, e.g. the journal was recreated under us
        assertThat(As400RpcConnection.isPositionStillAvailable(retrieval, as400, JOURNAL, position(4_000))).isFalse();
    }

    @Test
    void aFailureToReadTheChainIsNotTakenAsAPrunedPosition() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        when(retrieval.getReceivers(any(), any())).thenThrow(new Exception("connection reset"));

        // resetting the offset is the destructive answer, so a failure to look must never produce it
        assertThat(As400RpcConnection.isPositionStillAvailable(retrieval, as400, JOURNAL, position(82))).isTrue();
    }

    @Test
    void aBlankPositionIsNotAvailable() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);

        assertThat(As400RpcConnection.isPositionStillAvailable(retrieval, as400, JOURNAL, new JournalProcessedPosition()))
                .isFalse();
    }

    @Test
    void blankPositionIsNotSet() throws Exception {
        final JournalInfoRetrieval retrieval = mock(JournalInfoRetrieval.class);
        final As400OffsetContext blank = new As400OffsetContext(config(), new JournalProcessedPosition());

        assertThat(As400RpcConnection.checkLogPosition(retrieval, as400, blank))
                .isEqualTo(PositionAvailability.NOT_SET);
    }
}
