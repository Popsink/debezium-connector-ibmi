/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code CHGQRYA QRYTIMLMT} command the snapshot source runs on its connections to keep the
 * DB2 for i Predictive Query Governor from rejecting large full-table snapshots with {@code SQL0666}.
 */
public class As400SnapshotQueryGovernorTest {

    @Test
    void nomax_is_wrapped_in_qcmdexc() {
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand("*NOMAX"))
                .contains("CALL QSYS2.QCMDEXC('CHGQRYA QRYTIMLMT(*NOMAX)')");
    }

    @Test
    void numeric_seconds_are_accepted() {
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand("3600"))
                .contains("CALL QSYS2.QCMDEXC('CHGQRYA QRYTIMLMT(3600)')");
    }

    @Test
    void value_is_normalised_to_upper_case() {
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand("*nomax"))
                .contains("CALL QSYS2.QCMDEXC('CHGQRYA QRYTIMLMT(*NOMAX)')");
    }

    @Test
    void same_and_blank_and_null_are_no_ops() {
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand("*SAME")).isEmpty();
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand("  ")).isEmpty();
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand(null)).isEmpty();
    }

    @Test
    void invalid_values_are_rejected_not_interpolated() {
        // guards against CL/SQL injection through the config value
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand("*NOMAX) CLRPFM")).isEmpty();
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand("60'); DROP")).isEmpty();
        assertThat(As400SnapshotChangeEventSource.queryTimeLimitCommand("-5")).isEmpty();
    }
}
