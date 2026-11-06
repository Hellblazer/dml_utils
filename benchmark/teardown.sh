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

# Only ever remove resources in the benchmark namespace. The names come from
# config.env/config.local.env, where a typo or copied override must not destroy
# an unrelated container or data volume.
for _name in "$BENCH_CONTAINER" "$BENCH_VOLUME" "$BENCH_NETWORK"; do
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

# Best effort: each may already be absent.
docker rm -f "$BENCH_CONTAINER" >/dev/null 2>&1 || true
docker volume rm "$BENCH_VOLUME" >/dev/null 2>&1 || true
docker network rm "$BENCH_NETWORK" >/dev/null 2>&1 || true

echo "removed container $BENCH_CONTAINER, volume $BENCH_VOLUME and network $BENCH_NETWORK" >&2
