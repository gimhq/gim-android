# Shared emulator / gim-server plumbing for scripts/run-device-tests.sh and scripts/e2e-interop.sh.
# Source it; it defines functions and variables only. Every container carries --label ai-agent=true.
#
#   gim_check_host_paths ROOT     fail unless ROOT is a host-visible path (not /work/..., /root/...)
#   gim_ensure_emulator_image     build ai-agent/gim-android-emulator once (REBUILD_IMAGE=1 forces it)
#   gim_start_server NAME ROOT    run ROOT/e2e/gim-server in oven/bun:1.4 as NAME, wait for /healthz
#   GIM_EMULATOR_BOOT             bash code, run inside the emulator image (with --device /dev/kvm): boots
#                                 the AVD headless, waits for sys.boot_completed, disables animations.
#                                 Reads these container env vars (defaults match the image's pixel_6 AVD):
#                                   DEVICE_PROFILE  avdmanager device, or "default" for no device (320x640
#                                                   @160dpi, what android-emulator-runner creates without
#                                                   a profile); default pixel_6
#                                   GPU             emulator -gpu mode, default swiftshader_indirect
#                                   CORES           emulator -cores; RAM_SIZE emulator -memory (MB);
#                                                   DISK_SIZE data partition, e.g. 4096M (new AVDs only)
#                                   SNAPSHOT_BOOT=1 boot once, save a snapshot, then boot from it with
#                                                   -no-snapshot-save, like CI's cached-AVD flow
#
# The emulator shares the server container's network namespace (--network container:NAME), so
# http://10.0.2.2:8080 (the emulator's host loopback) reaches the server.

GIM_EMULATOR_IMAGE=ai-agent/gim-android-emulator
GIM_EMULATOR_API=35
GIM_SYSTEM_IMAGE="system-images;android-$GIM_EMULATOR_API;google_apis;x86_64"
GIM_EMULATOR_SERVER_URL=http://10.0.2.2:8080

gim_check_host_paths() {
    command -v docker >/dev/null || { echo "error: docker is required" >&2; return 1; }
    case "$1" in
        /work/* | /root/*)
            echo "error: $1 is a container-local path; run from its /srv/... form" >&2
            return 1
            ;;
    esac
}

gim_ensure_emulator_image() {
    if [[ ${REBUILD_IMAGE:-0} != 1 ]] && docker image inspect "$GIM_EMULATOR_IMAGE" >/dev/null 2>&1; then
        return 0
    fi
    echo "building $GIM_EMULATOR_IMAGE"
    docker build --label ai-agent=true -t "$GIM_EMULATOR_IMAGE" - <<EOF
FROM ghcr.io/cirruslabs/android-sdk:35
RUN apt-get update \\
    && apt-get install -y --no-install-recommends libpulse0 libnss3 libxcomposite1 libxcursor1 \\
        libxdamage1 libxi6 libxtst6 libxkbfile1 libgl1 libasound2t64 \\
    && rm -rf /var/lib/apt/lists/*
RUN yes | sdkmanager --licenses >/dev/null \\
    && sdkmanager --install emulator "$GIM_SYSTEM_IMAGE" >/dev/null \\
    && echo no | avdmanager create avd -n device-tests -k "$GIM_SYSTEM_IMAGE" -d pixel_6
ENV PATH=\$PATH:/opt/android-sdk-linux/emulator
EOF
}

gim_start_server() {
    local name=$1 root=$2
    echo "starting gim-server ($(cat "$root/e2e/gim-server/VERSION"))"
    docker run -d --label ai-agent=true --name "$name" \
        -v "$root/e2e/gim-server:/src:ro" \
        -e PORT=8080 -e HOST=0.0.0.0 -e DATABASE_PATH=/tmp/gim/gim.db -e DATA_DIR=/tmp/gim \
        oven/bun:1.4 \
        sh -c 'cp -R /src /app && cd /app && bun install --frozen-lockfile && exec bun src/index.ts' >/dev/null
    local _
    for _ in $(seq 1 60); do
        if docker exec "$name" bun -e \
            "fetch('http://127.0.0.1:8080/healthz').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))" \
            >/dev/null 2>&1; then
            echo "gim-server healthy"
            return 0
        fi
        sleep 1
    done
    echo "error: gim-server did not become healthy" >&2
    docker logs "$name" >&2 || true
    return 1
}

# shellcheck disable=SC2016 # expanded inside the emulator container
GIM_EMULATOR_BOOT='
set -euo pipefail
avd=device-tests
profile=${DEVICE_PROFILE:-pixel_6}
if [[ $profile != pixel_6 ]]; then
    avd=device-tests-$profile
    if [[ ! -d ~/.android/avd/$avd.avd ]]; then
        device_args=()
        [[ $profile == default ]] || device_args=(-d "$profile")
        echo no | avdmanager create avd -n "$avd" -k "'"$GIM_SYSTEM_IMAGE"'" "${device_args[@]}" >/dev/null
        [[ -z ${DISK_SIZE:-} ]] || echo "disk.dataPartition.size=$DISK_SIZE" >>~/.android/avd/"$avd".avd/config.ini
    fi
fi
emulator_args=(-avd "$avd" -no-window -no-audio -no-boot-anim -gpu "${GPU:-swiftshader_indirect}" -camera-back none)
[[ -z ${CORES:-} ]] || emulator_args+=(-cores "$CORES")
[[ -z ${RAM_SIZE:-} ]] || emulator_args+=(-memory "$RAM_SIZE")
boot() {
    setsid emulator "${emulator_args[@]}" "$@" >>/tmp/emulator.log 2>&1 &
    adb wait-for-device
    for _ in $(seq 1 180); do
        [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r") == 1 ]] && return 0
        sleep 2
    done
    echo "error: emulator did not boot" >&2
    tail -n 50 /tmp/emulator.log >&2
    exit 1
}
if [[ ${SNAPSHOT_BOOT:-0} == 1 ]]; then
    boot
    echo "emulator booted; saving snapshot"
    adb emu kill >/dev/null
    while pgrep -f "^[^ ]*qemu-system" >/dev/null; do sleep 1; done
    adb kill-server
    boot -no-snapshot-save
else
    boot -no-snapshot
fi
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 0
done
adb logcat -G 16M
echo "emulator booted ($avd, $(adb shell wm size | tr -d "\r"))"
'
