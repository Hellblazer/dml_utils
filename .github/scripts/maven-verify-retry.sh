#!/usr/bin/env bash
# Run the Maven build, retrying once on a Testcontainers/PostgreSQL startup race.
#
# The race and its rationale live in maven-retry-lib.sh, which owns the retry
# harness; this entry point only defines what to run.
#
# Usage: .github/scripts/maven-verify-retry.sh [maven args...]
# The reusable Maven workflow appends -Dpostgres.image=..., which is forwarded
# to ./mvnw like any other argument.
#
# The script works from any directory: the harness resolves the repository root
# from its own path and runs Maven there, so the reactor (not just one module
# POM) is built regardless of the caller's working directory.
set -euo pipefail

# shellcheck source=.github/scripts/maven-retry-lib.sh
. "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/maven-retry-lib.sh"

run_build() {
    "$repo_root/mvnw" --batch-mode -s "$repo_root/.github/scripts/settings.xml" \
        clean verify "$@"
}

maven_retry_run build "$@"
