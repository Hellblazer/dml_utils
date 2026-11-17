#!/usr/bin/env bash
# Run the Maven release build, retrying once on a Testcontainers/PostgreSQL
# startup race.
#
# testcontainers-jooq-codegen-maven-plugin:0.0.4 overrides the PostgreSQL
# container's readiness wait with HostPortWaitStrategy, which returns as soon as
# the port is open. On a loaded CI runner jOOQ can then connect while Postgres is
# still initialising and the release fails with
#
#   FATAL:  the database system is starting up
#
# There is no configuration to change the wait strategy and no newer plugin
# release (the fix exists only on the plugin's main branch). Retry the release
# once when that specific transient failure (or the equivalent port-wait
# timeout) occurs; any other failure is reported with Maven's own exit status.
#
# deployAtEnd is important here: without it Maven publishes the parent POM
# during the parent module's deploy phase, before the dml_utils module has built.
# A codegen failure then leaves an orphan package version, and the retry fails
# with HTTP 409 because GitHub Packages does not allow replacing a version.
# deployAtEnd defers publication until the whole reactor has built.
#
# Usage: .github/scripts/maven-release-retry.sh [maven args...]
# The reusable release workflow appends -Drevision=... and -Dpostgres.image=...,
# which are forwarded to ./mvnw like any other argument.
#
# The script works from any directory: it resolves the repository root from its
# own path and runs Maven there, so the reactor (not just one module POM) is
# built regardless of the caller's working directory.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root" || exit 1
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

run_build() {
    "$repo_root/mvnw" --batch-mode -s "$repo_root/.github/scripts/settings.xml" \
        clean deploy -DdeployAtEnd=true ${1+"$@"}
}

# Run one attempt, teeing it to the shared log. Return Maven's status, not the
# pipeline's: with pipefail a tee failure (disk full, SIGPIPE on the CI log
# stream) must not turn a successful build into a failure.
attempt_build() {
    # Append: the retry must not truncate the first attempt's log, so the
    # diagnostics below and a post-mortem see both attempts.
    run_build ${1+"$@"} 2>&1 | tee -a "$log"
    return "${PIPESTATUS[0]}"
}

report_status() {
    if grep -q 'HTTP Status: 409' "$log"; then
        printf '\nA package version already exists; GitHub Packages cannot replace it.\n' >&2
        printf 'Delete the partial version or release a new version.\n' >&2
    fi
}

if attempt_build ${1+"$@"}; then
    exit 0
else
    maven_status=$?
fi

# Match the codegen's own FATAL line (not the bare text, which can appear while
# the connection recovered and an unrelated test failed) or the port-wait
# timeout.
if grep -Eq 'FATAL:[[:space:]]+the database system is starting up|Timed out waiting for container port' "$log"; then
    printf '\nTransient PostgreSQL container startup race; retrying the release once.\n' >&2
    if attempt_build ${1+"$@"}; then
        exit 0
    else
        maven_status=$?
    fi
fi

report_status
exit "$maven_status"
