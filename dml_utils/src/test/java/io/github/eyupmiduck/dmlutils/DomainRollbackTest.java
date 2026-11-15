package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the {@code 004-create-domains} rollback drops every type and
 * domain its forward changeset creates, so a rollback (or a re-apply after one)
 * cannot leave an orphan behind and fail with "type already exists".
 */
class DomainRollbackTest {

    private static final String FORWARD =
            "db/changelog/changes/sql_changes/004-create-domains.sql";
    private static final String ROLLBACK =
            "db/changelog/changes/rollback/004-create-domains-rollback.sql";

    private static final Pattern CREATED = Pattern.compile(
            "^\\s*CREATE\\s+(TYPE|DOMAIN)\\s+(dml_utils_data\\.[a-z0-9_]+)",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private static final Pattern DROPPED = Pattern.compile(
            "^\\s*DROP\\s+(TYPE|DOMAIN)\\s+IF\\s+EXISTS\\s+(dml_utils_data\\.[a-z0-9_]+)",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT) + " "
                    + matcher.group(2).toLowerCase(java.util.Locale.ROOT));
        }
        return found;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> missing = new LinkedHashSet<>(left);
        missing.removeAll(right);
        return missing;
    }

    /**
     * Every {@code CREATE TYPE}/{@code CREATE DOMAIN} in the forward changeset has
     * a matching {@code DROP ... IF EXISTS} in its rollback.
     */
    @Test
    void rollbackDropsEveryTypeAndDomainTheChangesetCreates() throws Exception {
        Set<String> created = matches(CREATED, TestResources.read(FORWARD));
        Set<String> dropped = matches(DROPPED, TestResources.read(ROLLBACK));

        assertTrue(created.containsAll(Set.of(
                        "TYPE dml_utils_data.migration_key",
                        "TYPE dml_utils_data.chunking_strategy",
                        "DOMAIN dml_utils_data.positive_integer",
                        "DOMAIN dml_utils_data.non_negative_integer",
                        "DOMAIN dml_utils_data.non_null_text",
                        "DOMAIN dml_utils_data.non_null_boolean")),
                "the forward changeset should create all six objects, found " + created);
        assertTrue(dropped.containsAll(created),
                "the rollback must drop every created object; missing "
                        + difference(created, dropped));
    }
}
