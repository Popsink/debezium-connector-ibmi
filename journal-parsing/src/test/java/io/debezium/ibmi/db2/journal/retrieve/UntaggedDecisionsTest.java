/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class UntaggedDecisionsTest {

    @Test
    void decisionsSurviveSerialisation() {
        final UntaggedDecisions decisions = new UntaggedDecisions();
        decisions.putIfAbsent(UntaggedDecisions.key("LIB", "TABLE", "PWD"), UntaggedDecisions.HEX);
        decisions.putIfAbsent(UntaggedDecisions.key("LIB", "TABLE", "NAME"), 1388);

        assertEquals("LIB.TABLE.NAME=1388;LIB.TABLE.PWD=hex", decisions.serialized());

        final UntaggedDecisions restored = new UntaggedDecisions();
        restored.load(decisions.serialized());
        assertEquals(OptionalInt.of(1388), restored.get("LIB.TABLE.NAME"));
        assertEquals(OptionalInt.of(UntaggedDecisions.HEX), restored.get("LIB.TABLE.PWD"));
        assertEquals(decisions.serialized(), restored.serialized());
    }

    /** A column's first decision wins: re-deciding it would flip its Kafka type. */
    @Test
    void aDecisionIsSticky() {
        final UntaggedDecisions decisions = new UntaggedDecisions();
        decisions.load("LIB.T.C=hex");
        decisions.putIfAbsent("LIB.T.C", 1388);
        decisions.load("LIB.T.C=37");

        assertEquals(OptionalInt.of(UntaggedDecisions.HEX), decisions.get("LIB.T.C"));
    }

    /** The offset reads it once per record: it must not be rebuilt unless something was added. */
    @Test
    void serialisedFormIsOnlyRebuiltWhenADecisionIsAdded() {
        final UntaggedDecisions decisions = new UntaggedDecisions();
        decisions.putIfAbsent("LIB.T.C", 1388);
        final String first = decisions.serialized();

        decisions.putIfAbsent("LIB.T.C", 37);
        assertSame(first, decisions.serialized());

        decisions.putIfAbsent("LIB.T.D", 37);
        assertEquals("LIB.T.C=1388;LIB.T.D=37", decisions.serialized());
    }

    @Test
    void malformedEntriesAreSkipped() {
        final UntaggedDecisions decisions = new UntaggedDecisions();
        decisions.load("garbage;LIB.T.C=notanumber;=37;LIB.T.D=1388");

        assertEquals("LIB.T.D=1388", decisions.serialized());
        assertTrue(decisions.get("LIB.T.C").isEmpty());
    }
}
