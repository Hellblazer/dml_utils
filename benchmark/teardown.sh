#!/bin/sh
# Remove the benchmark container, its data volume and its network, reclaiming the
# disk space. Results on the host (benchmark/results/) are not touched. Recreate
# the database later with benchmark/setup.sh.
#
# Usage: benchmark/teardown.sh

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
. "$here/config.env"
# Optional personal overrides, kept out of git (copy config.local.env.example).
# BENCH_IGNORE_LOCAL_CONFIG=1 skips them, so benchmark/smoke.sh is not affected
# by a developer's personal sweep.
if [ -f "$here/config.local.env" ] && [ "${BENCH_IGNORE_LOCAL_CONFIG:-0}" != 1 ]; then
    . "$here/config.local.env"
fi

usage() {
    echo "usage: $0" >&2
}

for arg in "$@"; do
    case "$arg" in
        -h|--help) usage; exit 0 ;;
        *)
            echo "unknown argument: $arg" >&2
            usage
            exit 2
            ;;
    esac
done

# Only ever remove resources in the benchmark namespace. The names come from
# config.env/config.local.env, where a typo or copied override must not destroy
# an unrelated container or data volume. ${VAR:-} keeps set -u from aborting with
# a cryptic "unbound variable" before the guard can explain the problem.
for _name in "${BENCH_CONTAINER:-}" "${BENCH_VOLUME:-}" "${BENCH_NETWORK:-}"; do
    case "$_name" in
        dml_utils_benchmark*) ;;
        *)
            echo "refusing to remove '$_name': not a benchmark resource" >&2
            exit 1
            ;;
    esac
done

command -v docker >/dev/null 2>&1 || {
    echo "docker is required but was not found on PATH" >&2
    exit 1
}
# The binary existing does not mean the daemon is reachable: without this, every
# removal fails silently and the script still claims success.
docker info >/dev/null 2>&1 || {
    echo "the docker daemon is not reachable" >&2
    exit 1
}

status=0

if docker container inspect "$BENCH_CONTAINER" >/dev/null 2>&1; then
    if docker rm -f "$BENCH_CONTAINER" >/dev/null; then
        echo "removed container $BENCH_CONTAINER" >&2
    else
        echo "failed to remove container $BENCH_CONTAINER" >&2
        status=1
    fi
else
    echo "container $BENCH_CONTAINER was not present" >&2
fi

if docker volume inspect "$BENCH_VOLUME" >/dev/null 2>&1; then
    if docker volume rm "$BENCH_VOLUME" >/dev/null; then
        echo "removed volume $BENCH_VOLUME" >&2
    else
        echo "failed to remove volume $BENCH_VOLUME" >&2
        status=1
    fi
else
    echo "volume $BENCH_VOLUME was not present" >&2
fi

if docker network inspect "$BENCH_NETWORK" >/dev/null 2>&1; then
    if docker network rm "$BENCH_NETWORK" >/dev/null; then
        echo "removed network $BENCH_NETWORK" >&2
    else
        echo "failed to remove network $BENCH_NETWORK" >&2
        status=1
    fi
else
    echo "network $BENCH_NETWORK was not present" >&2
fi

exit "$status"
