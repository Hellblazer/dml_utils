#!/bin/sh
# Open a psql session against the benchmark database.
#
# Connects through `docker exec` to the dedicated benchmark container
# (BENCH_CONTAINER / BENCH_DB / BENCH_DB_USER from config.env), so no host psql
# or connection string is needed. Any arguments are passed to psql:
#
#   benchmark/psql.sh                       # interactive session
#   benchmark/psql.sh -c "SELECT 1"         # run one command
#   benchmark/psql.sh -f some.sql           # run a file
#
# Usage: benchmark/psql.sh [psql arguments...]

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
. "$here/config.env"
# Optional personal overrides, kept out of git (copy config.local.env.example).
# BENCH_IGNORE_LOCAL_CONFIG=1 skips them, so benchmark/smoke.sh is not affected
# by a developer's personal sweep.
if [ -f "$here/config.local.env" ] && [ "${BENCH_IGNORE_LOCAL_CONFIG:-0}" != 1 ]; then
    . "$here/config.local.env"
fi

command -v docker >/dev/null 2>&1 || {
    echo "docker is required but was not found on PATH" >&2
    exit 1
}

# One inspect: it exits non-zero when the container is missing, and prints
# nothing when it is stopped, so the combined check covers both cases without a
# second docker round trip or a gap between the two.
if [ "$(docker inspect -f '{{.State.Running}}' "$BENCH_CONTAINER" 2>/dev/null)" != "true" ]; then
    echo "benchmark container $BENCH_CONTAINER is not running; run benchmark/setup.sh first" >&2
    exit 1
fi

# Allocate a TTY only when both stdin and stdout are terminals. `docker exec -it`
# allocates a PTY for stdout too, so redirecting output would otherwise get CRLF
# line endings and psql's interactive formatting.
if [ -t 0 ] && [ -t 1 ]; then
    exec docker exec -it "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
else
    exec docker exec -i "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
fi
