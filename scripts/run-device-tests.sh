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
#
# Flow:
#   1. Builds (once) ai-agent/gim-android-emulator: ghcr.io/cirruslabs/android-sdk:35 plus the emulator
#      and system-images;android-35;google_apis;x86_64.
#   2. Starts e2e/gim-server in an oven/bun:1.4 container and waits for GET /healthz.
#   3. Boots the emulator and runs Gradle in a container with --device /dev/kvm that shares the server
#      container's network namespace, so http://10.0.2.2:8080 (the emulator's host loopback) reaches
#      the server exactly as in CI.
# Only the worktree (host path == container path) and the Gradle cache are mounted. Every container
# carries --label ai-agent=true and is removed on exit. The worktree must live on a host-visible path
# (e.g. /srv/...), not a container-local alias such as /work.
set -euo pipefail

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
    sed -n '2,23p' "$0"
    exit 0
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
gradle_cache=${GRADLE_CACHE:-/srv/.tmp/cache/gradle}
image=ai-agent/gim-android-emulator
api_level=35
system_image="system-images;android-$api_level;google_apis;x86_64"
server_url=http://10.0.2.2:8080
run_id=$$
server=ai-agent-gim-server-$run_id
runner=ai-agent-gim-android-device-$run_id

command -v docker >/dev/null || { echo "error: docker is required" >&2; exit 1; }
case "$repo_root" in
    /work/* | /root/*)
        echo "error: $repo_root is a container-local path; run from its /srv/... form" >&2
        exit 1
        ;;
esac

cleanup() {
    docker rm -f "$runner" "$server" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

if [[ ${REBUILD_IMAGE:-0} == 1 ]] || ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "building $image"
    docker build --label ai-agent=true -t "$image" - <<EOF
FROM ghcr.io/cirruslabs/android-sdk:35
RUN apt-get update \\
    && apt-get install -y --no-install-recommends libpulse0 libnss3 libxcomposite1 libxcursor1 \\
        libxdamage1 libxi6 libxtst6 libxkbfile1 libgl1 libasound2t64 \\
    && rm -rf /var/lib/apt/lists/*
RUN yes | sdkmanager --licenses >/dev/null \\
    && sdkmanager --install emulator "$system_image" >/dev/null \\
    && echo no | avdmanager create avd -n device-tests -k "$system_image" -d pixel_6
ENV PATH=\$PATH:/opt/android-sdk-linux/emulator
EOF
fi

echo "starting gim-server ($(cat "$repo_root/e2e/gim-server/VERSION"))"
docker run -d --label ai-agent=true --name "$server" \
    -v "$repo_root/e2e/gim-server:/src:ro" \
    -e PORT=8080 -e HOST=0.0.0.0 -e DATABASE_PATH=/tmp/gim/gim.db -e DATA_DIR=/tmp/gim \
    oven/bun:1.4 \
    sh -c 'cp -R /src /app && cd /app && bun install --frozen-lockfile && exec bun src/index.ts' >/dev/null

healthy=0
for _ in $(seq 1 60); do
    if docker exec "$server" bun -e \
        "fetch('http://127.0.0.1:8080/healthz').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))" \
        >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 1
done
if ((!healthy)); then
    echo "error: gim-server did not become healthy" >&2
    docker logs "$server" >&2 || true
    exit 1
fi
echo "gim-server healthy"

mkdir -p "$gradle_cache"
status=0
docker run --rm --label ai-agent=true --name "$runner" \
    --device /dev/kvm \
    --network "container:$server" \
    -v "$repo_root:$repo_root" -w "$repo_root" \
    -v "$gradle_cache:/cache/gradle" -e GRADLE_USER_HOME=/cache/gradle \
    -e SERVER_URL="$server_url" \
    "$image" bash -c '
set -euo pipefail
emulator -avd device-tests -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    -camera-back none >/tmp/emulator.log 2>&1 &
adb wait-for-device
for _ in $(seq 1 180); do
    [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r") == 1 ]] && break
    sleep 2
done
if [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r") != 1 ]]; then
    echo "error: emulator did not boot" >&2
    tail -n 50 /tmp/emulator.log >&2
    exit 1
fi
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 0
done
echo "emulator booted"
./gradlew --no-daemon connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.gimServerUrl=$SERVER_URL" "$@"
' device-tests "$@" || status=$?

if ((status)); then
    echo "device tests failed (exit $status); gim-server log tail:" >&2
    docker logs --tail 50 "$server" >&2 || true
fi
exit "$status"
