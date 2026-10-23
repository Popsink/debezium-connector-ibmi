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
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400RpcConnection.PositionAvailability;
import io.debezium.ibmi.db2.journal.retrieve.JournalPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.pipeline.spi.Offsets;

/**
 * Verifies the operator-requested reposition to the journal head: the escape hatch for a connector whose
 * position is perfectly available but too far behind to ever catch up.
 */
class As400ConnectorTaskRepositionTest {

    private static final JournalReceiver BEHIND_RECEIVER = new JournalReceiver("RECV900", "RCV_LIB");
    private static final JournalReceiver HEAD_RECEIVER = new JournalReceiver("RECV904", "RCV_LIB");
    private static final BigInteger BEHIND_OFFSET = BigInteger.valueOf(209_942_954_749L);
    private static final BigInteger HEAD_OFFSET = BigInteger.valueOf(210_061_145_412L);
    private static final String TOKEN = "urm-2026-09-14";

    private static Configuration.Builder base() {
        return Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX");
    }

    private static As400ConnectorConfig config(String token) {
        final Configuration.Builder builder = base()
                .with(As400ConnectorConfig.ERRORS_TOLERANCE, As400ConnectorConfig.ErrorsTolerance.ALL.getValue());
        if (token != null) {
            builder.with(As400ConnectorConfig.REPOSITION_TOKEN, token);
        }
        return new As400ConnectorConfig(builder.build());
    }

    private static Offsets<As400Partition, As400OffsetContext> offsets(As400ConnectorConfig config, String appliedToken) {
        final JournalProcessedPosition behind = new JournalProcessedPosition(
                BEHIND_OFFSET, BEHIND_RECEIVER, Instant.ofEpochSecond(1_757_000_000L), true);
        final As400OffsetContext context = new As400OffsetContext(config, behind);
        context.setRepositionToken(appliedToken);
        return Offsets.of(Map.of(new As400Partition("serverX"), context));
    }

    /** The position is available - the whole point is that nothing else would move it. */
    private static As400RpcConnection connectionAtHead() throws Exception {
        final As400RpcConnection rpcConnection = mock(As400RpcConnection.class);
        when(rpcConnection.checkLogPosition(any())).thenReturn(PositionAvailability.AVAILABLE);
        when(rpcConnection.getCurrentPosition()).thenReturn(new JournalPosition(HEAD_OFFSET, HEAD_RECEIVER));
        return rpcConnection;
    }

    @Test
    void anArmedTokenMovesStreamingToTheHead() throws Exception {
        final As400ConnectorConfig config = config(TOKEN);
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config, null);

        As400ConnectorTask.applyOperatorReposition(config, connectionAtHead(), previousOffsets);

        final JournalProcessedPosition moved = previousOffsets.getTheOnlyOffset().getPosition();
        assertThat(moved.getReceiver()).isEqualTo(HEAD_RECEIVER);
        assertThat(moved.getOffset()).isEqualTo(HEAD_OFFSET);
        // the head entry is already accounted for, so streaming resumes after it rather than re-reading it
        assertThat(moved.processed()).isTrue();
    }

    @Test
    void theAppliedTokenIsRecordedInTheOffset() throws Exception {
        final As400ConnectorConfig config = config(TOKEN);
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config, null);

        As400ConnectorTask.applyOperatorReposition(config, connectionAtHead(), previousOffsets);

        assertThat(previousOffsets.getTheOnlyOffset().getRepositionToken()).isEqualTo(TOKEN);
    }

    /** The one that matters: a restart must not skip the journal a second time. */
    @Test
    void anAlreadyAppliedTokenLeavesThePositionAlone() throws Exception {
        final As400ConnectorConfig config = config(TOKEN);
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config, TOKEN);
        final As400RpcConnection rpcConnection = connectionAtHead();

        As400ConnectorTask.applyOperatorReposition(config, rpcConnection, previousOffsets);

        final JournalProcessedPosition unchanged = previousOffsets.getTheOnlyOffset().getPosition();
        assertThat(unchanged.getReceiver()).isEqualTo(BEHIND_RECEIVER);
        assertThat(unchanged.getOffset()).isEqualTo(BEHIND_OFFSET);
        verify(rpcConnection, never()).getCurrentPosition();
    }

    @Test
    void aDifferentTokenArmsAnotherReposition() throws Exception {
        final As400ConnectorConfig config = config("urm-round-two");
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config, TOKEN);

        As400ConnectorTask.applyOperatorReposition(config, connectionAtHead(), previousOffsets);

        assertThat(previousOffsets.getTheOnlyOffset().getPosition().getOffset()).isEqualTo(HEAD_OFFSET);
        assertThat(previousOffsets.getTheOnlyOffset().getRepositionToken()).isEqualTo("urm-round-two");
    }

    @Test
    void noTokenIsANoOp() throws Exception {
        final As400ConnectorConfig config = config(null);
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config, null);
        final As400RpcConnection rpcConnection = connectionAtHead();

        As400ConnectorTask.applyOperatorReposition(config, rpcConnection, previousOffsets);

        assertThat(previousOffsets.getTheOnlyOffset().getPosition().getOffset()).isEqualTo(BEHIND_OFFSET);
        verify(rpcConnection, never()).getCurrentPosition();
    }

    /** A blank value is how the token is cleared; it must not count as armed. */
    @Test
    void aBlankTokenIsANoOp() throws Exception {
        final As400ConnectorConfig config = config("   ");
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config, null);
        final As400RpcConnection rpcConnection = connectionAtHead();

        As400ConnectorTask.applyOperatorReposition(config, rpcConnection, previousOffsets);

        assertThat(previousOffsets.getTheOnlyOffset().getPosition().getOffset()).isEqualTo(BEHIND_OFFSET);
        verify(rpcConnection, never()).getCurrentPosition();
    }

    @Test
    void theTokenSurvivesAnOffsetRoundTrip() throws Exception {
        final As400ConnectorConfig config = config(TOKEN);
        final Offsets<As400Partition, As400OffsetContext> previousOffsets = offsets(config, null);
        As400ConnectorTask.applyOperatorReposition(config, connectionAtHead(), previousOffsets);

        final Map<String, ?> stored = previousOffsets.getTheOnlyOffset().getOffset();
        final As400OffsetContext reloaded = new As400OffsetContext.Loader(config).load(stored);

        assertThat(reloaded.getRepositionToken()).isEqualTo(TOKEN);
        assertThat(reloaded.getPosition().getOffset()).isEqualTo(HEAD_OFFSET);
    }

    /** Skipping changes contradicts errors.tolerance=none, so the task must refuse to start. */
    @Test
    void aRepositionWithoutErrorsToleranceAllIsRefused() {
        final As400ConnectorConfig config = new As400ConnectorConfig(base()
                .with(As400ConnectorConfig.REPOSITION_TOKEN, TOKEN)
                .build());

        assertThatThrownBy(config::validateUnavailablePositionRecovery)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(As400ConnectorConfig.REPOSITION_TOKEN.name())
                .hasMessageContaining(As400ConnectorConfig.ERRORS_TOLERANCE.name());
    }
}
