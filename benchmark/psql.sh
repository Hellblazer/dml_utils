#!/bin/sh
# Open a psql session against the benchmark database.
#
# Connects through `docker exec` to the dedicated benchmark container
# (BENCH_CONTAINER / BENCH_DB / BENCH_DB_USER from config.env), so no host psql
# or connection string is needed. Any arguments are passed to psql:
#
#   benchmark/psql.sh                       # interactive session
#   benchmark/psql.sh -c "SELECT 1"         # run one command
#   benchmark/psql.sh -f some.sql           # run a host file
#   benchmark/psql.sh < some.sql            # run SQL from stdin
#
# The container cannot see host paths, so a -f/--file path is read here and
# streamed into the container's psql as `-f -`. Only one host file per call;
# `-f -` itself is passed through so psql reads the caller's stdin. All other
# arguments are passed through unchanged, in order.
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
# The binary existing does not mean the daemon is reachable: without this, an
# unreachable daemon looks like a stopped container.
docker info >/dev/null 2>&1 || {
    echo "the docker daemon is not reachable" >&2
    exit 1
}

# Rewrite a host `-f FILE` into `-f -`, fed from that file below. The arguments
# are rebuilt in place; the loop processes exactly the original count, while the
# rewritten copies are appended.
host_file=""
arg_count=$#
arg_index=0
while [ "$arg_index" -lt "$arg_count" ]; do
    arg="$1"
    shift
    case "$arg" in
        -f|--file)
            [ "$#" -gt 0 ] || {
                echo "$arg needs a file" >&2
                exit 2
            }
            case "$1" in
                -)
                    set -- "$@" "$arg" -
                    ;;
                '')
                    echo "$arg needs a file" >&2
                    exit 2
                    ;;
                *)
                    [ -z "$host_file" ] || {
                        echo "only one -f/--file host file is supported" >&2
                        exit 2
                    }
                    host_file="$1"
                    set -- "$@" "$arg" -
                    ;;
            esac
            shift
            arg_index=$((arg_index + 2))
            ;;
        --file=-|-f-)
            # psql reads stdin itself.
            set -- "$@" "$arg"
            arg_index=$((arg_index + 1))
            ;;
        --file=*)
            [ -n "${arg#--file=}" ] || {
                echo "$arg needs a file" >&2
                exit 2
            }
            [ -z "$host_file" ] || {
                echo "only one -f/--file host file is supported" >&2
                exit 2
            }
            host_file="${arg#--file=}"
            set -- "$@" -f -
            arg_index=$((arg_index + 1))
            ;;
        -f?*)
            [ -z "$host_file" ] || {
                echo "only one -f/--file host file is supported" >&2
                exit 2
            }
            host_file="${arg#-f}"
            set -- "$@" -f -
            arg_index=$((arg_index + 1))
            ;;
        *)
            set -- "$@" "$arg"
            arg_index=$((arg_index + 1))
            ;;
    esac
done

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
if [ -n "$host_file" ]; then
    [ -f "$host_file" ] || {
        echo "no such SQL file: $host_file" >&2
        exit 2
    }
    # No TTY: the SQL comes from the file, and psql must not think stdout is a
    # terminal either.
    exec docker exec -i "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@" \
        < "$host_file"
fi

if [ -t 0 ] && [ -t 1 ]; then
    exec docker exec -it "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
else
    exec docker exec -i "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
fi
