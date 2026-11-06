#!/bin/sh
# Create (or refresh) the dedicated benchmark database.
#
# Starts a PostgreSQL container from the custom image with max_worker_processes
# raised enough for the thread sweep, applies the dml_utils changelog to it, then
# creates the deterministic synthetic source table and the throwaway target
# table. The bigint-keyed source is a plain heap, so it supports both the
# primary_key and blocks chunking strategies; a second UUIDv4-keyed source with a
# (created_at, id) unique index supports ordered_key, and a range-partitioned
# copy of the bigint-keyed source supports the partition-wise variant. The
# default workload (an INSERT into the target) leaves the source read-only, which
# blocks requires.
# Safe to re-run; pass --refresh to drop the container and its data volume first
# (required after changing the server settings or the image).
#
# Usage: benchmark/setup.sh [--refresh]

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/.." && pwd)"
. "$here/config.env"
# Optional personal overrides, kept out of git (copy config.local.env.example).
# BENCH_IGNORE_LOCAL_CONFIG=1 skips them, so benchmark/smoke.sh is not affected
# by a developer's personal sweep.
if [ -f "$here/config.local.env" ] && [ "${BENCH_IGNORE_LOCAL_CONFIG:-0}" != 1 ]; then
    . "$here/config.local.env"
fi

usage() {
    echo "usage: $0 [--refresh]" >&2
}

refresh=0
for arg in "$@"; do
    case "$arg" in
        --refresh) refresh=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

# Only ever remove resources in the benchmark namespace: the names come from
# config.env/config.local.env, and a typo must not destroy an unrelated volume.
for _name in "$BENCH_CONTAINER" "$BENCH_VOLUME" "$BENCH_NETWORK"; do
    case "$_name" in
        dml_utils_benchmark*) ;;
        *)
            echo "refusing to manage '$_name': not a benchmark resource" >&2
            exit 1
            ;;
    esac
done

command -v docker >/dev/null 2>&1 || {
    echo "docker is required but was not found on PATH" >&2
    exit 1
}

# The custom image carries the roles, plpgsql_check and pg_background; build it
# if it is missing.
if ! docker image inspect "$BENCH_IMAGE" >/dev/null 2>&1; then
    echo "image $BENCH_IMAGE not found; building it ..." >&2
    "$repo_root/scripts/build-postgres-image.sh" "postgres:${BENCH_IMAGE##*:}"
fi

docker network inspect "$BENCH_NETWORK" >/dev/null 2>&1 \
    || docker network create "$BENCH_NETWORK" >/dev/null

if [ "$refresh" = 1 ]; then
    echo "removing container $BENCH_CONTAINER and volume $BENCH_VOLUME" >&2
    docker rm -f "$BENCH_CONTAINER" >/dev/null 2>&1 || true
    docker volume rm "$BENCH_VOLUME" >/dev/null 2>&1 || true
fi

if ! docker inspect "$BENCH_CONTAINER" >/dev/null 2>&1; then
    echo "starting $BENCH_CONTAINER ($BENCH_IMAGE) ..." >&2
    # The trailing `postgres -c ...` overrides the image CMD, so the server runs
    # with the settings a reproducible sweep needs.
    docker run -d --name "$BENCH_CONTAINER" --network "$BENCH_NETWORK" \
        -e POSTGRES_DB="$BENCH_DB" \
        -e POSTGRES_USER="$BENCH_DB_USER" \
        -e POSTGRES_PASSWORD="$BENCH_DB_USER" \
        -p "127.0.0.1:${BENCH_HOST_PORT}:5432" \
        -v "${BENCH_VOLUME}:/var/lib/postgresql/data" \
        "$BENCH_IMAGE" \
        postgres -c max_worker_processes="$BENCH_MAX_WORKER_PROCESSES" \
        -c shared_buffers="$BENCH_SHARED_BUFFERS" \
        -c max_connections="$BENCH_MAX_CONNECTIONS" >/dev/null
else
    docker start "$BENCH_CONTAINER" >/dev/null 2>&1 || true
fi

# Talk to the container through docker exec, so no host client is needed.
psql_run() {
    docker exec "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
}
psql_stdin() {
    docker exec -i "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
}

# The image's entrypoint starts a temporary server for its init scripts, stops
# it, then starts the real server. A psql check can succeed against the
# temporary server and then hit "the database system is shutting down" (seen on
# CI), so wait for the entrypoint to report that init is complete before polling
# psql. A reused container has that line in its logs already, so the wait passes
# immediately.
echo "waiting for postgres ..." >&2
init_deadline=$(( $(date +%s) + 120 ))
while ! docker logs "$BENCH_CONTAINER" 2>&1 | grep -q "PostgreSQL init process complete"; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$BENCH_CONTAINER" 2>/dev/null)" != "true" ]; then
        echo "postgres container exited during init" >&2
        docker logs "$BENCH_CONTAINER" >&2 || true
        exit 1
    fi
    if [ "$(date +%s)" -ge "$init_deadline" ]; then
        echo "postgres did not finish its init scripts" >&2
        docker logs "$BENCH_CONTAINER" >&2 || true
        exit 1
    fi
    sleep 1
done
ready=0
for _ in $(seq 1 90); do
    if psql_run -At -c 'SELECT 1' >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 1
done
[ "$ready" = 1 ] || { echo "postgres did not become ready" >&2; exit 1; }

# Install ddl_utils first: dml_utils assumes it is present in the same database,
# and dml_utils_data migrations call its DDL helpers. The versioned ddl_utils
# Maven artifact is unpacked into the build output at initialize.
module_dir="$repo_root/$(basename "$repo_root")"
ddl_utils_changelog_dir="$module_dir/target/classes/db/changelog-install/ddl-utils/db/changelog"
if [ ! -f "$ddl_utils_changelog_dir/db.changelog-master.xml" ]; then
    echo "unpacking the ddl_utils changelog ..." >&2
    (cd "$repo_root" && ./mvnw -q -pl "$(basename "$repo_root")" -am initialize)
fi
[ -f "$ddl_utils_changelog_dir/db.changelog-master.xml" ] || {
    echo "ddl_utils changelog is missing; run ./mvnw -pl $(basename "$repo_root") -am initialize" >&2
    exit 1
}
if ! psql_run -At -c "SELECT 1 FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'ddl_utils' AND p.proname = 'add_column'" \
        | grep -q 1; then
    echo "installing ddl_utils ..." >&2
    psql_run -q -v ON_ERROR_STOP=1 -c \
        "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ddl_utils_caller') THEN CREATE ROLE ddl_utils_caller; END IF; END \$\$;"
    psql_run -q -v ON_ERROR_STOP=1 -c "GRANT ddl_utils_caller TO $BENCH_OWNER_USER"
    docker run --rm --network "$BENCH_NETWORK" \
        -v "$ddl_utils_changelog_dir:/liquibase/ddl-utils-changelog" \
        "$BENCH_LIQUIBASE_IMAGE" sh -c \
        "lpm add postgresql --global && liquibase \
            --url=jdbc:postgresql://${BENCH_CONTAINER}:5432/${BENCH_DB} \
            --username=${BENCH_OWNER_USER} \
            --password=${BENCH_OWNER_PASSWORD} \
            --changelog-file=ddl-utils-changelog/db.changelog-master.xml \
            --liquibase-schema-name=liquibase \
            --database-changelog-table-name=ddl_utils_databasechangelog \
            --database-changelog-lock-table-name=ddl_utils_databasechangeloglock \
            update"
fi

# Apply the dml_utils changelog once (the routines are what we benchmark). The
# marker is the routine under test, so a re-run skips the driver download.
if ! psql_run -At -c "SELECT 1 FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'dml_utils' AND p.proname = 'run_migration_chunks'" \
        | grep -q 1; then
    echo "applying the changelog ..." >&2
    changelog_dir="$repo_root/$(basename "$repo_root")/src/main/resources/db/changelog"
    docker run --rm --network "$BENCH_NETWORK" \
        -v "$changelog_dir:/liquibase/changelog" \
        "$BENCH_LIQUIBASE_IMAGE" sh -c \
        "lpm add postgresql --global && liquibase \
            --url=jdbc:postgresql://${BENCH_CONTAINER}:5432/${BENCH_DB} \
            --username=${BENCH_OWNER_USER} \
            --password=${BENCH_OWNER_PASSWORD} \
            --changelog-file=changelog/db.changelog-master.xml \
            --liquibase-schema-name=liquibase \
            --database-changelog-table-name=${BENCH_DB}_databasechangelog \
            --database-changelog-lock-table-name=${BENCH_DB}_databasechangeloglock \
            update"
fi

# Build the primary-key SQL fragments from BENCH_PK_COLUMNS, the number of key
# columns (1 to 3; all bigint, named k1..kN). The row number g maps to a unique
# key tuple by base-mod digits: with p columns and modulus m = ceil(rows^(1/p)),
# the i-th column is ((g - 1) / m^(p-i)) % m + 1, so m^p >= rows makes the tuples
# unique.
case "$BENCH_PK_COLUMNS" in
    1|2|3) ;;
    *)
        echo "BENCH_PK_COLUMNS must be 1, 2 or 3: $BENCH_PK_COLUMNS" >&2
        exit 2
        ;;
esac
case "$BENCH_ROWS" in
    ''|*[!0-9]*)
        echo "BENCH_ROWS must be a positive integer: $BENCH_ROWS" >&2
        exit 2
        ;;
esac
[ "$BENCH_ROWS" -gt 0 ] || {
    echo "BENCH_ROWS must be positive: $BENCH_ROWS" >&2
    exit 2
}
case "$BENCH_PARTITIONED_LEAVES" in
    ''|*[!0-9]*)
        echo "BENCH_PARTITIONED_LEAVES must be a positive integer: $BENCH_PARTITIONED_LEAVES" >&2
        exit 2
        ;;
esac
[ "$BENCH_PARTITIONED_LEAVES" -gt 0 ] || {
    echo "BENCH_PARTITIONED_LEAVES must be positive: $BENCH_PARTITIONED_LEAVES" >&2
    exit 2
}
case "$BENCH_THREADS_TO" in
    ''|*[!0-9]*)
        echo "BENCH_THREADS_TO must be a non-negative integer: $BENCH_THREADS_TO" >&2
        exit 2
        ;;
esac
case "$BENCH_MAX_WORKER_PROCESSES" in
    ''|*[!0-9]*)
        echo "BENCH_MAX_WORKER_PROCESSES must be a non-negative integer: $BENCH_MAX_WORKER_PROCESSES" >&2
        exit 2
        ;;
esac
[ "$BENCH_MAX_WORKER_PROCESSES" -ge "$BENCH_THREADS_TO" ] || {
    echo "BENCH_MAX_WORKER_PROCESSES ($BENCH_MAX_WORKER_PROCESSES) must be at least BENCH_THREADS_TO ($BENCH_THREADS_TO)" >&2
    exit 2
}
case "$BENCH_SCHEMA" in
    ''|public|pg_catalog|information_schema)
        echo "refusing to create benchmark fixtures in schema '$BENCH_SCHEMA'" >&2
        exit 2
        ;;
esac
# A target that collides with any source would make create-target.sql drop the
# freshly populated source and turn the benchmark into a self-insert.
_sources=" $BENCH_SOURCE_TABLE $BENCH_ORDERED_SOURCE_TABLE $BENCH_PARTITIONED_SOURCE_TABLE "
for _target in "$BENCH_TARGET_TABLE" "$BENCH_ORDERED_TARGET_TABLE" \
    "$BENCH_PARTITIONED_TARGET_TABLE"; do
    case "$_sources" in
        *" $_target "*)
            echo "benchmark target '$_target' collides with a source table" >&2
            exit 2
            ;;
    esac
done
unset _sources _target
pk_count="$BENCH_PK_COLUMNS"
pk_mod="$(awk -v n="$BENCH_ROWS" -v p="$pk_count" 'BEGIN { m = 1; while (m ^ p < n) m++; print m }')"
if [ "$BENCH_PARTITIONED_LEAVES" -gt "$pk_mod" ]; then
    echo "BENCH_PARTITIONED_LEAVES ($BENCH_PARTITIONED_LEAVES) must not exceed the distinct k1 values ($pk_mod) produced for $BENCH_ROWS rows" >&2
    exit 2
fi
pk_def=""
pk_key=""
pk_exprs=""
pk_i=0
while [ "$pk_i" -lt "$pk_count" ]; do
    pk_i=$((pk_i + 1))
    pk_name="k$pk_i"
    pk_def="${pk_def:+$pk_def, }$pk_name bigint"
    pk_key="${pk_key:+$pk_key, }$pk_name"
    pk_div="$(awk -v m="$pk_mod" -v e="$((pk_count - pk_i))" 'BEGIN { print m ^ e }')"
    pk_exprs="${pk_exprs:+$pk_exprs, }((g - 1) / $pk_div) % $pk_mod + 1"
done

# Deterministic source data, then the throwaway target.
echo "creating benchmark tables ($BENCH_ROWS source rows, $BENCH_PK_COLUMNS-column key) ..." >&2
psql_stdin -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v source_table="$BENCH_SOURCE_TABLE" \
    -v rows="$BENCH_ROWS" \
    -v pk_def="$pk_def" \
    -v pk_key="$pk_key" \
    -v pk_exprs="$pk_exprs" \
    -f - < "$here/sql/create-source.sql"
psql_stdin -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v target_table="$BENCH_TARGET_TABLE" \
    -v pk_def="$pk_def" \
    -f - < "$here/sql/create-target.sql"

# The ordered-key variant: a UUIDv4-keyed source with a (created_at, id) unique
# index, and its throwaway target.
echo "creating ordered-key benchmark tables ($BENCH_ROWS source rows) ..." >&2
psql_stdin -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v source_table="$BENCH_ORDERED_SOURCE_TABLE" \
    -v rows="$BENCH_ROWS" \
    -v index="$BENCH_ORDERED_INDEX" \
    -f - < "$here/sql/create-ordered-source.sql"
psql_stdin -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v target_table="$BENCH_ORDERED_TARGET_TABLE" \
    -f - < "$here/sql/create-ordered-target.sql"

# The partition-wise variant: a range-partitioned copy of the bigint-keyed
# source with BENCH_PARTITIONED_LEAVES leaves, and its throwaway target.
echo "creating partition-wise benchmark tables ($BENCH_ROWS source rows, $BENCH_PARTITIONED_LEAVES leaves) ..." >&2
psql_stdin -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v source_table="$BENCH_PARTITIONED_SOURCE_TABLE" \
    -v rows="$BENCH_ROWS" \
    -v pk_def="$pk_def" \
    -v pk_key="$pk_key" \
    -v pk_exprs="$pk_exprs" \
    -v leaves="$BENCH_PARTITIONED_LEAVES" \
    -v k1_max="$pk_mod" \
    -f - < "$here/sql/create-partitioned-source.sql"
psql_stdin -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v target_table="$BENCH_PARTITIONED_TARGET_TABLE" \
    -v pk_def="$pk_def" \
    -f - < "$here/sql/create-target.sql"

# dml_utils_lib.populate_migration_boundaries is SECURITY DEFINER: it writes the
# run metadata as the owner, and its boundary query scans the driving table in
# that definer context. The fixture schema is created outside the
# Liquibase-managed schemas by the benchmark's superuser, so the owner needs
# explicit USAGE and SELECT or the boundary scan fails with "permission denied
# for schema benchmark". Only the source is read there; the per-chunk workload
# runs SECURITY INVOKER as the benchmark user.
psql_stdin -q -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v source_table="$BENCH_SOURCE_TABLE" \
    -v ordered_source="$BENCH_ORDERED_SOURCE_TABLE" \
    -v partitioned_source="$BENCH_PARTITIONED_SOURCE_TABLE" \
    -v owner="$BENCH_OWNER_USER" \
    -f - <<'SQL'
GRANT USAGE ON SCHEMA :"schema" TO :"owner";
GRANT SELECT ON :"schema".:"source_table" TO :"owner";
GRANT SELECT ON :"schema".:"ordered_source" TO :"owner";
GRANT SELECT ON :"schema".:"partitioned_source" TO :"owner";
SQL

# A partition-wise boundary scan reads each leaf, and the definer needs SELECT on
# every one of them.
psql_stdin -q -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v partitioned_source="$BENCH_PARTITIONED_SOURCE_TABLE" \
    -v owner="$BENCH_OWNER_USER" \
    -f - <<'SQL'
SELECT pg_catalog.format('GRANT SELECT ON %I.%I TO %I',
                         n.nspname, c.relname, :'owner')
FROM pg_catalog.pg_partition_tree(
             (:'schema' || '.' || :'partitioned_source')::regclass) AS leaf
         JOIN pg_catalog.pg_class c ON c.oid = leaf.relid
         JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE leaf.isleaf
\gexec
SQL

echo "benchmark database ready: $BENCH_CONTAINER / $BENCH_DB" >&2
