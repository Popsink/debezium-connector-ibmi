/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.ibmi.db2.journal.retrieve.JournalEntryType;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.jdbc.JdbcValueConverters.DecimalMode;
import io.debezium.relational.Column;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A control character in a text value is replaced with '?' and the value delivered. Each altered value is
 * counted and traceable to its row, and the log stays bounded however many values are altered (#58).
 */
class As400ValueConvertersSanitiseTest {

    private static final TableId TABLE = new TableId("DTEST", "LIB", "ORDERS");
    private static final Column NOTES = Column.editor().name("NOTES").type("VARCHAR").jdbcType(Types.VARCHAR).create();
    private static final Column CODE = Column.editor().name("CODE").type("CHAR").jdbcType(Types.CHAR).create();
    private static final String DIRTY = "a\u0000b\u0001";

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override
        public long currentTimeInMillis() {
            return now.get();
        }
    };
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(As400ValueConverters.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
    }

    private As400ValueConverters converters() {
        return new As400ValueConverters(DecimalMode.PRECISE, new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .build()), new SanitisedValues(clock));
    }

    private static Object convert(As400ValueConverters converters, Column column, Object value) {
        return converters.converter(column, new Field(column.name(), 0, Schema.OPTIONAL_STRING_SCHEMA)).convert(value);
    }

    private static RowAttribution journalRow(long offset, long rrn) {
        final EntryHeader eheader = mock(EntryHeader.class);
        when(eheader.getSequenceNumber()).thenReturn(BigInteger.valueOf(offset));
        when(eheader.getRelativeRecordNumber()).thenReturn(BigInteger.valueOf(rrn));
        when(eheader.getMember()).thenReturn("ORDERS");
        when(eheader.getJournalCode()).thenReturn('R');
        when(eheader.getJournalEntryType()).thenReturn(JournalEntryType.ADD_ROW2);
        when(eheader.getCommitCycle()).thenReturn(BigInteger.valueOf(42));
        return RowAttribution.ofJournalEntry(TABLE, eheader);
    }

    private List<ILoggingEvent> at(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @Test
    void replacesControlCharactersInPlaceAndDeliversTheValue() {
        assertThat(convert(converters(), NOTES, DIRTY)).isEqualTo("a?b?");
    }

    @Test
    void countsEachAlteredValueByColumn() {
        final As400ValueConverters converters = converters();

        convert(converters, NOTES, DIRTY);
        convert(converters, NOTES, DIRTY);
        convert(converters, CODE, "clean");

        assertThat(converters.sanitisedValues().total()).isEqualTo(2);
        assertThat(converters.sanitisedValues().byColumn()).isEqualTo(Map.of("NOTES", 2L));
    }

    @Test
    void countsAColumnOncePerRowAndKeysItByTable() {
        final As400ValueConverters converters = converters();

        try (RowAttribution.Binding row = journalRow(100, 1).bind()) {
            // a key column is converted for the key and for the value; an update converts both images
            convert(converters, NOTES, DIRTY);
            convert(converters, NOTES, DIRTY);
            convert(converters, CODE, DIRTY);
        }
        try (RowAttribution.Binding row = journalRow(101, 2).bind()) {
            convert(converters, NOTES, DIRTY);
        }

        assertThat(converters.sanitisedValues().total()).isEqualTo(3);
        assertThat(converters.sanitisedValues().byColumn())
                .isEqualTo(Map.of("LIB.ORDERS.NOTES", 2L, "LIB.ORDERS.CODE", 1L));
    }

    @Test
    void warnsOnceNamingTheRowItAlteredAndWhatHappened() {
        final As400ValueConverters converters = converters();

        for (int i = 0; i < 1000; i++) {
            try (RowAttribution.Binding row = journalRow(12345 + i, 7 + i).bind()) {
                convert(converters, NOTES, DIRTY);
            }
        }

        final List<ILoggingEvent> warnings = at(Level.WARN);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage())
                .contains("replaced 2 control character(s) with '?' in column NOTES")
                .contains("table DTEST.LIB.ORDERS")
                .contains("offset 12345")
                .contains("RRN 7")
                .contains("member ORDERS")
                .contains("journal code R")
                .contains("commit cycle 42")
                .contains("delivered")
                .doesNotContain("removed")
                .doesNotContain("fieldDefn");
        assertThat(at(Level.DEBUG)).hasSize(1000);
        assertThat(at(Level.DEBUG).get(999).getFormattedMessage()).contains("offset 13344").contains("RRN 1006");
        assertThat(converters.sanitisedValues().total()).isEqualTo(1000);
    }

    @Test
    void summarisesTheValuesHeldBackOncePerInterval() {
        final As400ValueConverters converters = converters();
        try (RowAttribution.Binding row = journalRow(1, 1).bind()) {
            convert(converters, NOTES, DIRTY);
        }
        for (int i = 2; i <= 10; i++) {
            try (RowAttribution.Binding row = journalRow(i, i).bind()) {
                convert(converters, NOTES, DIRTY);
            }
        }
        assertThat(at(Level.WARN)).hasSize(1);

        now.addAndGet(SanitisedValues.SUMMARY_INTERVAL.toMillis());
        try (RowAttribution.Binding row = journalRow(11, 11).bind()) {
            convert(converters, NOTES, DIRTY);
        }
        try (RowAttribution.Binding row = journalRow(12, 12).bind()) {
            convert(converters, NOTES, DIRTY);
        }

        final List<ILoggingEvent> warnings = at(Level.WARN);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(1).getFormattedMessage())
                .contains("in 10 more value(s) over the last 60s")
                .contains("LIB.ORDERS.NOTES: 10 value(s), first at offset 2, RRN 2")
                .contains("last at offset 11, RRN 11")
                .contains("11 value(s) altered since the connector started")
                .doesNotContain("removed");
    }

    @Test
    void aNewColumnIsAnnouncedEvenInsideAnInterval() {
        final As400ValueConverters converters = converters();

        try (RowAttribution.Binding row = journalRow(1, 1).bind()) {
            convert(converters, NOTES, DIRTY);
        }
        try (RowAttribution.Binding row = journalRow(2, 2).bind()) {
            convert(converters, CODE, DIRTY);
        }

        assertThat(at(Level.WARN)).extracting(ILoggingEvent::getFormattedMessage)
                .satisfiesExactly(
                        notes -> assertThat(notes).contains("column NOTES").contains("offset 1,"),
                        code -> assertThat(code).contains("column CODE").contains("offset 2,"));
    }

    @Test
    void aBindingIsReleasedWhenClosed() {
        try (RowAttribution.Binding outer = journalRow(1, 1).bind()) {
            try (RowAttribution.Binding inner = RowAttribution.ofSnapshot(TABLE).bind()) {
                assertThat(RowAttribution.current().row().position()).isEqualTo("read by snapshot");
            }
            assertThat(RowAttribution.current().row().position()).startsWith("offset 1,");
        }
        assertThat(RowAttribution.current()).isNull();
    }
}
