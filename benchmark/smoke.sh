#!/bin/sh
# Run a small end-to-end smoke test of the benchmark harness.
#
# Creates an isolated benchmark database, runs one measured run per chunking
# strategy (primary_key, blocks and ordered_key) with two threads, then one
# partition-wise run for each strategy, and removes the database again.
# The dataset is tiny and deterministic, and config.local.env is ignored, so the
# result does not depend on a developer's personal sweep.
#
# Intended for CI (see .github/workflows/benchmark-smoke.yml) and for checking a
# changelog or benchmark change locally. Pass --keep to leave the database
# running for inspection.
#
# Usage: benchmark/smoke.sh [--keep]

set -eu

here="$(cd "$(dirname "$0")" && pwd)"

keep=0
for arg in "$@"; do
    case "$arg" in
        --keep) keep=1 ;;
        -h|--help) echo "usage: $0 [--keep]" >&2; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# Isolate the smoke database from a developer's benchmark stack, and keep the
# dataset and sweep independent of config.env's published defaults and of
# config.local.env.
BENCH_IGNORE_LOCAL_CONFIG=1
# Scope the names to this invocation so two concurrent smoke runs (or a run next
# to a developer's benchmark stack) cannot stop each other's database.
suffix="$$"
BENCH_CONTAINER="dml_utils_benchmark_smoke_pg_$suffix"
BENCH_VOLUME="dml_utils_benchmark_smoke_pgdata_$suffix"
BENCH_NETWORK="dml_utils_benchmark_smoke_net_$suffix"
# No published port: the names are the isolation point, and two concurrent runs
# must not collide on a fixed host port.
BENCH_HOST_PORT=
BENCH_ROWS=2000
BENCH_PK_COLUMNS=3
BENCH_CHUNK_SIZE=500
BENCH_THREADS_FROM=1
BENCH_THREADS_TO=2
BENCH_RUNS=1
BENCH_WARMUP_RUNS=0
BENCH_PARTITIONED_LEAVES=3
# Scope the output directory by the same invocation suffix as the Docker
# resources, so two concurrent smoke runs cannot delete or append to each
# other's CSVs/report; the assertions derive every path from this variable.
BENCH_RESULTS_DIR="results/smoke_$suffix"
BENCH_REPORT=report.md
export BENCH_IGNORE_LOCAL_CONFIG BENCH_CONTAINER BENCH_VOLUME BENCH_NETWORK \
    BENCH_HOST_PORT BENCH_ROWS BENCH_PK_COLUMNS BENCH_CHUNK_SIZE \
    BENCH_THREADS_FROM BENCH_THREADS_TO BENCH_RUNS BENCH_WARMUP_RUNS \
    BENCH_PARTITIONED_LEAVES BENCH_RESULTS_DIR BENCH_REPORT

cleanup() {
    status=$?
    # Do not let an EXIT trap re-enter; a signal trap exits below.
    trap - EXIT INT TERM
    if [ "$keep" = 1 ]; then
        echo "leaving the smoke database running (--keep)" >&2
    else
        # Do not let a cleanup failure be swallowed, but do not overwrite a
        # non-zero status from the test itself.
        teardown_status=0
        "$here/teardown.sh" || teardown_status=$?
        if [ "$teardown_status" -ne 0 ]; then
            echo "teardown.sh failed" >&2
            [ "$status" -eq 0 ] && status=$teardown_status
        fi
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$here/setup.sh"

results_dir="$here/$BENCH_RESULTS_DIR"
# The directory is per-invocation; start clean rather than inheriting rows from
# a previous run with the same PID (PIDs can be reused).
rm -rf "$results_dir"
mkdir -p "$results_dir"

# The documented host-file recipe: psql.sh must stream a host file into the
# container, which cannot see host paths itself.
printf 'SELECT 1;\n' > "$results_dir/psql-probe.sql"
"$here/psql.sh" -f "$results_dir/psql-probe.sql" >/dev/null

# A trailing -f with no file must fail rather than consume a rewritten copy of
# an earlier argument (the rewrite appends copies to the same argument list).
if "$here/psql.sh" -c 'SELECT 1' -f >/dev/null 2>&1; then
    echo "psql.sh accepted a trailing -f without a file" >&2
    exit 1
fi

for strategy in primary_key blocks ordered_key; do
    BENCH_PARTITION_WISE=0 BENCH_CHUNK_BY="$strategy" BENCH_CSV="smoke-$strategy.csv" \
        "$here/run.sh" --reset
done

# One run per strategy inside its partitioned fixture (the range-partitioned
# bigint copy for primary_key/blocks, the hash-partitioned UUID copy for
# ordered_key), so the leaf manifest, per-leaf calculation and leaf rendering are
# exercised end to end too.
for strategy in primary_key blocks ordered_key; do
    BENCH_PARTITION_WISE=1 BENCH_CHUNK_BY="$strategy" \
        BENCH_CSV="smoke-partitioned-$strategy.csv" "$here/run.sh" --reset
done

# The report must keep the partition-wise rows in their own group and keep every
# thread value (the CSV grouping keys on the flag and the thread count).
report_dir="$results_dir/smoke-report"
rm -rf "$report_dir"
mkdir -p "$report_dir"
{
    head -n 1 "$results_dir/smoke-primary_key.csv"
    tail -n +2 "$results_dir/smoke-primary_key.csv"
    tail -n +2 "$results_dir/smoke-blocks.csv"
    tail -n +2 "$results_dir/smoke-ordered_key.csv"
    tail -n +2 "$results_dir/smoke-partitioned-primary_key.csv"
    tail -n +2 "$results_dir/smoke-partitioned-blocks.csv"
    tail -n +2 "$results_dir/smoke-partitioned-ordered_key.csv"
} > "$report_dir/combined.csv"
BENCH_RESULTS_DIR="$BENCH_RESULTS_DIR/smoke-report" BENCH_CSV=combined.csv \
    "$here/report.sh" >/dev/null
# A non-partitioned group must exist (the first loop pins
# BENCH_PARTITION_WISE=0), and the partition-wise groups must stay separate.
grep -q '^### strategy `primary_key`, key k1+k2+k3, chunk_size 500 rows$' \
    "$report_dir/report.md"
grep -q '^### strategy `blocks`, key k1+k2+k3, chunk_size 500 blocks$' \
    "$report_dir/report.md"
grep -q '^### strategy `primary_key`, key k1+k2+k3, chunk_size 500 rows (partition-wise)$' \
    "$report_dir/report.md"
grep -q '^### strategy `blocks`, key k1+k2+k3, chunk_size 500 blocks (partition-wise)$' \
    "$report_dir/report.md"
grep -q '^### strategy `ordered_key`, key created_at+id, chunk_size 500 rows (partition-wise)' \
    "$report_dir/report.md"
# Every group's table carries both thread values, each one exactly once. A page-
# wide grep or a count of two rows would pass with a duplicated value.
awk '/^### strategy `/{group = $0; t1[group] = 0; t2[group] = 0; next}
     /^\| 1 \|/{t1[group]++}
     /^\| 2 \|/{t2[group]++}
     END {
         for (g in t1)
             if (t1[g] != 1 || t2[g] != 1) {
                 print "missing or duplicated thread row in " g > "/dev/stderr"
                 exit 1
             }
     }' "$report_dir/report.md"
rm -rf "$report_dir"

# Remove the whole per-invocation output directory (the report directory is
# inside it); the results parent and published snapshots are untouched.
rm -rf "$results_dir"
echo "benchmark smoke test passed" >&2
