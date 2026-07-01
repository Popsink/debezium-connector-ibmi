/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import io.debezium.DebeziumException;

/**
 * Thrown at startup when the stored journal position points at a receiver that has been pruned on
 * the IBM i and the connector is configured to {@code FAIL} rather than auto-recover.
 * <p>
 * This is a <em>non-transient</em> condition: retrying the engine will never make the pruned
 * receiver reappear, so an orchestrator must reset the offset (and trigger a snapshot) or alert an
 * operator rather than spend its restart budget. The {@link #ERROR_CODE} marker is stable and
 * machine-readable so the data-plane can classify this deterministically from the failure message.
 */
public class OffsetNoLongerAvailableException extends DebeziumException {

    /** Stable marker an orchestrator can match on; do not change without coordinating with the data-plane. */
    public static final String ERROR_CODE = "IBMI_OFFSET_NO_LONGER_AVAILABLE";

    public OffsetNoLongerAvailableException(String message) {
        super(ERROR_CODE + ": " + message);
    }
}
