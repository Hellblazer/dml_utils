#!/usr/bin/env bash
# Run the Maven release build, retrying once on a Testcontainers/PostgreSQL
# startup race.
#
# The race and its rationale live in maven-retry-lib.sh, which owns the retry
# harness; this entry point defines what to run and the release-only diagnostic.
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
# The script works from any directory: the harness resolves the repository root
# from its own path and runs Maven there, so the reactor (not just one module
# POM) is built regardless of the caller's working directory.
set -euo pipefail

# shellcheck source=.github/scripts/maven-retry-lib.sh
. "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/maven-retry-lib.sh"

run_build() {
    "$repo_root/mvnw" --batch-mode -s "$repo_root/.github/scripts/settings.xml" \
        clean deploy -DdeployAtEnd=true "$@"
}

report_failure() {
    if grep -q 'HTTP Status: 409' "$maven_retry_log"; then
        printf '\nA package version already exists; GitHub Packages cannot replace it.\n' >&2
        printf 'Delete the partial version or release a new version.\n' >&2
    fi
}

maven_retry_run release "$@"
