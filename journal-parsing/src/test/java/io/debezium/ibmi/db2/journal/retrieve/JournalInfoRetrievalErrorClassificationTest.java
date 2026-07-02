/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400Message;

import io.debezium.ibmi.db2.journal.retrieve.exception.JournalReceiverNotFoundException;

class JournalInfoRetrievalErrorClassificationTest {

    private static AS400Message message(String id, String text) {
        final AS400Message m = mock(AS400Message.class);
        lenient().when(m.getID()).thenReturn(id);
        lenient().when(m.getText()).thenReturn(text);
        return m;
    }

    @Test
    void objectNotFoundIsClassifiedAsReceiverNotFound() {
        final AS400Message[] messages = { message("CPF9801", "Object RCV0001 in library JRNLIB not found.") };

        final Exception e = JournalInfoRetrieval.classifyServiceProgramFailure(
                JournalInfoRetrieval.JOURNAL_SERVICE_LIB, "QjoRtvJrnReceiverInformation", messages);

        assertInstanceOf(JournalReceiverNotFoundException.class, e);
        assertEquals("CPF9801", ((JournalReceiverNotFoundException) e).getMessageId());
        assertTrue(e.getMessage().contains("RCV0001"));
    }

    @Test
    void notFoundMessageIdIsCaseInsensitive() {
        final AS400Message[] messages = { message("cpf9810", "Library JRNLIB not found.") };

        final Exception e = JournalInfoRetrieval.classifyServiceProgramFailure(
                JournalInfoRetrieval.JOURNAL_SERVICE_LIB, "QjoRtvJrnReceiverInformation", messages);

        assertInstanceOf(JournalReceiverNotFoundException.class, e);
    }

    @Test
    void notFoundIsDetectedAmongOtherMessages() {
        final AS400Message[] messages = {
                message("CPF1234", "Some diagnostic."),
                message("CPF9801", "Object RCV0001 in library JRNLIB not found.")
        };

        final Exception e = JournalInfoRetrieval.classifyServiceProgramFailure(
                JournalInfoRetrieval.JOURNAL_SERVICE_LIB, "QjoRtvJrnReceiverInformation", messages);

        assertInstanceOf(JournalReceiverNotFoundException.class, e);
    }

    @Test
    void otherFailureStaysTransient() {
        final AS400Message[] messages = { message("CPF9999", "Function check.") };

        final Exception e = JournalInfoRetrieval.classifyServiceProgramFailure(
                JournalInfoRetrieval.JOURNAL_SERVICE_LIB, "QjoRtvJrnReceiverInformation", messages);

        assertFalse(e instanceof JournalReceiverNotFoundException);
        assertEquals(Exception.class, e.getClass());
    }
}
