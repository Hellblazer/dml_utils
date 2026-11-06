#!/bin/sh
# Run a small end-to-end smoke test of the benchmark harness.
#
# Creates an isolated benchmark database, runs one measured run per chunking
# strategy (primary_key, blocks and ordered_key) with two threads, then one
# partition-wise run for primary_key and blocks, and removes the database again.
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
BENCH_CONTAINER=dml_utils_benchmark_smoke_pg
BENCH_VOLUME=dml_utils_benchmark_smoke_pgdata
BENCH_NETWORK=dml_utils_benchmark_smoke_net
BENCH_HOST_PORT=55434
BENCH_ROWS=2000
BENCH_PK_COLUMNS=3
BENCH_CHUNK_SIZE=500
BENCH_THREADS_FROM=1
BENCH_THREADS_TO=2
BENCH_RUNS=1
BENCH_WARMUP_RUNS=0
BENCH_PARTITIONED_LEAVES=3
# Pin the output directory so an exported BENCH_RESULTS_DIR/BENCH_REPORT cannot
# send the smoke rows somewhere the assertions do not read.
BENCH_RESULTS_DIR=results
export BENCH_IGNORE_LOCAL_CONFIG BENCH_CONTAINER BENCH_VOLUME BENCH_NETWORK \
    BENCH_HOST_PORT BENCH_ROWS BENCH_PK_COLUMNS BENCH_CHUNK_SIZE \
    BENCH_THREADS_FROM BENCH_THREADS_TO BENCH_RUNS BENCH_WARMUP_RUNS \
    BENCH_PARTITIONED_LEAVES BENCH_RESULTS_DIR

cleanup() {
    status=$?
    # Do not let an EXIT trap re-enter; a signal trap exits below.
    trap - EXIT INT TERM
    if [ "$keep" = 1 ]; then
        echo "leaving the smoke database running (--keep)" >&2
    else
        "$here/teardown.sh" || echo "teardown.sh failed" >&2
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$here/setup.sh"

results_dir="$here/$BENCH_RESULTS_DIR"
# Start from a clean slate: an interrupted earlier smoke run must not leave rows
# the report then aggregates.
rm -f "$results_dir/smoke-"*.csv

for strategy in primary_key blocks ordered_key; do
    BENCH_CHUNK_BY="$strategy" BENCH_CSV="smoke-$strategy.csv" \
        "$here/run.sh" --reset
done

# One run per partition-wise-capable strategy, inside the range-partitioned
# fixture, so the leaf manifest, per-leaf calculation and leaf rendering are
# exercised end to end too.
for strategy in primary_key blocks; do
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
    tail -n +2 "$results_dir/smoke-partitioned-primary_key.csv"
} > "$report_dir/combined.csv"
BENCH_RESULTS_DIR="$BENCH_RESULTS_DIR/smoke-report" BENCH_CSV=combined.csv \
    "$here/report.sh" >/dev/null
grep -q '^### strategy `primary_key`' "$report_dir/report.md"
grep -q '(partition-wise)' "$report_dir/report.md"
grep -q '^| 2 |' "$report_dir/report.md"
rm -rf "$report_dir"

rm -f "$results_dir/smoke-primary_key.csv" "$results_dir/smoke-blocks.csv" \
    "$results_dir/smoke-ordered_key.csv" \
    "$results_dir/smoke-partitioned-primary_key.csv" \
    "$results_dir/smoke-partitioned-blocks.csv"
echo "benchmark smoke test passed" >&2
