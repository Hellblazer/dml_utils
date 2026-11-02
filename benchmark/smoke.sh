#!/bin/sh
# Run a small end-to-end smoke test of the benchmark harness.
#
# Creates an isolated benchmark database, runs one measured run per chunking
# strategy (primary_key, blocks and ordered_key) with two threads, and removes the
# database again. The dataset is tiny and deterministic, and config.local.env is
# ignored, so the result does not depend on a developer's personal sweep.
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
export BENCH_IGNORE_LOCAL_CONFIG BENCH_CONTAINER BENCH_VOLUME BENCH_NETWORK \
    BENCH_HOST_PORT BENCH_ROWS BENCH_PK_COLUMNS BENCH_CHUNK_SIZE \
    BENCH_THREADS_FROM BENCH_THREADS_TO BENCH_RUNS BENCH_WARMUP_RUNS

cleanup() {
    if [ "$keep" = 1 ]; then
        echo "leaving the smoke database running (--keep)" >&2
    else
        "$here/teardown.sh"
    fi
}
trap cleanup EXIT INT TERM

"$here/setup.sh"

for strategy in primary_key blocks ordered_key; do
    BENCH_CHUNK_BY="$strategy" BENCH_CSV="smoke-$strategy.csv" "$here/run.sh"
done

rm -f "$here/results/smoke-primary_key.csv" "$here/results/smoke-blocks.csv" \
    "$here/results/smoke-ordered_key.csv"
echo "benchmark smoke test passed" >&2
