/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.TableId;

/**
 * Verifies {@link As400ConnectorConfig#getTableIncludeListMatchers()}, which lets schema discovery name the
 * {@code table.include.list} entries that resolved to no table on the source (issue #49). Those entries are
 * dropped by design - #33 made the include list an allow list - but the skip used to leave no trace at all,
 * so a table that was never captured because it does not exist looked exactly like one that works.
 *
 * <p>What matters here is that an entry is only ever reported as unresolved when the connector really is not
 * capturing it: the matchers must agree with the filters the connector actually applies.
 */
public class As400ConnectorConfigIncludeListMatchersTest {

    private As400ConnectorConfig configWith(String includeList) {
        return new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .with(As400ConnectorConfig.SCHEMA, "LIB1")
                .with(RelationalDatabaseConnectorConfig.TABLE_INCLUDE_LIST, includeList)
                .build());
    }

    /** What schema discovery does: an entry no captured table matches is an entry that resolved to nothing. */
    private static List<String> unresolvedAgainst(As400ConnectorConfig config, List<TableId> captured) {
        return config.getTableIncludeListMatchers().entrySet().stream()
                .filter(entry -> captured.stream().noneMatch(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Test
    public void everyConfiguredEntryGetsAMatcher() {
        final Map<String, Predicate<TableId>> matchers = configWith("LIB1.T1, LIB2.T2 ,LIB1.T3").getTableIncludeListMatchers();

        // keyed by the entry as configured, trimmed, and in the order given
        assertThat(matchers.keySet()).containsExactly("LIB1.T1", "LIB2.T2", "LIB1.T3");
    }

    @Test
    public void anEmptyIncludeListHasNoMatchers() {
        assertThat(configWith("").getTableIncludeListMatchers()).isEmpty();
    }

    @Test
    public void onlyTheEntriesWithNoMatchingTableAreReported() {
        final As400ConnectorConfig config = configWith("LIB1.PRESENT,LIB1.GONE,LIB2.ALSOGONE");

        final List<String> unresolved = unresolvedAgainst(config,
                List.of(new TableId("serverX", "LIB1", "PRESENT"), new TableId("serverX", "LIB1", "OTHER")));

        assertThat(unresolved).containsExactly("LIB1.GONE", "LIB2.ALSOGONE");
    }

    @Test
    public void nothingIsReportedWhenEveryEntryResolves() {
        final As400ConnectorConfig config = configWith("LIB1.T1,LIB2.T2");

        final List<String> unresolved = unresolvedAgainst(config,
                List.of(new TableId("serverX", "LIB1", "T1"), new TableId("serverX", "LIB2", "T2")));

        assertThat(unresolved).isEmpty();
    }

    /**
     * The false positive to avoid: a name with a regex metacharacter is escaped before the filters see it,
     * so the matcher has to be built from the same normalized form or a table that is being captured
     * perfectly well would be reported as missing on every start.
     */
    @Test
    public void anEntryWithARegexMetacharacterStillMatchesItsTable() {
        final As400ConnectorConfig config = configWith("LIB1.$SCHAR,LIB1.\"$QUOTED\"");

        final List<String> unresolved = unresolvedAgainst(config,
                List.of(new TableId("serverX", "LIB1", "$SCHAR"), new TableId("serverX", "LIB1", "$QUOTED")));

        assertThat(unresolved).isEmpty();
    }

    /**
     * The matchers exist to describe the filters, so they must not claim a table is captured when the
     * connector's own filter disagrees - which is what makes the reported list trustworthy.
     */
    @Test
    public void theMatchersAgreeWithTheFilterTheConnectorApplies() {
        final As400ConnectorConfig config = configWith("LIB1.T1,LIB1.$SCHAR,LIB1.GONE");
        final List<TableId> candidates = List.of(
                new TableId("serverX", "LIB1", "T1"),
                new TableId("serverX", "LIB1", "$SCHAR"),
                new TableId("serverX", "LIB1", "NOTINCLUDED"));

        for (final TableId candidate : candidates) {
            final boolean anyMatcherAccepts = config.getTableIncludeListMatchers().values().stream()
                    .anyMatch(matcher -> matcher.test(candidate));
            assertThat(anyMatcherAccepts)
                    .as("matcher verdict for %s must equal the connector's data collection filter", candidate)
                    .isEqualTo(config.getTableFilters().dataCollectionFilter().isIncluded(candidate));
        }
    }
}
