#!/usr/bin/env bash
# Run the Maven build, retrying once on a Testcontainers/PostgreSQL startup race.
#
# testcontainers-jooq-codegen-maven-plugin:0.0.4 overrides the PostgreSQL
# container's readiness wait with HostPortWaitStrategy, which returns as soon as
# the port is open. On a loaded CI runner jOOQ can then connect while Postgres is
# still initialising and the codegen fails with
#
#   FATAL:  the database system is starting up
#
# There is no configuration to change the wait strategy and no newer plugin
# release (the fix exists only on the plugin's main branch). Retry the build once
# when that specific transient failure (or the equivalent port-wait timeout)
# occurs; any other failure is reported with Maven's own exit status.
#
# Usage: .github/scripts/maven-verify-retry.sh [maven args...]
# The reusable Maven workflow appends -Dpostgres.image=..., which is forwarded
# to ./mvnw like any other argument.
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
        clean verify ${1+"$@"}
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

if attempt_build ${1+"$@"}; then
    exit 0
else
    maven_status=$?
fi

# Match the codegen's own FATAL line (not the bare text, which can appear while
# the connection recovered and an unrelated test failed) or the port-wait
# timeout.
if grep -Eq 'FATAL:[[:space:]]+the database system is starting up|Timed out waiting for container port' "$log"; then
    printf '\nTransient PostgreSQL container startup race; retrying the build once.\n' >&2
    if attempt_build ${1+"$@"}; then
        exit 0
    else
        maven_status=$?
    fi
fi

exit "$maven_status"
