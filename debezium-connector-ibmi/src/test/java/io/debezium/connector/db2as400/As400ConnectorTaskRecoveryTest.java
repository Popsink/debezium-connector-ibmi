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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400RpcConnection.PositionAvailability;
import io.debezium.connector.db2as400.As400RpcConnection.RpcException;
import io.debezium.ibmi.db2.journal.retrieve.JournalPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.pipeline.spi.Offsets;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Verifies the {@link As400ConnectorConfig.UnavailablePositionRecovery} strategies applied at startup
 * when the stored journal position points at a pruned receiver.
 */
class As400ConnectorTaskRecoveryTest {

    private static final JournalReceiver LOST_RECEIVER = new JournalReceiver("RECV008", "RCV_LIB");
    private static final JournalReceiver HEAD_RECEIVER = new JournalReceiver("RECV431", "RCV_LIB");
    private static final BigInteger HEAD_OFFSET = BigInteger.valueOf(3_525_020_805L);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(As400ConnectorTask.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logs);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The lost position, 82, against a chain retaining 101..3 700: 18 entries are gone, 3 600 retained. */
    private static Optional<RecoveryCost> costOfTheLostPosition() {
        return Optional.of(new RecoveryCost(new JournalProcessedPosition(BigInteger.valueOf(82), LOST_RECEIVER,
                Instant.ofEpochSecond(123_456L), true), ReceiverChain.of(ReceiverChainTest.chain(), ReceiverChainTest.NOW),
                Optional.empty()));
    }

    private static As400ConnectorConfig config(String recovery) {
        // 'latest' only starts under errors.tolerance=all, so these exercise a configuration that
        // As400ConnectorConfig#validateUnavailablePositionRecovery would actually let through
        return new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .with(As400ConnectorConfig.UNAVAILABLE_POSITION_RECOVERY, recovery)
                .with(As400ConnectorConfig.ERRORS_TOLERANCE, As400ConnectorConfig.ErrorsTolerance.ALL.getValue())
                .build());
    }

    private static Offsets<As400Partition, As400OffsetContext> offsets(As400ConnectorConfig config) {
        final JournalProcessedPosition lost = new JournalProcessedPosition(
                BigInteger.valueOf(82), LOST_RECEIVER, Instant.ofEpochSecond(123_456L), true);
        return Offsets.of(Map.of(new As400Partition("serverX"), new As400OffsetContext(config, lost)));
    }

    private static As400RpcConnection prunedConnection() {
        final As400RpcConnection rpcConnection = mock(As400RpcConnection.class);
        when(rpcConnection.checkLogPosition(any())).thenReturn(PositionAvailability.PRUNED);
        return rpcConnection;
    }

    @Test
    void latestResumesAtTheJournalHead() throws Exception {
        final As400ConnectorConfig config = config("latest");
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config);
        final As400RpcConnection rpcConnection = prunedConnection();
        when(rpcConnection.getCurrentPosition()).thenReturn(new JournalPosition(HEAD_OFFSET, HEAD_RECEIVER));

        As400ConnectorTask.applyUnavailablePositionRecovery(config, rpcConnection, previousOffsets);

        final JournalProcessedPosition recovered = previousOffsets.getTheOnlyOffset().getPosition();
        assertThat(recovered.getReceiver()).isEqualTo(HEAD_RECEIVER);
        assertThat(recovered.getOffset()).isEqualTo(HEAD_OFFSET);
        // the head entry is already accounted for, so streaming resumes after it rather than re-reading it
        assertThat(recovered.processed()).isTrue();
    }

    @Test
    void latestLetsATransientHeadLookupFailureRetry() throws Exception {
        final As400ConnectorConfig config = config("latest");
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config);
        final As400RpcConnection rpcConnection = prunedConnection();
        when(rpcConnection.getCurrentPosition()).thenThrow(new RpcException("connection reset"));

        assertThatThrownBy(() -> As400ConnectorTask.applyUnavailablePositionRecovery(config, rpcConnection, previousOffsets))
                .isInstanceOf(DebeziumException.class)
                .hasMessageContaining("transient");

        // the stored position is left alone so the engine restart is a real retry, not a silent reset
        assertThat(previousOffsets.getTheOnlyOffset().getPosition().getReceiver()).isEqualTo(LOST_RECEIVER);
    }

    @Test
    void latestLeavesAnAvailablePositionAlone() throws Exception {
        final As400ConnectorConfig config = config("latest");
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config);
        final As400RpcConnection rpcConnection = mock(As400RpcConnection.class);
        when(rpcConnection.checkLogPosition(any())).thenReturn(PositionAvailability.AVAILABLE);

        As400ConnectorTask.applyUnavailablePositionRecovery(config, rpcConnection, previousOffsets);

        verify(rpcConnection, never()).getCurrentPosition();
        assertThat(previousOffsets.getTheOnlyOffset().getPosition().getReceiver()).isEqualTo(LOST_RECEIVER);
    }

    @Test
    void earliestStillResetsToABlankPosition() throws Exception {
        final As400ConnectorConfig config = config("earliest");
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config);
        final As400RpcConnection rpcConnection = prunedConnection();

        As400ConnectorTask.applyUnavailablePositionRecovery(config, rpcConnection, previousOffsets);

        final JournalProcessedPosition recovered = previousOffsets.getTheOnlyOffset().getPosition();
        assertThat(recovered.getReceiver()).isNull();
        assertThat(recovered.isOffsetSet()).isFalse();
        verify(rpcConnection, never()).getCurrentPosition();
    }

    @Test
    void failStillThrowsAndPointsAtTheAutoRecoveryOptions() {
        final As400ConnectorConfig config = config("fail");
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config);

        assertThatThrownBy(() -> As400ConnectorTask.applyUnavailablePositionRecovery(config, prunedConnection(), previousOffsets))
                .isInstanceOf(OffsetNoLongerAvailableException.class)
                .hasMessageContaining("'snapshot' or 'earliest'")
                // 'latest' is offered only with the tolerance it requires, so the hint cannot be followed into a rejected start
                .hasMessageContaining("'latest' together with 'errors.tolerance=all'");
    }

    /**
     * At startup too the cost is stated before any strategy is applied, and 'earliest' names only the entries the
     * chain shows gone (issue #87): this is the path the 3.5-billion-entry re-read went through.
     */
    @Test
    void earliestStatesTheMeasuredCostBeforeResetting() throws Exception {
        final As400ConnectorConfig config = config("earliest");
        final As400RpcConnection rpcConnection = prunedConnection();
        when(rpcConnection.recoveryCost(any())).thenReturn(costOfTheLostPosition());

        As400ConnectorTask.applyUnavailablePositionRecovery(config, rpcConnection, offsets(config));

        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(0)).contains("recovering with 'earliest'", "earliest re-reads 3600 entries",
                "latest skips 3600 retained entries", "retention=60.0min");
        assertThat(warnings().get(1)).startsWith("DATA GAP")
                .contains("18 entries between the lost position and the earliest available receiver are unrecoverable");
    }

    @Test
    void earliestClaimsNoLossItCouldNotMeasure() throws Exception {
        final As400ConnectorConfig config = config("earliest");
        final As400RpcConnection rpcConnection = prunedConnection();
        when(rpcConnection.recoveryCost(any())).thenReturn(Optional.empty());

        As400ConnectorTask.applyUnavailablePositionRecovery(config, rpcConnection, offsets(config));

        assertThat(warnings()).noneSatisfy(w -> assertThat(w).contains("unrecoverable"));
        assertThat(warnings()).anySatisfy(w -> assertThat(w).startsWith("DATA GAP").contains("could not be measured"));
    }

    @Test
    void latestStatesTheRetainedEntriesItSkips() throws Exception {
        final As400ConnectorConfig config = config("latest");
        final As400RpcConnection rpcConnection = prunedConnection();
        when(rpcConnection.recoveryCost(any())).thenReturn(costOfTheLostPosition());
        when(rpcConnection.getCurrentPosition()).thenReturn(new JournalPosition(HEAD_OFFSET, HEAD_RECEIVER));

        As400ConnectorTask.applyUnavailablePositionRecovery(config, rpcConnection, offsets(config));

        assertThat(warnings()).anySatisfy(w -> assertThat(w).startsWith("DATA GAP")
                .contains("the 3600 retained entries before it are skipped", "18 entries between the lost position"));
    }
}
