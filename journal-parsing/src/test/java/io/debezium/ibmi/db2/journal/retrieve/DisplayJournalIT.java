/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.CommandCall;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.test.TestConnector;

/**
 * The two things about {@code QSYS2.DISPLAY_JOURNAL} that {@link JournalLobFetcher} depends on and
 * cannot check for itself. Both are properties of the system rather than of our code, so they are
 * asserted against a live IBM i instead of assumed.
 *
 * <p>
 * This class used to hold the whole investigation behind {@code docs/detect-and-enrich-retrieval.md} -
 * twenty-odd probes that timed query shapes, hunted the pointer handle ceiling and measured bandwidth.
 * Those served their purpose and their results are written down in that document; the code that
 * produced them created hundreds of tables, leaked six hundred thousand handles and allocated
 * gigabytes, which is not something a test run should do to a shared system. It is in the history if a
 * number ever needs re-deriving.
 * </p>
 *
 * <p>
 * Needs {@code ISERIES_HOST}, {@code ISERIES_USER}, {@code ISERIES_PASSWORD} and {@code ISERIES_SCHEMA}.
 * </p>
 */
class DisplayJournalIT {
    private static final Logger log = LoggerFactory.getLogger(DisplayJournalIT.class);

    private static final String LOB_TABLE = "PTRSHAPE";
    private static final String PLAIN_TABLE = "PTRPLAIN";
    private static final String BIG_TABLE = "DJBIG";

    private static Connect<AS400, IOException> as400Connect;
    private static Connect<Connection, SQLException> sqlConnect;
    private static As400TextFactory textFactory;
    private static String schema;

    @BeforeAll
    static void setup() throws Exception {
        assumeTrue(System.getenv("ISERIES_HOST") != null && System.getenv("ISERIES_PASSWORD") != null,
                "needs ISERIES_HOST, ISERIES_USER, ISERIES_PASSWORD and ISERIES_SCHEMA for a live system");
        final TestConnector connector = new TestConnector();
        as400Connect = connector.getAs400();
        sqlConnect = connector.getJdbc();
        textFactory = connector.getTextFactory();
        schema = connector.getSchema();
        assertNotNull(schema, "ISERIES_SCHEMA must be set");
    }

    /**
     * The pointer handle is what tells the connector an entry has lob data to fetch, so it has to be a
     * property of the table rather than of the value. A handle that appeared only for a populated lob
     * would make the budget under-count; one that appeared for a table with no lob column would send
     * the fetcher looking for data that does not exist.
     */
    @Test
    void thePointerHandleIsATableLevelLobDetector() throws Exception {
        create(LOB_TABLE, "ID INT NOT NULL PRIMARY KEY, BODY CLOB(1M)");
        startJournaling(LOB_TABLE);
        create(PLAIN_TABLE, "ID INT NOT NULL PRIMARY KEY, NAME CHAR(30)");
        startJournaling(PLAIN_TABLE);

        final JournalInfoRetrieval retrieval = new JournalInfoRetrieval(textFactory, 0, 0, 1000);
        final JournalInfo journal = retrieval.getJournal(as400Connect.connection(), schema, LOB_TABLE);
        final JournalPosition at = retrieval.getCurrentPosition(as400Connect.connection(), journal);

        try (Statement stmt = sqlConnect.connection().createStatement()) {
            stmt.executeUpdate(String.format("INSERT INTO %s.%s (ID, BODY) VALUES (1, 'populated')", schema, LOB_TABLE));
            stmt.executeUpdate(String.format("INSERT INTO %s.%s (ID, BODY) VALUES (2, NULL)", schema, LOB_TABLE));
            stmt.executeUpdate(String.format("INSERT INTO %s.%s (ID, BODY) VALUES (3, '')", schema, LOB_TABLE));
            stmt.executeUpdate(String.format("INSERT INTO %s.%s (ID, NAME) VALUES (1, 'no lob here')", schema,
                    PLAIN_TABLE));
        }

        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect)
                .withTextFactory(textFactory).withJournalInfo(journal).build();
        final RetrieveJournal rj = new RetrieveJournal(config, retrieval);
        rj.retrieveJournal(new JournalProcessedPosition(at, Instant.now(), true));

        final Map<String, Long> handleByRow = new HashMap<>();
        while (rj.nextEntry()) {
            final var header = rj.getEntryHeader();
            final String table = header.getFile().trim();
            if (LOB_TABLE.equals(table) || PLAIN_TABLE.equals(table)) {
                handleByRow.put(table + '#' + header.getRelativeRecordNumber(), header.getPointerHandle());
                log.info("{} rrn {} -> handle {}", table, header.getRelativeRecordNumber(),
                        header.getPointerHandle());
            }
        }

        // populated, null and empty alike: the handle follows the column's existence, not its value
        assertTrue(handleByRow.getOrDefault(LOB_TABLE + "#1", 0L) != 0, "populated lob carried no handle");
        assertTrue(handleByRow.getOrDefault(LOB_TABLE + "#2", 0L) != 0, "null lob carried no handle");
        assertTrue(handleByRow.getOrDefault(LOB_TABLE + "#3", 0L) != 0, "empty lob carried no handle");
        assertEquals(0L, handleByRow.get(PLAIN_TABLE + "#1"), "a table with no lob column handed back a handle");
    }

    /**
     * The window reads entry data through {@code CAST(... AS VARBINARY)}, which is what makes it cheap.
     * The cast truncates silently, and the fetcher relies on a value shorter than the limit being proof
     * that nothing was cut off. This checks the system really does truncate rather than fail, so the one
     * case the fetcher re-reads whole is the only one it needs to.
     */
    @Test
    void theCastTruncatesSilentlyAtItsLimit() throws Exception {
        create(BIG_TABLE, "ID INT NOT NULL PRIMARY KEY, BODY CLOB(2M)");
        try {
            startJournaling(BIG_TABLE);
            final JournalInfoRetrieval retrieval = new JournalInfoRetrieval(textFactory, 0, 0, 1000);
            final JournalInfo journal = retrieval.getJournal(as400Connect.connection(), schema, BIG_TABLE);
            final JournalPosition before = retrieval.getCurrentPosition(as400Connect.connection(), journal);
            try (Statement stmt = sqlConnect.connection().createStatement()) {
                stmt.executeUpdate(String.format("""
                        INSERT INTO %s.%s (ID, BODY)
                        WITH n(i) AS (VALUES 1 UNION ALL SELECT i + 1 FROM n WHERE i < 5)
                        SELECT i, REPEAT(CAST('x' AS CLOB(2M)), 100000) FROM n
                        """, schema, BIG_TABLE));
            }
            final JournalPosition after = retrieval.getCurrentPosition(as400Connect.connection(), journal);

            final String sql = String.format("""
                    SELECT LENGTH(ENTRY_DATA), CAST(ENTRY_DATA AS VARBINARY(30000))
                        FROM TABLE(QSYS2.DISPLAY_JOURNAL('%s', '%s',
                            STARTING_SEQUENCE => CAST(? AS DECIMAL(21,0)),
                            ENDING_SEQUENCE => CAST(? AS DECIMAL(21,0)),
                            JOURNAL_CODES => 'R',
                            OBJECT_LIBRARY => '%s', OBJECT_NAME => '%s',
                            OBJECT_OBJTYPE => '*FILE', OBJECT_MEMBER => '*ALL'))
                    """, journal.journalLibrary(), journal.journalName(), schema, BIG_TABLE);
            int truncated = 0;
            try (var ps = sqlConnect.connection().prepareStatement(sql)) {
                ps.setLong(1, before.getOffset().longValue());
                ps.setLong(2, after.getOffset().longValue());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final long full = rs.getLong(1);
                        final byte[] head = rs.getBytes(2);
                        assertNotNull(head, "the cast returned nothing at all");
                        assertEquals(30000, head.length, "a lob far past the limit should fill it exactly");
                        assertTrue(full > head.length, "LENGTH still reports the untruncated size");
                        truncated++;
                    }
                }
            }
            log.info("{} oversized entries truncated silently by the cast, and detectably so", truncated);
            assertTrue(truncated > 0, "no oversized entry was produced to check");
        }
        finally {
            drop(BIG_TABLE);
        }
    }

    private static void create(String table, String columns) throws SQLException {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            try {
                stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, table));
            }
            catch (final SQLException e) {
                log.debug("{}.{} did not exist", schema, table);
            }
            stmt.executeUpdate(String.format("CREATE TABLE %s.%s (%s)", schema, table, columns));
        }
    }

    private static void drop(String table) {
        try (Statement stmt = sqlConnect.connection().createStatement()) {
            stmt.executeUpdate(String.format("DROP TABLE %s.%s", schema, table));
        }
        catch (final SQLException e) {
            log.warn("could not drop {}.{}", schema, table, e);
        }
    }

    /** The schema is not necessarily journaled itself, so each table needs STRJRNPF of its own. */
    private static void startJournaling(String table) throws Exception {
        final String command = String.format("STRJRNPF FILE(%s/%s) JRN(%s/JRNTEST) IMAGES(*BOTH) OMTJRNE(*NONE)",
                schema, table, schema);
        final CommandCall call = new CommandCall(as400Connect.connection());
        call.run(command);
        for (final AS400Message message : call.getMessageList()) {
            log.info("{} -> {}", command, message.getText());
        }
    }
}
