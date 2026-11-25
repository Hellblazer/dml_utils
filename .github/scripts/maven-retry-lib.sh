#!/usr/bin/env bash
# Shared retry harness for .github/scripts/maven-*-retry.sh. Sourced, not run.
#
# testcontainers-jooq-codegen-maven-plugin:0.0.4 overrides the PostgreSQL
# container's readiness wait with HostPortWaitStrategy, which returns as soon as
# the port is open. On a loaded CI runner jOOQ can then connect while Postgres is
# still initialising and the build fails with
#
#   FATAL:  the database system is starting up
#
# There is no configuration to change the wait strategy and no newer plugin
# release (the fix exists only on the plugin's main branch), so the entry points
# retry once when that specific transient failure (or the equivalent port-wait
# timeout) occurs. Any other failure is reported with Maven's own exit status.
#
# The sourcing entry point defines:
#
#   run_build [args...]   runs one Maven attempt from the repository root and
#                         returns its status; it may use $repo_root
#
# and may define:
#
#   report_failure        extra diagnostics after the final failure; it may read
#                         the combined log at $maven_retry_log
#
# It then calls:
#
#   maven_retry_run LABEL [args...]
#
# where LABEL is used in the retry message ("build" or "release"). The function
# exits the process with the final Maven status, so the entry point is a thin
# definition of run_build plus this call.
#
# The repository root is resolved from this file, so the entry points work from
# any directory and always build the whole reactor (not just one module POM).

# Repo root and log are globals: run_build and report_failure read them.
repo_root=""
maven_retry_log=""

# Run one attempt, teeing it to the shared log. Return Maven's status, not the
# pipeline's: with pipefail a tee failure (disk full, SIGPIPE on the CI log
# stream) must not turn a successful build into a failure.
maven_retry_attempt() {
    # Append: the retry must not truncate the first attempt's log, so the
    # diagnostics and a post-mortem see both attempts.
    run_build "$@" 2>&1 | tee -a "$maven_retry_log"
    return "${PIPESTATUS[0]}"
}

maven_retry_run() {
    local label="$1"
    shift

    if ! declare -F run_build >/dev/null; then
        printf 'maven_retry_run: the entry point must define run_build\n' >&2
        exit 1
    fi

    repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
    cd "$repo_root" || exit 1
    maven_retry_log="$(mktemp)"
    trap 'rm -f "$maven_retry_log"' EXIT

    local maven_status
    if maven_retry_attempt "$@"; then
        exit 0
    else
        maven_status=$?
    fi

    # Match the codegen's own FATAL line (not the bare text, which can appear
    # while the connection recovered and an unrelated test failed) or the
    # port-wait timeout.
    if grep -Eq 'FATAL:[[:space:]]+the database system is starting up|Timed out waiting for container port' \
        "$maven_retry_log"; then
        printf '\nTransient PostgreSQL container startup race; retrying the %s once.\n' \
            "$label" >&2
        if maven_retry_attempt "$@"; then
            exit 0
        else
            maven_status=$?
        fi
    fi

    if declare -F report_failure >/dev/null; then
        # Diagnostics are best-effort: the entry points run with errexit, so an
        # unprotected hook failure would replace Maven's status with the hook's.
        report_failure || true
    fi
    exit "$maven_status"
}
