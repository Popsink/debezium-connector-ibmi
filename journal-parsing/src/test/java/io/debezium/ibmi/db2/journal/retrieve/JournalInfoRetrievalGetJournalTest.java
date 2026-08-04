/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;

/**
 * Unit tests for {@link JournalInfoRetrieval#getJournal(AS400, String, List)} covering the
 * multi-library support: tables spread across libraries are allowed as long as they share a single
 * journal, and the multi-journal case is rejected with an explicit message.
 *
 * <p>Also covers {@link JournalInfoRetrieval#resolveJournal(AS400, String, List, boolean)}, which backs
 * {@code errors.tolerance}: a table with no journal fails startup by default and is dropped from the
 * journal filters when tolerated.</p>
 */
@ExtendWith(MockitoExtension.class)
class JournalInfoRetrievalGetJournalTest {

    @Mock
    AS400 as400;

    private final JournalInfo journalA = new JournalInfo("JRN", "JRNLIB", false);
    private final JournalInfo journalB = new JournalInfo("OTHERJRN", "JRNLIB", false);

    @Test
    void multipleLibrariesSharingOneJournalResolveToSingleJournal() throws Exception {
        // Given two tables in two different libraries that both journal to JRN
        final JournalInfoRetrieval retrieval = spy(new JournalInfoRetrieval(As400TextFactory.forCcsid(37), 0L, 0L, 0L));
        doReturn(journalA).when(retrieval).getJournal(as400, "LIB1", "T1");
        doReturn(journalA).when(retrieval).getJournal(as400, "LIB2", "T2");

        // When resolving the journal for the include list
        final JournalInfo result = retrieval.getJournal(as400, "LIB1",
                List.of(new FileFilter("LIB1", "T1"), new FileFilter("LIB2", "T2")));

        // Then the shared journal is returned without error
        assertEquals(journalA, result);
    }

    @Test
    void unjournaledTableFailsWhenNotTolerant() throws Exception {
        // Given one table that resolves and one whose journal cannot be retrieved
        final JournalInfoRetrieval retrieval = spy(new JournalInfoRetrieval(As400TextFactory.forCcsid(37), 0L, 0L, 0L));
        doReturn(journalA).when(retrieval).getJournal(as400, "LIB1", "T1");
        doThrow(new IllegalStateException("Journal not found for LIB1.VIEW1")).when(retrieval).getJournal(as400, "LIB1", "VIEW1");

        // When resolving with errors.tolerance=none
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> retrieval.resolveJournal(as400, "LIB1",
                        List.of(new FileFilter("LIB1", "T1"), new FileFilter("LIB1", "VIEW1")), false));

        // Then startup fails rather than silently never streaming that table, naming the offending table
        assertTrue(ex.getMessage().contains("unable to retrieve journal details"), ex.getMessage());
        assertTrue(ex.getMessage().contains("LIB1.VIEW1"), ex.getMessage());
    }

    @Test
    void unjournaledTableIsDroppedFromTheFiltersWhenTolerant() throws Exception {
        // Given one table that resolves and one whose journal cannot be retrieved
        final JournalInfoRetrieval retrieval = spy(new JournalInfoRetrieval(As400TextFactory.forCcsid(37), 0L, 0L, 0L));
        doReturn(journalA).when(retrieval).getJournal(as400, "LIB1", "T1");
        doThrow(new IllegalStateException("Journal not found for LIB1.VIEW1")).when(retrieval).getJournal(as400, "LIB1", "VIEW1");

        // When resolving with errors.tolerance=all
        final JournalInfoRetrieval.ResolvedJournal resolved = retrieval.resolveJournal(as400, "LIB1",
                List.of(new FileFilter("LIB1", "T1"), new FileFilter("LIB1", "VIEW1")), true);

        // Then the capturable table keeps streaming and the other is left out of the journal filters
        assertEquals(journalA, resolved.journalInfo());
        assertEquals(List.of(new FileFilter("LIB1", "T1")), resolved.includes());
    }

    @Test
    void allTablesUnjournaledFailsEvenWhenTolerant() throws Exception {
        // Given an include list where nothing can be captured
        final JournalInfoRetrieval retrieval = spy(new JournalInfoRetrieval(As400TextFactory.forCcsid(37), 0L, 0L, 0L));
        doThrow(new IllegalStateException("Journal not found for LIB1.T1")).when(retrieval).getJournal(as400, "LIB1", "T1");

        // When resolving with errors.tolerance=all
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> retrieval.resolveJournal(as400, "LIB1", List.of(new FileFilter("LIB1", "T1")), true));

        // Then the connector still refuses to start with nothing to capture
        assertTrue(ex.getMessage().contains("nothing to capture"), ex.getMessage());
    }

    @Test
    void multipleLibrariesWithDistinctJournalsFailWithExplicitMessage() throws Exception {
        // Given two tables in two libraries that journal to different journals
        final JournalInfoRetrieval retrieval = spy(new JournalInfoRetrieval(As400TextFactory.forCcsid(37), 0L, 0L, 0L));
        doReturn(journalA).when(retrieval).getJournal(as400, "LIB1", "T1");
        doReturn(journalB).when(retrieval).getJournal(as400, "LIB2", "T2");

        // When resolving the journal for the include list
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> retrieval.getJournal(as400, "LIB1",
                        List.of(new FileFilter("LIB1", "T1"), new FileFilter("LIB2", "T2"))));

        // Then the failure clearly names the multi-journal situation and the libraries involved
        assertTrue(ex.getMessage().contains("more than one journal"), ex.getMessage());
        assertTrue(ex.getMessage().contains("LIB1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("LIB2"), ex.getMessage());
    }
}
