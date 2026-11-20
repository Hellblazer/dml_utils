#!/usr/bin/env bash
# Shell tests for the Maven retry entry points, using a stubbed ./mvnw.
#
# Each case builds a throwaway repository tree containing the two entry points,
# the shared harness and a stub ./mvnw that logs its arguments and plays back
# one status/output pair per attempt. No Maven, Docker or network access.
#
# Run: bash .github/scripts/maven-retry-test.sh
set -euo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0
checks=0
out=""
status=0

fail() {
    failures=$((failures + 1))
    printf 'FAIL: %s\n' "$*" >&2
}

pass() {
    checks=$((checks + 1))
}

# assert_status EXPECTED MESSAGE
assert_status() {
    if [ "$status" -eq "$1" ]; then
        pass
    else
        fail "$2 (expected exit $1, got $status)"
        printf '%s\n' "$out" | sed 's/^/    | /' >&2
    fi
}

# assert_contains TEXT MESSAGE
assert_contains() {
    if printf '%s\n' "$out" | grep -Fq -- "$1"; then
        pass
    else
        fail "$2 (output does not contain '$1')"
        printf '%s\n' "$out" | sed 's/^/    | /' >&2
    fi
}

# assert_not_contains TEXT MESSAGE
assert_not_contains() {
    if printf '%s\n' "$out" | grep -Fq -- "$1"; then
        fail "$2 (output contains '$1')"
    else
        pass
    fi
}

# assert_calls REPO EXPECTED MESSAGE
assert_calls() {
    local count
    count="$(cat "$1/state/count" 2>/dev/null || echo 0)"
    if [ "$count" -eq "$2" ]; then
        pass
    else
        fail "$3 (expected $2 Maven call(s), got $count)"
    fi
}

# assert_argv REPO ATTEMPT MESSAGE -- EXPECTED_LINE...
# The stub logs PWD= then one ARG= line per argument, in order.
assert_argv() {
    local repo="$1" attempt="$2" message="$3"
    shift 4
    local file="$repo/state/call-$attempt.argv" expected actual
    expected="$work/expected.argv"
    actual="$work/actual.argv"
    printf '%s\n' "$@" > "$expected"
    if [ -f "$file" ]; then
        cat "$file" > "$actual"
    else
        : > "$actual"
    fi
    if diff -u "$expected" "$actual" > "$work/argv.diff"; then
        pass
    else
        fail "$message (argv differs)"
        sed 's/^/    | /' "$work/argv.diff" >&2
    fi
}

# make_repo NAME -> prints the new repository root
make_repo() {
    local dir="$work/$1"
    mkdir -p "$dir/.github/scripts" "$dir/state"
    cp "$here/maven-retry-lib.sh" "$here/maven-verify-retry.sh" \
        "$here/maven-release-retry.sh" "$here/settings.xml" "$dir/.github/scripts/"
    cat > "$dir/mvnw" <<'STUB'
#!/usr/bin/env bash
# Stub ./mvnw: log the call, replay this attempt's output and exit its status.
set -u
state="$STUB_STATE"
n=$(( $(cat "$state/count" 2>/dev/null || echo 0) + 1 ))
printf '%s\n' "$n" > "$state/count"
{
    printf 'PWD=%s\n' "$PWD"
    for arg in "$@"; do
        printf 'ARG=%s\n' "$arg"
    done
} > "$state/call-$n.argv"
if [ -f "$state/attempt-$n.output" ]; then
    cat "$state/attempt-$n.output"
fi
status=0
if [ -f "$state/attempt-$n.status" ]; then
    status="$(cat "$state/attempt-$n.status")"
fi
exit "$status"
STUB
    chmod +x "$dir/mvnw"
    printf '%s\n' "$dir"
}

# run REPO SCRIPT [ARGS...] -> sets out and status
run() {
    local dir="$1" script="$2"
    shift 2
    out=""
    status=0
    out="$(STUB_STATE="$dir/state" "$dir/.github/scripts/$script" "$@" 2>&1)" || status=$?
}

# attempt_status REPO ATTEMPT STATUS
attempt_status() {
    printf '%s\n' "$3" > "$1/state/attempt-$2.status"
}

# attempt_output REPO ATTEMPT TEXT
attempt_output() {
    printf '%s\n' "$3" > "$1/state/attempt-$2.output"
}

fatal_line='FATAL:  the database system is starting up'

# 1. A successful build runs once and exits 0.
dir="$(make_repo success)"
run "$dir" maven-verify-retry.sh
assert_status 0 "verify: success exits 0"
assert_calls "$dir" 1 "verify: success runs Maven once"
assert_not_contains "Transient" "verify: success does not print the retry notice"
assert_argv "$dir" 1 "verify: success forwards the goals" -- \
    "PWD=$dir" \
    "ARG=--batch-mode" \
    "ARG=-s" \
    "ARG=$dir/.github/scripts/settings.xml" \
    "ARG=clean" \
    "ARG=verify"

# 1b. A successful attempt that happens to print the transient marker is not
# retried: the marker only matters after a failure.
dir="$(make_repo success-with-marker)"
attempt_status "$dir" 1 0
attempt_output "$dir" 1 "$fatal_line"
run "$dir" maven-verify-retry.sh
assert_status 0 "verify: success with a marker exits 0"
assert_calls "$dir" 1 "verify: success with a marker is not retried"
assert_not_contains "Transient" "verify: success with a marker does not print the retry notice"

# 2. A non-transient failure is reported once with Maven's own status.
dir="$(make_repo failure)"
attempt_status "$dir" 1 7
attempt_output "$dir" 1 "BUILD FAILURE"
run "$dir" maven-verify-retry.sh
assert_status 7 "verify: non-transient failure propagates the status"
assert_calls "$dir" 1 "verify: non-transient failure is not retried"
assert_not_contains "Transient" "verify: non-transient failure does not print the retry notice"

# 3. The startup race retries once and succeeds.
dir="$(make_repo transient)"
attempt_status "$dir" 1 1
attempt_output "$dir" 1 "$fatal_line"
attempt_status "$dir" 2 0
run "$dir" maven-verify-retry.sh
assert_status 0 "verify: transient then success exits 0"
assert_calls "$dir" 2 "verify: transient then success retries exactly once"
assert_contains "Transient PostgreSQL container startup race; retrying the build once." \
    "verify: transient then success prints the retry notice"

# 4. Two transient failures report the second attempt's status.
dir="$(make_repo transient-twice)"
attempt_status "$dir" 1 1
attempt_output "$dir" 1 "$fatal_line"
attempt_status "$dir" 2 3
attempt_output "$dir" 2 "$fatal_line"
run "$dir" maven-verify-retry.sh
assert_status 3 "verify: transient twice propagates the second status"
assert_calls "$dir" 2 "verify: transient twice retries only once"

# 5. The port-wait timeout is the other accepted transient marker.
dir="$(make_repo port-wait)"
attempt_status "$dir" 1 1
attempt_output "$dir" 1 "Timed out waiting for container port to open"
attempt_status "$dir" 2 0
run "$dir" maven-verify-retry.sh
assert_status 0 "verify: port-wait timeout then success exits 0"
assert_calls "$dir" 2 "verify: port-wait timeout is retried"

# 6. Arguments are forwarded verbatim, including values with spaces.
dir="$(make_repo arguments)"
run "$dir" maven-verify-retry.sh "-Dmessage=a b" "-Dempty="
assert_status 0 "verify: arguments exit 0"
assert_argv "$dir" 1 "verify: arguments with spaces stay one argument" -- \
    "PWD=$dir" \
    "ARG=--batch-mode" \
    "ARG=-s" \
    "ARG=$dir/.github/scripts/settings.xml" \
    "ARG=clean" \
    "ARG=verify" \
    "ARG=-Dmessage=a b" \
    "ARG=-Dempty="

# 7. The entry point works from another directory and builds the repository.
# The repository and the caller's directory have distinct names: reusing one name
# would land the caller inside the repo and make the PWD assertion trivial.
dir="$(make_repo elsewhere-repo)"
mkdir -p "$work/elsewhere"
out=""
status=0
out="$(
    cd "$work/elsewhere"
    STUB_STATE="$dir/state" "$dir/.github/scripts/maven-verify-retry.sh" 2>&1
)" || status=$?
assert_status 0 "verify: runs from another directory"
assert_argv "$dir" 1 "verify: runs from another directory builds the repo root" -- \
    "PWD=$dir" \
    "ARG=--batch-mode" \
    "ARG=-s" \
    "ARG=$dir/.github/scripts/settings.xml" \
    "ARG=clean" \
    "ARG=verify"

# 7b. A failing report_failure hook must not replace Maven's status: the entry
# points run with errexit, so the harness has to swallow the hook's failure.
dir="$(make_repo hook-failure)"
attempt_status "$dir" 1 7
attempt_output "$dir" 1 "BUILD FAILURE"
cat > "$dir/.github/scripts/hook-failure.sh" <<'EOS'
#!/usr/bin/env bash
set -euo pipefail
. "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/maven-retry-lib.sh"
run_build() { "$repo_root/mvnw" --batch-mode clean verify "$@"; }
report_failure() { echo "diagnostics failed" >&2; return 9; }
maven_retry_run build "$@"
EOS
chmod +x "$dir/.github/scripts/hook-failure.sh"
run "$dir" hook-failure.sh
assert_status 7 "verify: a failing report_failure hook keeps Maven's status"
assert_calls "$dir" 1 "verify: a failing hook does not retry"
assert_contains "diagnostics failed" "verify: the failing hook's diagnostics are printed"

# 8. The release entry point deploys at the end of the reactor.
dir="$(make_repo release-goals)"
run "$dir" maven-release-retry.sh
assert_status 0 "release: success exits 0"
assert_argv "$dir" 1 "release: deploys with deployAtEnd" -- \
    "PWD=$dir" \
    "ARG=--batch-mode" \
    "ARG=-s" \
    "ARG=$dir/.github/scripts/settings.xml" \
    "ARG=clean" \
    "ARG=deploy" \
    "ARG=-DdeployAtEnd=true"

# 9. A 409 on the final failure explains the immutable package version.
dir="$(make_repo release-409)"
attempt_status "$dir" 1 5
attempt_output "$dir" 1 "HTTP Status: 409"
run "$dir" maven-release-retry.sh
assert_status 5 "release: 409 propagates the status"
assert_calls "$dir" 1 "release: 409 is not a transient failure"
assert_contains "A package version already exists" "release: 409 explains the immutable version"
assert_contains "Delete the partial version or release a new version." \
    "release: 409 suggests the fix"

# 10. The release retry notice names the release.
dir="$(make_repo release-transient)"
attempt_status "$dir" 1 1
attempt_output "$dir" 1 "$fatal_line"
attempt_status "$dir" 2 0
run "$dir" maven-release-retry.sh
assert_status 0 "release: transient then success exits 0"
assert_calls "$dir" 2 "release: transient then success retries once"
assert_contains "retrying the release once." "release: retry notice names the release"

# 11. A 409 after a transient retry still reports the package-version diagnostic.
dir="$(make_repo release-transient-409)"
attempt_status "$dir" 1 1
attempt_output "$dir" 1 "$fatal_line"
attempt_status "$dir" 2 5
attempt_output "$dir" 2 "HTTP Status: 409"
run "$dir" maven-release-retry.sh
assert_status 5 "release: transient then 409 propagates the final status"
assert_calls "$dir" 2 "release: transient then 409 retries once"
assert_contains "A package version already exists" "release: transient then 409 still explains 409"

if [ "$failures" -ne 0 ]; then
    printf '\n%d check(s) failed, %d passed\n' "$failures" "$checks" >&2
    exit 1
fi
printf '%d checks passed\n' "$checks"
