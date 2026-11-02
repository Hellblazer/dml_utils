#!/bin/sh
# Run the thread-scaling benchmark.
#
# For each thread value from BENCH_THREADS_FROM to BENCH_THREADS_TO it runs
# BENCH_WARMUP_RUNS unrecorded warm-ups, then BENCH_RUNS measured runs of
# dml_utils.run_migration_chunks, and appends one CSV row per measured run to
# results/<BENCH_CSV>. Results accumulate across invocations; pass --reset to
# start a fresh CSV. Format it with report.sh.
#
# Usage: benchmark/run.sh [--reset]

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
. "$here/config.env"
# Optional personal overrides, kept out of git (copy config.local.env.example).
# BENCH_IGNORE_LOCAL_CONFIG=1 skips them, so benchmark/smoke.sh is not affected
# by a developer's personal sweep.
if [ -f "$here/config.local.env" ] && [ "${BENCH_IGNORE_LOCAL_CONFIG:-0}" != 1 ]; then
    . "$here/config.local.env"
fi

# The chunk-size unit and source/target/workload are determined by the strategy.
case "$BENCH_CHUNK_BY" in
    primary_key)
        chunk_unit="rows"
        run_table="$BENCH_SOURCE_TABLE"
        run_target="$BENCH_TARGET_TABLE"
        run_sql="$BENCH_SQL"
        boundary_clause=""
        ;;
    ordered_key)
        chunk_unit="rows"
        run_table="$BENCH_ORDERED_SOURCE_TABLE"
        run_target="$BENCH_ORDERED_TARGET_TABLE"
        run_sql="$BENCH_ORDERED_SQL"
        # psql substitutes this raw (not quoted), so an empty value leaves the
        # call unchanged and the ordered variant adds the index parameter.
        boundary_clause=", i_boundary_key_index => '$BENCH_ORDERED_INDEX'"
        ;;
    blocks)
        chunk_unit="blocks"
        run_table="$BENCH_SOURCE_TABLE"
        run_target="$BENCH_TARGET_TABLE"
        run_sql="$BENCH_SQL"
        boundary_clause=""
        ;;
    *)
        echo "BENCH_CHUNK_BY must be primary_key, ordered_key or blocks: $BENCH_CHUNK_BY" >&2
        exit 2
        ;;
esac

# The key column names, joined by '+' so a report group key has no spaces.
if [ "$BENCH_CHUNK_BY" = ordered_key ]; then
    pk_columns_csv="created_at+id"
else
    pk_columns_csv=""
    _pk_i=0
    while [ "$_pk_i" -lt "$BENCH_PK_COLUMNS" ]; do
        _pk_i=$((_pk_i + 1))
        pk_columns_csv="${pk_columns_csv:+$pk_columns_csv+}k$_pk_i"
    done
fi

usage() {
    echo "usage: $0 [--reset]" >&2
    echo "  --reset starts a fresh results CSV; to recreate the benchmark" >&2
    echo "  database, run ./setup.sh [--refresh] instead." >&2
}

reset=0
for arg in "$@"; do
    case "$arg" in
        --reset) reset=1 ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "unknown argument: $arg" >&2
            echo "  to recreate the benchmark database, run ./setup.sh --refresh" >&2
            usage
            exit 2
            ;;
    esac
done

command -v docker >/dev/null 2>&1 || {
    echo "docker is required but was not found on PATH" >&2
    exit 1
}

if ! docker inspect "$BENCH_CONTAINER" >/dev/null 2>&1 \
    || [ "$(docker inspect -f '{{.State.Running}}' "$BENCH_CONTAINER" 2>/dev/null)" != "true" ]; then
    echo "benchmark container $BENCH_CONTAINER is not running; run benchmark/setup.sh first" >&2
    exit 1
fi

results_dir="$here/$BENCH_RESULTS_DIR"
csv="$results_dir/$BENCH_CSV"
mkdir -p "$results_dir"
if [ "$reset" = 1 ] || [ ! -f "$csv" ]; then
    printf 'strategy,chunk_unit,chunk_size,threads,run,chunks,range_seconds,chunk_seconds,total_seconds,pk_columns\n' > "$csv"
fi

psql_run() {
    docker exec "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
}
# psql -c does not interpolate :variables, so the parameterised statements are
# fed on stdin with -f -.
psql_stdin() {
    docker exec -i "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
}

# run_once <threads> <kind> <run_no> <record:0|1>
run_once() {
    threads="$1"
    kind="$2"
    run_no="$3"
    record="$4"

    label="bench-${threads}-${kind}-${run_no}-$(date +%s%N)"

    if [ "$BENCH_TRUNCATE_TARGET" = 1 ]; then
        psql_run -q -v ON_ERROR_STOP=1 \
            -c "TRUNCATE ${BENCH_SCHEMA}.${run_target}"
    fi

    # pg_background caps concurrent workers per session at
    # pg_background.max_workers (16 by default), independently of the server's
    # max_worker_processes, so raise it to this run's thread count before
    # launching the workers. It is a user-settable GUC, so this needs no server
    # change.
    psql_stdin -q -v ON_ERROR_STOP=1 \
        -v sql="$run_sql" \
        -v schema="$BENCH_SCHEMA" \
        -v table="$run_table" \
        -v label="$label" \
        -v chunk="$BENCH_CHUNK_SIZE" \
        -v threads="$threads" \
        -v chunk_by="$BENCH_CHUNK_BY" \
        -v boundary_clause="$boundary_clause" \
        -f - >/dev/null <<'SQL'
SET pg_background.max_workers = :threads;
SELECT dml_utils.run_migration_chunks(
    i_sql_text => :'sql',
    i_driving_table_schema_name => :'schema',
    i_driving_table_name => :'table',
    i_label => :'label',
    i_chunk_size => :'chunk',
    i_threads => :'threads',
    i_chunk_by => :'chunk_by' :boundary_clause);
SQL

    # chunks, range_seconds, chunk_seconds, total_seconds. The chunk count is the
    # boundary count minus the terminal boundary.
    row="$(psql_stdin -At -F, -v ON_ERROR_STOP=1 -v label="$label" -f - <<'SQL'
SELECT (SELECT count(*) - 1
        FROM dml_utils_data.migration_boundary AS b
        JOIN dml_utils_data.migration_run AS r ON r.run_id = b.run_id
        WHERE r.label = :'label'),
       EXTRACT(EPOCH FROM (boundaries_calculated_at - started_at)),
       EXTRACT(EPOCH FROM (completed_at - boundaries_calculated_at)),
       EXTRACT(EPOCH FROM (completed_at - started_at))
FROM dml_utils_data.migration_run
WHERE label = :'label';
SQL
)"

    if [ "$record" = 1 ]; then
        printf '%s,%s,%s,%s,%s,%s,%s\n' "$BENCH_CHUNK_BY" "$chunk_unit" "$BENCH_CHUNK_SIZE" "$threads" "$run_no" "$row" "$pk_columns_csv" >> "$csv"
        echo "strategy=$BENCH_CHUNK_BY threads=$threads run=$run_no chunks=$(printf '%s' "$row" | cut -d, -f1) range=$(printf '%s' "$row" | cut -d, -f2)s chunk=$(printf '%s' "$row" | cut -d, -f3)s total=$(printf '%s' "$row" | cut -d, -f4)s" >&2
    else
        echo "strategy=$BENCH_CHUNK_BY threads=$threads warmup=$run_no done" >&2
    fi
}

# Report the dataset the sweep runs against.
src_rows="$(psql_run -At -c "SELECT count(*) FROM ${BENCH_SCHEMA}.${run_table}")"
if [ "$BENCH_CHUNK_BY" = ordered_key ]; then
    key_desc="ordered key ${pk_columns_csv}"
else
    key_desc="${BENCH_PK_COLUMNS} pk column(s)"
fi
echo "sweep: strategy=${BENCH_CHUNK_BY} (${chunk_unit}), ${key_desc}, ${src_rows} rows, threads ${BENCH_THREADS_FROM}..${BENCH_THREADS_TO}, ${BENCH_RUNS} measured + ${BENCH_WARMUP_RUNS} warm-up run(s) each" >&2
threads="$BENCH_THREADS_FROM"
while [ "$threads" -le "$BENCH_THREADS_TO" ]; do
    warmup=1
    while [ "$warmup" -le "$BENCH_WARMUP_RUNS" ]; do
        run_once "$threads" warmup "$warmup" 0
        warmup=$((warmup + 1))
    done
    run_no=1
    while [ "$run_no" -le "$BENCH_RUNS" ]; do
        run_once "$threads" run "$run_no" 1
        run_no=$((run_no + 1))
    done
    threads=$((threads + 1))
done

echo "results appended to $csv" >&2
