/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400Text;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.data.types.UntaggedCcsid;

/**
 * Decides whether an untagged (CCSID 65535) {@code CHAR}/{@code VARCHAR} column - which jt400 reports
 * as {@code FOR BIT DATA} - is read as text in the system CCSID's superset, or kept binary
 * (Popsink/data-plane#5831).
 *
 * <p>
 * A decision comes from {@link UntaggedDecisions} when there is one. Otherwise every undecided untagged
 * column of the table is sampled in one capped query: a column whose first non-blank values all read as
 * text ({@link UntaggedCcsid#readsAsText}) is text, any other is binary. A sample that fails decides
 * binary - what the column streamed as before - rather than guess.
 * </p>
 */
public class UntaggedColumns {
    private static final Logger log = LoggerFactory.getLogger(UntaggedColumns.class);

    /** Read before {@link CcsidCache} applies {@code from.ccsid}, which never targets 65535 anyway. */
    static final String UNTAGGED_COLUMNS = "select column_name from qsys2.syscolumns where table_schema=? "
            + "and (table_name=? or system_table_name=?) and ccsid=65535 and data_type in ('CHAR','VARCHAR')";
    static final int SAMPLE_ROWS = 1000;
    static final int SAMPLE_TIMEOUT_SECONDS = 60;

    private final Connect<Connection, SQLException> jdbcConnect;
    private final UntaggedDecisions decisions;
    private final int ccsid;

    public UntaggedColumns(Connect<Connection, SQLException> jdbcConnect, UntaggedDecisions decisions,
                           As400TextFactory textFactory) {
        this.jdbcConnect = jdbcConnect;
        this.decisions = decisions;
        this.ccsid = UntaggedCcsid.superset(textFactory.systemCcsid());
    }

    public UntaggedDecisions decisions() {
        return decisions;
    }

    /** @return the CCSID the column is read in, empty when it stays binary */
    public synchronized OptionalInt textCcsid(String schema, String table, String column) {
        final String key = UntaggedDecisions.key(schema, table, column);
        OptionalInt decision = decisions.get(key);
        if (decision.isEmpty()) {
            decide(schema, table);
            decision = decisions.get(key);
        }
        return (decision.isEmpty() || decision.getAsInt() == UntaggedDecisions.HEX) ? OptionalInt.empty() : decision;
    }

    private void decide(String schema, String table) {
        if (jdbcConnect == null) {
            // a decoder built offline: decide nothing, the column stays binary as before
            return;
        }
        final List<String> undecided = new ArrayList<>();
        try {
            for (final String column : untaggedColumns(schema, table)) {
                if (decisions.get(UntaggedDecisions.key(schema, table, column)).isEmpty()) {
                    undecided.add(column);
                }
            }
        }
        catch (final SQLException e) {
            log.warn("could not list the untagged columns of {}.{}, they stay binary until its schema is "
                    + "next read", schema, table, e);
            return;
        }
        if (undecided.isEmpty()) {
            return;
        }

        Map<String, Integer> decided;
        if (ccsid <= 0) {
            // a system CCSID of 65535 (QCCSID's shipped default) names no code page; reading the bytes in
            // the pod's locale guess would differ from the loaders, which keep these columns hex
            log.warn("the IBM i system ccsid is unknown (65535), so the untagged columns {} of {}.{} stay binary",
                    undecided, schema, table);
            decided = allHex(undecided);
        }
        else {
            decided = sampleOrHex(schema, table, undecided);
        }
        record(schema, table, decided);
    }

    private Map<String, Integer> sampleOrHex(String schema, String table, List<String> undecided) {
        try {
            return sample(schema, table, undecided);
        }
        catch (final SQLException e) {
            log.warn("sampling the untagged columns {} of {}.{} failed, they stay binary", undecided, schema,
                    table, e);
            return allHex(undecided);
        }
    }

    private static Map<String, Integer> allHex(List<String> columns) {
        final Map<String, Integer> decided = new LinkedHashMap<>();
        for (final String column : columns) {
            decided.put(column, UntaggedDecisions.HEX);
        }
        return decided;
    }

    private void record(String schema, String table, Map<String, Integer> decided) {
        final StringJoiner text = new StringJoiner(", ");
        for (final Map.Entry<String, Integer> entry : decided.entrySet()) {
            decisions.putIfAbsent(UntaggedDecisions.key(schema, table, entry.getKey()), entry.getValue());
            if (entry.getValue() == UntaggedDecisions.HEX) {
                log.warn("untagged (ccsid 65535) column {} of {}.{} does not read as text in ccsid {}, it stays "
                        + "binary", entry.getKey(), schema, table, ccsid);
            }
            else {
                text.add(entry.getKey());
            }
        }
        if (text.length() > 0) {
            log.info("untagged (ccsid 65535) columns of {}.{} read as text in ccsid {}: {}", schema, table, ccsid,
                    text);
        }
    }

    private List<String> untaggedColumns(String schema, String table) throws SQLException {
        final List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = jdbcConnect.connection().prepareStatement(UNTAGGED_COLUMNS)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(StringHelpers.safeTrim(rs.getString(1)));
                }
            }
        }
        return columns;
    }

    /** One query for all of them, the first rows in whatever order is cheapest: no predicate, no sort. */
    private Map<String, Integer> sample(String schema, String table, List<String> columns) throws SQLException {
        final StringJoiner select = new StringJoiner(", ", "SELECT ", " FROM " + quote(schema) + "." + quote(table)
                + " FETCH FIRST " + SAMPLE_ROWS + " ROWS ONLY OPTIMIZE FOR " + SAMPLE_ROWS + " ROWS");
        columns.forEach(c -> select.add(quote(c)));

        final boolean[] binary = new boolean[columns.size()];
        final Map<Integer, AS400Text> decoders = new HashMap<>();
        try (PreparedStatement ps = jdbcConnect.connection().prepareStatement(select.toString())) {
            ps.setQueryTimeout(SAMPLE_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    for (int i = 0; i < columns.size(); i++) {
                        final byte[] value = binary[i] ? null : rs.getBytes(i + 1);
                        // a blank value decodes to spaces, which read as text: only null needs skipping
                        if (value == null || value.length == 0) {
                            continue;
                        }
                        final AS400Text decoder = decoders.computeIfAbsent(value.length, l -> new AS400Text(l, ccsid));
                        binary[i] = !UntaggedCcsid.readsAsText((String) decoder.toObject(value));
                    }
                }
            }
        }

        final Map<String, Integer> decided = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            decided.put(columns.get(i), binary[i] ? UntaggedDecisions.HEX : ccsid);
        }
        return decided;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
