# Shared emulator / gim-hub plumbing for scripts/run-device-tests.sh.
# Source it; it defines functions and variables only. Every container and image carries --label ai-agent=true
# plus --label ai-agent-run=$GIM_RUN_LABEL (set GIM_RUN_LABEL before calling; default gim-android-$$).
#
#   gim_check_host_paths ROOT     fail unless ROOT is a host-visible path (not /work/..., /root/...)
#   gim_ensure_emulator_image     build ai-agent/gim-android-emulator once (REBUILD_IMAGE=1 forces it)
#   gim_ensure_hub_image ROOT     build ai-agent/gim-hub:<sha12> once from `git archive` of the commit in
#                                 ROOT/e2e/GIM_HUB_VERSION (checkout: GIM_HUB_SRC, default
#                                 /srv/gimhq/im-client/hub) with the hub's own Dockerfile; sets GIM_HUB_IMAGE
#   gim_start_hub NAME ROOT       run that image as NAME with an ephemeral /data (tmpfs), wait for /healthz
#   gim_containers_dead NAME...   print "NAME <state>" for each container that is missing or not running, and
#                                 "NAME unhealthy" for a gim-hub (name ending in -hub) whose /healthz does not answer;
#                                 nothing when all are fine. Containers removed by someone else mid-run show up here.
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
# The emulator shares the hub container's network namespace (--network container:NAME), so
# http://10.0.2.2:8080 (the emulator's host loopback) reaches the hub.

GIM_EMULATOR_IMAGE=ai-agent/gim-android-emulator
GIM_EMULATOR_API=35
GIM_SYSTEM_IMAGE="system-images;android-$GIM_EMULATOR_API;google_apis;x86_64"
GIM_EMULATOR_SERVER_URL=http://10.0.2.2:8080
GIM_HUB_SRC=${GIM_HUB_SRC:-/srv/gimhq/im-client/hub}
GIM_RUN_LABEL=${GIM_RUN_LABEL:-gim-android-$$}

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
    docker build --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" -t "$GIM_EMULATOR_IMAGE" - <<EOF
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

gim_ensure_hub_image() {
    local root=$1 rev tmp status=0
    rev=$(tr -d '[:space:]' <"$root/e2e/GIM_HUB_VERSION")
    GIM_HUB_IMAGE=ai-agent/gim-hub:${rev:0:12}
    if docker image inspect "$GIM_HUB_IMAGE" >/dev/null 2>&1; then
        echo "reusing $GIM_HUB_IMAGE"
        return 0
    fi
    git -C "$GIM_HUB_SRC" rev-parse --verify --quiet "$rev^{commit}" >/dev/null || {
        echo "error: gim-hub commit $rev not found in $GIM_HUB_SRC" >&2
        return 1
    }
    tmp=$root/.tmp/gim-hub-src-$$
    rm -rf "$tmp"
    mkdir -p "$tmp"
    git -C "$GIM_HUB_SRC" archive "$rev" | tar -x -C "$tmp"
    echo "building $GIM_HUB_IMAGE from $GIM_HUB_SRC @ $rev"
    docker build --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --label "gim.hub.commit=$rev" \
        -t "$GIM_HUB_IMAGE" "$tmp" || status=$?
    rm -rf "$tmp"
    return "$status"
}

gim_containers_dead() {
    local name state
    for name in "$@"; do
        state=$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null) || state=gone
        if [[ $state != running ]]; then
            echo "$name $state"
        elif [[ $name == *-hub ]] && ! docker exec "$name" gim-hub healthcheck >/dev/null 2>&1; then
            echo "$name unhealthy"
        fi
    done
}

gim_start_hub() {
    local name=$1 root=$2
    gim_ensure_hub_image "$root"
    echo "starting gim-hub ($GIM_HUB_IMAGE)"
    # Disposable: the hub's data lives in memory and goes with the container.
    docker run -d --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$name" \
        --tmpfs /data:rw,uid=10001,gid=10001,mode=0700 \
        -e GIM_HUB_LISTEN=0.0.0.0:8080 -e GIM_HUB_DATA_DIR=/data \
        "$GIM_HUB_IMAGE" >/dev/null
    local _
    for _ in $(seq 1 60); do
        # `gim-hub healthcheck` is GET /healthz on GIM_HUB_LISTEN, exit 0 on 200.
        if docker exec "$name" gim-hub healthcheck >/dev/null 2>&1; then
            echo "gim-hub healthy"
            return 0
        fi
        sleep 1
    done
    echo "error: gim-hub did not become healthy" >&2
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
