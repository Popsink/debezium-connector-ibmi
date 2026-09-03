/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400RpcConnection.BlockingReceiverConsumer;
import io.debezium.ibmi.db2.journal.retrieve.JdbcFileDecoder;
import io.debezium.ibmi.db2.journal.retrieve.JournalEntryType;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.ibmi.db2.journal.retrieve.RetrieveJournal;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** The skip ERROR line feeds the dead-letter queue, so it must keep its wording and carry the row bytes. */
public class As400StreamingChangeEventSourceSkipLogTest {

    private static final String DATABASE = "DTEST";
    private static final String LIBRARY = "LIB";
    private static final String TABLE = "CUSTOMERS";
    private static final BigInteger SEQUENCE = BigInteger.valueOf(12345);
    private static final BigInteger RRN = BigInteger.valueOf(7);
    private static final String RECORD_IMAGE_HEX = "c1c2c34040";

    private static final class SingleIterationContext implements ChangeEventSourceContext {
        private final AtomicInteger runningChecks = new AtomicInteger();

        @Override
        public boolean isRunning() {
            return runningChecks.getAndIncrement() == 0;
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public void streamingPaused() {
        }

        @Override
        public void waitSnapshotCompletion() {
        }

        @Override
        public void resumeStreaming() {
        }

        @Override
        public void waitStreamingPaused() {
        }
    }

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    public void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(As400StreamingChangeEventSource.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    public void releaseLog() {
        logger.detachAppender(logs);
    }

    private static As400ConnectorConfig config() {
        return new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "server1")
                .with(As400ConnectorConfig.DATABASE_NAME, DATABASE)
                .with(As400ConnectorConfig.SCHEMA, LIBRARY)
                .with(As400ConnectorConfig.TABLE_INCLUDE_LIST, LIBRARY + "." + TABLE)
                .with(CommonConnectorConfig.POLL_INTERVAL_MS, 1)
                .build());
    }

    private static JournalProcessedPosition positionAt(BigInteger offset, boolean processed) {
        return new JournalProcessedPosition(offset, new JournalReceiver("RCV0001", "RCVLIB"), Instant.EPOCH, processed);
    }

    private static EntryHeader insertOnCapturedTable() {
        final EntryHeader eheader = mock(EntryHeader.class);
        when(eheader.getJournalEntryType()).thenReturn(JournalEntryType.ADD_ROW1);
        when(eheader.getJournalCode()).thenReturn('R');
        when(eheader.getLibrary()).thenReturn(LIBRARY);
        when(eheader.getFile()).thenReturn(TABLE);
        when(eheader.getSequenceNumber()).thenReturn(SEQUENCE);
        when(eheader.getRelativeRecordNumber()).thenReturn(RRN);
        when(eheader.getCommitCycle()).thenReturn(BigInteger.ZERO);
        return eheader;
    }

    private static RetrieveJournal undecodableEntry() throws Exception {
        final RetrieveJournal retrieveJournal = mock(RetrieveJournal.class);
        when(retrieveJournal.decode(any())).thenThrow(new RuntimeException("boom"));
        when(retrieveJournal.currentRecordImageHex(anyInt())).thenReturn(RECORD_IMAGE_HEX);
        return retrieveJournal;
    }

    /** Mirrors {@link As400RpcConnection#getJournalEntries}: the position moves on only if the consumer returns. */
    private static As400RpcConnection oneEntryBlock(RetrieveJournal retrieveJournal, EntryHeader eheader) throws Exception {
        final As400RpcConnection dataConnection = mock(As400RpcConnection.class);
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenAnswer(invocation -> {
            final As400OffsetContext offsetContext = invocation.getArgument(1);
            final BlockingReceiverConsumer consumer = invocation.getArgument(2);
            consumer.accept(SEQUENCE, retrieveJournal, eheader);
            offsetContext.getPosition().setPosition(positionAt(SEQUENCE, true));
            return RetrievalState.Success;
        });
        return dataConnection;
    }

    private static As400JdbcConnection jdbcConnection() {
        final As400JdbcConnection jdbcConnection = mock(As400JdbcConnection.class);
        when(jdbcConnection.getRealDatabaseName()).thenReturn(DATABASE);
        when(jdbcConnection.getLongName(LIBRARY, TABLE)).thenReturn(TABLE);
        return jdbcConnection;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void skippedEntryIsLoggedOnceWithItsRecordImage() throws Exception {
        final As400ConnectorConfig config = config();
        final EventDispatcher<As400Partition, TableId> dispatcher = mock(EventDispatcher.class);
        final As400DatabaseSchema schema = mock(As400DatabaseSchema.class);
        when(schema.getFileDecoder()).thenReturn(mock(JdbcFileDecoder.class));
        final As400OffsetContext offsetContext = new As400OffsetContext(config,
                positionAt(SEQUENCE.subtract(BigInteger.ONE), true));

        final As400StreamingChangeEventSource source = new As400StreamingChangeEventSource(config,
                oneEntryBlock(undecodableEntry(), insertOnCapturedTable()), jdbcConnection(), dispatcher,
                mock(ErrorHandler.class), Clock.SYSTEM, schema);

        source.execute(new SingleIterationContext(), new As400Partition(config.getLogicalName()), offsetContext);

        final List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        final ILoggingEvent skip = errors.get(0);
        assertThat(skip.getFormattedMessage())
                .contains("offset = " + SEQUENCE)
                .contains("in table = " + TABLE)
                .contains("RRN = " + RRN)
                .as("data-plane DLQ filter wording")
                .contains("skipping and dumping diagnostics")
                .contains("record image (hex) = " + RECORD_IMAGE_HEX);
        assertThat(skip.getThrowableProxy()).isNotNull();
        assertThat(skip.getThrowableProxy().getMessage()).isEqualTo("boom");

        verify(dispatcher, never()).dispatchDataChangeEvent(any(), any(), any());
        assertThat(offsetContext.getPosition().getOffset()).isEqualTo(SEQUENCE);
    }
}
