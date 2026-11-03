#!/bin/sh
# Format the accumulated benchmark results into a readable Markdown page.
#
# Reads results/<BENCH_CSV> (written by run.sh), captures the hardware and the
# PostgreSQL settings used, and writes results/<BENCH_REPORT>. The results are
# grouped by the chunking strategy, key, chunk size and partition-wise flag
# recorded in each row, with one table and one SVG chart per group. Re-runnable:
# it only reads the CSV, so the report can be regenerated at any time.
#
# Usage: benchmark/report.sh

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
. "$here/config.env"
# Optional personal overrides, kept out of git (copy config.local.env.example).
# BENCH_IGNORE_LOCAL_CONFIG=1 skips them, so benchmark/smoke.sh is not affected
# by a developer's personal sweep.
if [ -f "$here/config.local.env" ] && [ "${BENCH_IGNORE_LOCAL_CONFIG:-0}" != 1 ]; then
    . "$here/config.local.env"
fi

results_dir="$here/$BENCH_RESULTS_DIR"
csv="$results_dir/$BENCH_CSV"
report="$results_dir/$BENCH_REPORT"

if [ ! -f "$csv" ]; then
    echo "no results at $csv; run benchmark/run.sh first" >&2
    exit 1
fi

mkdir -p "$results_dir"

# --- Hardware and software --------------------------------------------------
hardware_file="$results_dir/hardware.txt"
{
    echo "captured: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "host: $(hostname 2>/dev/null || echo unknown)"
    echo
    uname -a
    echo
    if command -v lscpu >/dev/null 2>&1; then lscpu; fi
    echo
    echo "nproc: $(nproc 2>/dev/null || echo unknown)"
    echo
    if command -v free >/dev/null 2>&1; then free -h; fi
    echo
    if command -v df >/dev/null 2>&1; then df -h "$here"; fi
    echo
    if command -v docker >/dev/null 2>&1; then
        docker version --format 'docker client {{.Client.Version}} / server {{.Server.Version}}' 2>/dev/null || true
    fi
} > "$hardware_file" 2>&1

# --- PostgreSQL settings ----------------------------------------------------
settings_file="$results_dir/postgres-settings.txt"
if command -v docker >/dev/null 2>&1 \
    && [ "$(docker inspect -f '{{.State.Running}}' "$BENCH_CONTAINER" 2>/dev/null)" = "true" ]; then
    docker exec "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" -P pager=off -c "
        SELECT name, setting, coalesce(unit, '') AS unit
        FROM pg_settings
        WHERE name IN ('server_version', 'max_worker_processes', 'shared_buffers',
                       'max_connections', 'work_mem', 'maintenance_work_mem',
                       'effective_cache_size', 'wal_level', 'fsync',
                       'synchronous_commit', 'full_page_writes', 'autovacuum',
                       'checkpoint_timeout', 'random_page_cost', 'seq_page_cost')
        ORDER BY name" > "$settings_file" 2>&1 || true
else
    echo "(benchmark container not running; settings unavailable)" > "$settings_file"
fi

# --- Dataset ----------------------------------------------------------------
# Rows and blocks of the source table, if the container is running.
dataset_file="$results_dir/dataset.txt"
if command -v docker >/dev/null 2>&1 \
    && [ "$(docker inspect -f '{{.State.Running}}' "$BENCH_CONTAINER" 2>/dev/null)" = "true" ]; then
    docker exec "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" -At -F' | ' -c "
        SELECT 'rows', count(*)::text FROM ${BENCH_SCHEMA}.${BENCH_SOURCE_TABLE}
        UNION ALL
        SELECT 'blocks', (pg_relation_size('${BENCH_SCHEMA}.${BENCH_SOURCE_TABLE}'::regclass)
                          / current_setting('block_size')::bigint)::text
        UNION ALL
        SELECT 'ordered_rows', count(*)::text FROM ${BENCH_SCHEMA}.${BENCH_ORDERED_SOURCE_TABLE}
        UNION ALL
        SELECT 'partitioned_rows', count(*)::text FROM ${BENCH_SCHEMA}.${BENCH_PARTITIONED_SOURCE_TABLE}
        UNION ALL
        SELECT 'partitioned_blocks',
               ((SELECT coalesce(sum(pg_relation_size(leaf.relid)), 0)::bigint
                 FROM pg_partition_tree('${BENCH_SCHEMA}.${BENCH_PARTITIONED_SOURCE_TABLE}'::regclass) AS leaf
                 WHERE leaf.isleaf) / current_setting('block_size')::bigint)::text
        UNION ALL
        SELECT 'partitioned_leaves', count(*)::text
        FROM pg_partition_tree('${BENCH_SCHEMA}.${BENCH_PARTITIONED_SOURCE_TABLE}'::regclass)
        WHERE isleaf
        ORDER BY 1" > "$dataset_file" 2>&1 || true
else
    echo "(benchmark container not running; dataset unavailable)" > "$dataset_file"
fi

# Source rows/blocks, for the per-chunk averages (empty when unavailable).
src_rows="$(awk -F'|' '{ gsub(/ /, "", $1); if ($1 == "rows") { gsub(/ /, "", $2); print $2 } }' \
    "$dataset_file" 2>/dev/null || true)"
src_blocks="$(awk -F'|' '{ gsub(/ /, "", $1); if ($1 == "blocks") { gsub(/ /, "", $2); print $2 } }' \
    "$dataset_file" 2>/dev/null || true)"
ordered_rows="$(awk -F'|' '{ gsub(/ /, "", $1); if ($1 == "ordered_rows") { gsub(/ /, "", $2); print $2 } }' \
    "$dataset_file" 2>/dev/null || true)"
partitioned_rows="$(awk -F'|' '{ gsub(/ /, "", $1); if ($1 == "partitioned_rows") { gsub(/ /, "", $2); print $2 } }' \
    "$dataset_file" 2>/dev/null || true)"
partitioned_blocks="$(awk -F'|' '{ gsub(/ /, "", $1); if ($1 == "partitioned_blocks") { gsub(/ /, "", $2); print $2 } }' \
    "$dataset_file" 2>/dev/null || true)"

# --- Per-strategy charts ----------------------------------------------------
# One group per (strategy, chunk size, key columns, partition-wise). Older CSVs
# have no partition_wise column, so an absent value counts as "no".
groups="$(awk -F, 'NR > 1 && $1 != "" { pw = ($11 == "yes") ? "yes" : "no"; print $1 "/" $3 "/" $10 "/" pw }' "$csv" | sort -u)"
for g in $groups; do
    s="${g%%/*}"
    rest="${g#*/}"
    cs="${rest%%/*}"
    rest="${rest#*/}"
    pk="${rest%%/*}"
    pw="${rest#*/}"
    suffix=""
    if [ "$pw" = yes ]; then
        suffix="-partitioned"
    fi
    awk -v strategy="$s" -v chunk_size="$cs" -v pk_columns="$pk" -v partition_wise="$pw" \
        -f "$here/chart.awk" "$csv" > "$results_dir/report-${s}${suffix}-${cs}-${pk}.svg"
done

# --- Report -----------------------------------------------------------------
{
    echo "# Chunked migration thread-scaling benchmark"
    echo
    echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "This page is generated by \`benchmark/report.sh\` from"
    echo "\`$BENCH_RESULTS_DIR/$BENCH_CSV\`. Re-run it after more runs to refresh"
    echo "the means; the CSV accumulates across invocations of \`run.sh\`. The"
    echo "results are grouped by the chunking strategy, key, chunk size and"
    echo "partition-wise flag recorded in each row."
    echo
    echo "## Configuration"
    echo
    echo "| parameter | value |"
    echo "| --- | --- |"
    echo "| image | \`$BENCH_IMAGE\` |"
    echo "| source table | \`$BENCH_SCHEMA.$BENCH_SOURCE_TABLE\` |"
    echo "| target table | \`$BENCH_SCHEMA.$BENCH_TARGET_TABLE\` |"
    echo "| ordered source table | \`$BENCH_SCHEMA.$BENCH_ORDERED_SOURCE_TABLE\` |"
    echo "| ordered target table | \`$BENCH_SCHEMA.$BENCH_ORDERED_TARGET_TABLE\` |"
    echo "| ordered key index | \`$BENCH_ORDERED_INDEX\` |"
    echo "| partitioned source table | \`$BENCH_SCHEMA.$BENCH_PARTITIONED_SOURCE_TABLE\` |"
    echo "| partitioned target table | \`$BENCH_SCHEMA.$BENCH_PARTITIONED_TARGET_TABLE\` |"
    echo "| partitioned leaves | $BENCH_PARTITIONED_LEAVES |"
    echo "| rows | $BENCH_ROWS |"
    echo "| chunk_by (current config) | \`$BENCH_CHUNK_BY\` |"
    echo "| partition_wise (current config) | \`$BENCH_PARTITION_WISE\` |"
    echo "| chunk size | $BENCH_CHUNK_SIZE (rows for primary_key and ordered_key, blocks for blocks) |"
    echo "| threads | $BENCH_THREADS_FROM .. $BENCH_THREADS_TO |"
    echo "| measured runs per thread | $BENCH_RUNS |"
    echo "| warm-up runs per thread | $BENCH_WARMUP_RUNS |"
    echo "| truncate target each run | $BENCH_TRUNCATE_TARGET |"
    echo "| max_worker_processes | $BENCH_MAX_WORKER_PROCESSES |"
    echo "| shared_buffers | $BENCH_SHARED_BUFFERS |"
    echo
    echo "Dataset (live):"
    echo
    echo '```'
    cat "$dataset_file"
    echo '```'
    echo
    echo "Workload SQL (run once per chunk, \`<driving_table>\` aliased \`t\`):"
    echo
    echo '```sql'
    echo "$BENCH_SQL"
    echo '```'
    echo
    echo "Partition-wise workload SQL (the same copy into its own target):"
    echo
    echo '```sql'
    echo "$BENCH_PARTITIONED_SQL"
    echo '```'
    echo
    echo "## Hardware and software"
    echo
    echo '```'
    cat "$hardware_file"
    echo '```'
    echo
    echo "## PostgreSQL settings"
    echo
    echo '```'
    cat "$settings_file"
    echo '```'
    echo
    echo "## Results (mean over the measured runs)"
    echo
    if [ -z "$groups" ]; then
        echo "_no results yet_"
    fi
    for g in $groups; do
        s="${g%%/*}"
        rest="${g#*/}"
        cs="${rest%%/*}"
        rest="${rest#*/}"
        pk="${rest%%/*}"
        pw="${rest#*/}"
        unit="$(awk -F, -v s="$s" -v c="$cs" -v key="$pk" -v pw="$pw" '
            NR > 1 && $1 == s && $3 == c && $10 == key {
                row_pw = ($11 == "yes") ? "yes" : "no"
                if (row_pw == pw) { print $2; exit }
            }' "$csv")"
        suffix=""
        pw_label=""
        if [ "$pw" = yes ]; then
            suffix="-partitioned"
            pw_label=" (partition-wise)"
        fi
        echo "### strategy \`$s\`, key $pk, chunk_size $cs $unit$pw_label"
        echo
        echo "| threads | runs | chunks | rows/chunk | blocks/chunk | mean range calc (s) | mean chunk run (s) | mean total (s) | speedup vs first |"
        echo "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
        awk -F, -v s="$s" -v c="$cs" -v key="$pk" -v pw="$pw" \
            -v src_rows="$src_rows" -v src_blocks="$src_blocks" -v ordered_rows="$ordered_rows" \
            -v partitioned_rows="$partitioned_rows" -v partitioned_blocks="$partitioned_blocks" '
            NR == 1 { next }
            $1 != s || $3 != c || $10 != key { next }
            {
                row_pw = ($11 == "yes") ? "yes" : "no"
                if (row_pw != pw) { next }
                t = $4
                n[t]++
                ch[t] += $6
                r[t] += $7
                cs2[t] += $8
                tt[t] += $9
                if (!(t in seen)) { seen[t] = 1; order[++cnt] = t }
            }
            END {
                if (cnt == 0) { print "| _no results_ | | | | | | | | |"; exit }
                for (i = 1; i <= cnt; i++)
                    for (j = i + 1; j <= cnt; j++)
                        if (order[i] + 0 > order[j] + 0) {
                            tmp = order[i]; order[i] = order[j]; order[j] = tmp
                        }
                base = tt[order[1]] / n[order[1]]
                for (i = 1; i <= cnt; i++) {
                    t = order[i]
                    mc = ch[t] / n[t]
                    rows_for_chunk = (pw == "yes") ? partitioned_rows \
                        : ((s == "ordered_key") ? ordered_rows : src_rows)
                    blocks_for_chunk = (pw == "yes") ? partitioned_blocks : src_blocks
                    rpc = (rows_for_chunk + 0 > 0 && mc > 0) ? sprintf("%.0f", rows_for_chunk / mc) : "-"
                    bpc = (s == "blocks" && blocks_for_chunk + 0 > 0 && mc > 0) ? sprintf("%.0f", blocks_for_chunk / mc) : "-"
                    ms = tt[t] / n[t]
                    sp = (ms > 0) ? base / ms : 0
                    printf "| %s | %d | %.0f | %s | %s | %.3f | %.3f | %.3f | %.2fx |\n",
                        t, n[t], mc, rpc, bpc, r[t] / n[t], cs2[t] / n[t], ms, sp
                }
            }' "$csv"
        echo
        echo "![run_migration_chunks ($s$pw_label, key $pk, chunk_size $cs): mean total time vs threads](report-${s}${suffix}-${cs}-${pk}.svg)"
        echo
    done
    echo "## How the times are measured"
    echo
    echo "- **range calc** = \`boundaries_calculated_at - started_at\`: the"
    echo "  chunk-boundary calculation (a primary-key index scan for \`primary_key\`,"
    echo "  the ordered-index scan for \`ordered_key\`, an O(1) block count for"
    echo "  \`blocks\`). A partition-wise calculation runs inside the leaves and"
    echo "  commits one transaction per leaf, so its range-calc time includes that"
    echo "  per-leaf work."
    echo "- **chunk run** = \`completed_at - boundaries_calculated_at\`: the workload SQL"
    echo "  run over every chunk, up to the thread count at a time."
    echo "- **total** = \`completed_at - started_at\`."
    echo "- **rows/chunk** and **blocks/chunk** are the live source counts (see the"
    echo "  Dataset section) divided by the mean chunk count. A block holds a variable"
    echo "  number of rows, so for \`blocks\` these are averages and the final chunk is"
    echo "  usually smaller than the rest."
    echo "- Warm-up runs are not recorded. The mean is over the measured runs only, and"
    echo "  the CSV accumulates across invocations, so the mean covers every run recorded"
    echo "  for that strategy and thread value."
    echo "- Speedup is relative to the lowest thread value for that strategy."
    echo
    echo "## Caveats"
    echo
    echo "- The source data is synthetic and deterministic; a refresh reproduces it"
    echo "  exactly. Only the columns the workload reads are present."
    echo "- The default workload INSERTs into a throwaway target, so the source is never"
    echo "  modified and each run starts from the same state. This makes the source"
    echo "  quiescent, which is required for the \`blocks\` strategy."
    echo "- \`primary_key\` and \`blocks\` are not directly comparable at the same numeric"
    echo "  chunk size: \`primary_key\` chunks a fixed number of rows, \`blocks\` a fixed"
    echo "  number of heap blocks, and a block holds a variable number of rows."
    echo "- The partition-wise variant uses a range-partitioned copy of the same"
    echo "  synthetic data, so compare each partition-wise row with the non-partitioned"
    echo "  row of the same strategy, key and chunk size. Partition-aware plans prune to"
    echo "  the leaf, but a run pays one boundary calculation and transaction per leaf."
    echo "- \`ordered_key\` chunks a fixed number of rows like \`primary_key\`, so compare"
    echo "  those two by \`rows/chunk\`; it uses its own UUID-keyed source and target, so"
    echo "  its absolute times also include that table's shape."
    echo "- Autovacuum, checkpoints and other background activity can add noise; run more"
    echo "  measured runs (increase \`BENCH_RUNS\`) to tighten the mean."
    echo "- Each thread is one \`pg_background\` worker; the count is capped by"
    echo "  \`max_worker_processes\`, which is why the benchmark uses its own container."
} > "$report"

echo "wrote $report" >&2
