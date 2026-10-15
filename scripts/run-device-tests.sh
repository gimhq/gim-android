#!/usr/bin/env bash
# Runs the instrumented (connected) tests on an API 35 x86_64 emulator against the vendored gim-server,
# mirroring the CI `device-tests` job with sibling docker containers on the host (which provides KVM).
#
# Usage: scripts/run-device-tests.sh [GRADLE_ARGS...]
#   GRADLE_ARGS  passed through to `./gradlew connectedDebugAndroidTest`, e.g.
#                scripts/run-device-tests.sh :core:bridge:connectedDebugAndroidTest
#
# Environment:
#   REBUILD_IMAGE=1   rebuild the emulator image even if it exists
#   GRADLE_CACHE      Gradle user home on the host, default /srv/.tmp/cache/gradle
#   CI_LIKE=1         reproduce CI's emulator: presets DEVICE_PROFILE=default (320x640 @160dpi) CORES=2
#                     RAM_SIZE=3072 DISK_SIZE=4096M SNAPSHOT_BOOT=1 TIMEOUT_SCALE=2; each can be overridden
#   DEVICE_PROFILE, GPU, CORES, RAM_SIZE, DISK_SIZE, SNAPSHOT_BOOT
#                     emulator settings, see scripts/lib/device.sh (defaults: pixel_6, swiftshader_indirect)
#   TIMEOUT_SCALE     instrumentation argument timeoutScale (multiplies the test's per-step waits)
#
# Flow:
#   1. Builds (once) ai-agent/gim-android-emulator: ghcr.io/cirruslabs/android-sdk:35 plus the emulator
#      and system-images;android-35;google_apis;x86_64.
#   2. Starts e2e/gim-server in an oven/bun:1.4 container and waits for GET /healthz.
#   3. Boots the emulator and runs Gradle in a container with --device /dev/kvm that shares the server
#      container's network namespace, so http://10.0.2.2:8080 (the emulator's host loopback) reaches
#      the server exactly as in CI.
#   4. On failure, writes the full device logcat to .tmp/device-tests/logcat-<pid>.txt.
# Only the worktree (host path == container path) and the Gradle cache are mounted. Every container
# carries --label ai-agent=true and is removed on exit. The worktree must live on a host-visible path
# (e.g. /srv/...), not a container-local alias such as /work.
set -euo pipefail

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
    sed -n '2,30p' "$0"
    exit 0
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib/device.sh
source "$repo_root/scripts/lib/device.sh"
gradle_cache=${GRADLE_CACHE:-/srv/.tmp/cache/gradle}
server_url=$GIM_EMULATOR_SERVER_URL
run_id=$$
server=ai-agent-gim-server-$run_id
runner=ai-agent-gim-android-device-$run_id

gim_check_host_paths "$repo_root"

if [[ ${CI_LIKE:-0} == 1 ]]; then
    : "${DEVICE_PROFILE:=default}" "${CORES:=2}" "${RAM_SIZE:=3072}" "${DISK_SIZE:=4096M}"
    : "${SNAPSHOT_BOOT:=1}" "${TIMEOUT_SCALE:=2}"
fi
log_dir=$repo_root/.tmp/device-tests
mkdir -p "$log_dir"

cleanup() {
    docker rm -f "$runner" "$server" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

gim_ensure_emulator_image
gim_start_server "$server" "$repo_root"

mkdir -p "$gradle_cache"
status=0
docker run --rm --label ai-agent=true --name "$runner" \
    --device /dev/kvm \
    --network "container:$server" \
    -v "$repo_root:$repo_root" -w "$repo_root" \
    -v "$gradle_cache:/cache/gradle" -e GRADLE_USER_HOME=/cache/gradle \
    -e SERVER_URL="$server_url" -e LOGCAT="$log_dir/logcat-$run_id.txt" \
    -e DEVICE_PROFILE="${DEVICE_PROFILE:-}" -e GPU="${GPU:-}" -e CORES="${CORES:-}" -e RAM_SIZE="${RAM_SIZE:-}" \
    -e DISK_SIZE="${DISK_SIZE:-}" -e SNAPSHOT_BOOT="${SNAPSHOT_BOOT:-}" -e TIMEOUT_SCALE="${TIMEOUT_SCALE:-1}" \
    "$GIM_EMULATOR_IMAGE" bash -c "$GIM_EMULATOR_BOOT"'
status=0
./gradlew --no-daemon connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.gimServerUrl=$SERVER_URL" \
    "-Pandroid.testInstrumentationRunnerArguments.timeoutScale=$TIMEOUT_SCALE" "$@" || status=$?
if ((status)); then
    adb logcat -d >"$LOGCAT" 2>&1 || true
    echo "full device logcat: $LOGCAT" >&2
fi
exit "$status"
' device-tests "$@" || status=$?

if ((status)); then
    echo "device tests failed (exit $status); gim-server log tail:" >&2
    docker logs --tail 50 "$server" >&2 || true
fi
exit "$status"
