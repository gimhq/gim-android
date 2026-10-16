# Shared emulator / gim-hub plumbing for scripts/run-device-tests.sh.
# Source it; it defines functions and variables only. Every container and image carries --label ai-agent=true
# plus --label ai-agent-run=$GIM_RUN_LABEL (set GIM_RUN_LABEL before calling; default gim-android-$$).
#
#   gim_check_host_paths ROOT     fail unless ROOT is a host-visible path (not /work/..., /root/...)
#   gim_ensure_emulator_image     build ai-agent/gim-android-emulator once (REBUILD_IMAGE=1 forces it)
#   gim_ensure_hub_image ROOT     build ai-agent/gim-hub:<sha12> once from `git archive` of the commit in
#                                 ROOT/e2e/GIM_HUB_VERSION (checkout: GIM_HUB_SRC, default
#                                 /srv/gimhq/im-client/hub) with the hub's own Dockerfile; sets GIM_HUB_IMAGE
#   gim_start_hub NAME ROOT [fed] run that image as NAME with GIM_HUB_PUBLIC_URL=$GIM_EMULATOR_SERVER_URL and an
#                                 ephemeral /data (tmpfs), wait for /healthz, then gim_alias_emulator_host NAME. With
#                                 `fed`, NAME runs federated and ready for gim_start_hub2 (see below): /data and the hub
#                                 root key live in a volume NAME-data, the federation config in ROOT/.tmp/gim-fed-<label>.
#   gim_start_hub2 NAME NAME2 ROOT
#                                 hub B for 2-hub runs: start NAME2 in NAME's network namespace (NAME started with
#                                 `fed`) on port 8081 with GIM_HUB_PUBLIC_URL=$GIM_EMULATOR_SERVER_URL2, wait for its
#                                 /healthz and until both hubs have bound each other
#   gim_alias_emulator_host NAME  add 10.0.2.2/32 to lo in NAME's network namespace (short-lived alpine helper with
#                                 --cap-add NET_ADMIN), so CLI peers sharing it reach the hubs at their public URLs
#   gim_cleanup_hubs ROOT         remove this run's hub volumes and federation config (after removing the containers)
#   gim_ensure_push_mock_image ROOT
#                                 build ai-agent/gim-push-mock:<sha12> once from the same `git archive` with the hub's
#                                 Dockerfile.push-mock (like its scripts/docker-build.sh IMAGE push-mock); sets
#                                 GIM_PUSH_MOCK_IMAGE
#   gim_push_prepare WEBHOOK_URL  pick a random shared secret (GIM_PUSH_MOCK_SECRET, kept when set) and set GIM_HUB_PUSH_ARGS, the
#                                 hub env pointing its push webhook at WEBHOOK_URL; gim_start_hub, gim_start_hub2 and
#                                 gim_real_start_hub pass GIM_HUB_PUSH_ARGS to every hub they start
#   gim_start_push_mock NAME ROOT DOCKER_ARGS...
#                                 run gim-push-mock (docs/push-mock.md in gim-hub) as NAME with GIM_PUSH_MOCK_SECRET and
#                                 DOCKER_ARGS (network / ports), wait for its /healthz
#   gim_containers_dead NAME...   print "NAME <state>" for each container that is missing or not running, and
#                                 "NAME unhealthy" for a gim-hub (name ending in -hub or -hub2) whose /healthz does not answer;
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
#
# URL-bound sign-in: gim-core signs in only at the URL the hub proves, its GIM_HUB_PUBLIC_URL, so every client of a
# hub must use exactly that URL. The app inside the emulator uses http://10.0.2.2:8080 (and :8081 for hub B), so
# the hubs run with those public URLs, and every other client uses them too: in-process test peers inside the
# emulator reach them like the app; gim-cli runs in the hub's network namespace, where 10.0.2.2 is added to lo
# (gim_alias_emulator_host), so http://10.0.2.2:8080 reaches hub A on its 0.0.0.0:8080 listener there as well.
# Hub-to-hub federation (libp2p, TCP with a pre-shared key) uses /ip4/127.0.0.1/tcp/4001 (A) and 4002 (B) in that
# namespace.

GIM_EMULATOR_IMAGE=ai-agent/gim-android-emulator
GIM_EMULATOR_API=35
GIM_SYSTEM_IMAGE="system-images;android-$GIM_EMULATOR_API;google_apis;x86_64"
GIM_EMULATOR_SERVER_URL=http://10.0.2.2:8080
GIM_EMULATOR_SERVER_URL2=http://10.0.2.2:8081
GIM_HUB_SRC=${GIM_HUB_SRC:-/srv/gimhq/im-client/hub}
GIM_RUN_LABEL=${GIM_RUN_LABEL:-gim-android-$$}
GIM_HUB_PUSH_ARGS=()

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

# gim_hub_archive_build ROOT IMAGE DOCKERFILE: builds IMAGE once from `git archive` of the pinned gim-hub commit.
gim_hub_archive_build() {
    local root=$1 image=$2 dockerfile=$3 rev tmp status=0
    rev=$(tr -d '[:space:]' <"$root/e2e/GIM_HUB_VERSION")
    if docker image inspect "$image" >/dev/null 2>&1; then
        echo "reusing $image"
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
    echo "building $image from $GIM_HUB_SRC @ $rev ($dockerfile)"
    docker build --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --label "gim.hub.commit=$rev" \
        --build-arg "GIM_HUB_BUILD_REV=$rev" -f "$tmp/$dockerfile" -t "$image" "$tmp" || status=$?
    rm -rf "$tmp"
    return "$status"
}

gim_ensure_hub_image() {
    local rev
    rev=$(tr -d '[:space:]' <"$1/e2e/GIM_HUB_VERSION")
    GIM_HUB_IMAGE=ai-agent/gim-hub:${rev:0:12}
    gim_hub_archive_build "$1" "$GIM_HUB_IMAGE" Dockerfile
}

gim_ensure_push_mock_image() {
    local rev
    rev=$(tr -d '[:space:]' <"$1/e2e/GIM_HUB_VERSION")
    GIM_PUSH_MOCK_IMAGE=ai-agent/gim-push-mock:${rev:0:12}
    gim_hub_archive_build "$1" "$GIM_PUSH_MOCK_IMAGE" Dockerfile.push-mock
}

gim_push_prepare() {
    # The hub requires at least 32 bytes. Kept when already set, so the mock can start before its URL is known.
    [[ -n ${GIM_PUSH_MOCK_SECRET:-} ]] || GIM_PUSH_MOCK_SECRET=$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')
    GIM_HUB_PUSH_ARGS=(-e "GIM_HUB_PUSH_WEBHOOK_URL=$1" -e "GIM_HUB_PUSH_WEBHOOK_SECRET=$GIM_PUSH_MOCK_SECRET")
}

gim_start_push_mock() {
    local name=$1 root=$2
    shift 2
    gim_ensure_push_mock_image "$root"
    docker run -d --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$name" "$@" \
        -e GIM_PUSH_MOCK_LISTEN=0.0.0.0:8090 -e "GIM_PUSH_MOCK_SECRET=$GIM_PUSH_MOCK_SECRET" \
        "$GIM_PUSH_MOCK_IMAGE" >/dev/null
    gim_wait_push_mock_healthy "$name"
}

gim_wait_push_mock_healthy() {
    local name=$1 _
    for _ in $(seq 1 60); do
        if docker exec "$name" gim-push-mock healthcheck >/dev/null 2>&1; then
            echo "gim-push-mock $name healthy"
            return 0
        fi
        sleep 1
    done
    echo "error: gim-push-mock $name did not become healthy" >&2
    docker logs "$name" >&2 || true
    return 1
}

gim_containers_dead() {
    local name state
    for name in "$@"; do
        state=$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null) || state=gone
        if [[ $state != running ]]; then
            echo "$name $state"
        elif [[ $name == *-hub || $name == *-hub2 ]] && ! docker exec "$name" gim-hub healthcheck >/dev/null 2>&1; then
            echo "$name unhealthy"
        fi
    done
}

gim_wait_hub_healthy() {
    local name=$1 _
    for _ in $(seq 1 60); do
        # `gim-hub healthcheck` is GET /healthz on GIM_HUB_LISTEN, exit 0 on 200.
        if docker exec "$name" gim-hub healthcheck >/dev/null 2>&1; then
            echo "gim-hub $name healthy"
            return 0
        fi
        sleep 1
    done
    echo "error: gim-hub $name did not become healthy" >&2
    docker logs "$name" >&2 || true
    return 1
}

gim_fed_dir() {
    echo "$1/.tmp/gim-fed-$GIM_RUN_LABEL"
}

# gim_fed_hub_id VOLUME: creates the hub root key in VOLUME and prints the hub id.
gim_fed_hub_id() {
    docker volume create --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" "$1" >/dev/null
    docker run --rm --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" -v "$1:/data" \
        -e GIM_HUB_DATA_DIR=/data "$GIM_HUB_IMAGE" gim-hub hub-id | tr -d '\r\n'
}

# Closed federation of hubs A (NAME) and B (NAME2), like gim-core's scripts/gim-hub-dev.sh fed-start: both allow
# each other and share a libp2p PSK; they live in one network namespace, so the peers' addresses are loopback ports.
gim_fed_prepare() {
    local name=$1 root=$2 dir id_a id_b
    dir=$(gim_fed_dir "$root")
    rm -rf "$dir"
    mkdir -p "$dir"
    id_a=$(gim_fed_hub_id "$name-data")
    id_b=$(gim_fed_hub_id "${name}2-data")
    echo "federation: hub A $id_a, hub B $id_b"
    {
        echo "/key/swarm/psk/1.0.0/"
        echo "/base16/"
        od -An -tx1 -N32 /dev/urandom | tr -d ' \n'
        echo
    } >"$dir/swarm.key"
    printf '%s /ip4/127.0.0.1/tcp/4001\n%s /ip4/127.0.0.1/tcp/4002\n' "$id_a" "$id_b" >"$dir/allowlist"
    chmod 0755 "$dir"
    chmod 0644 "$dir"/*
    GIM_FED_HUB_ID_A=$id_a
    GIM_FED_HUB_ID_B=$id_b
}

# gim_fed_args P2P_PORT ROOT: docker run arguments of a federated hub listening for peers on P2P_PORT.
gim_fed_args() {
    GIM_FED_ARGS=(-v "$(gim_fed_dir "$2"):/config:ro" -e GIM_HUB_FEDERATION=true
        -e "GIM_HUB_P2P_LISTEN=/ip4/0.0.0.0/tcp/$1" -e "GIM_HUB_P2P_EXTERNAL_ADDRS=/ip4/127.0.0.1/tcp/$1"
        -e GIM_HUB_FEDERATION_ALLOWLIST=/config/allowlist -e GIM_HUB_P2P_PSK_FILE=/config/swarm.key
        -e GIM_HUB_S2S_TIMEOUT_SECONDS=3 -e GIM_HUB_OUTBOX_BACKOFF_MAX_SECONDS=2)
}

gim_alias_emulator_host() {
    docker run --rm --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --network "container:$1" \
        --cap-add NET_ADMIN alpine:3 ip addr add 10.0.2.2/32 dev lo
}

gim_start_hub() {
    local name=$1 root=$2 mode=${3:-}
    gim_ensure_hub_image "$root"
    echo "starting gim-hub ($GIM_HUB_IMAGE) at $GIM_EMULATOR_SERVER_URL${mode:+ ($mode)}"
    # shellcheck disable=SC2054 # the commas belong to the --tmpfs options
    local data=(--tmpfs /data:rw,uid=10001,gid=10001,mode=0700)
    GIM_FED_ARGS=()
    if [[ $mode == fed ]]; then
        gim_fed_prepare "$name" "$root"
        data=(-v "$name-data:/data")
        gim_fed_args 4001 "$root"
    fi
    # Disposable: the hub's data lives in memory (or the run's volume) and goes with the run.
    docker run -d --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$name" "${data[@]}" \
        -e GIM_HUB_LISTEN=0.0.0.0:8080 -e GIM_HUB_DATA_DIR=/data -e "GIM_HUB_PUBLIC_URL=$GIM_EMULATOR_SERVER_URL" \
        -e RUST_LOG=info "${GIM_FED_ARGS[@]}" "${GIM_HUB_PUSH_ARGS[@]}" "$GIM_HUB_IMAGE" >/dev/null
    gim_wait_hub_healthy "$name" || return 1
    gim_alias_emulator_host "$name"
}

# gim_fed_wait_bound NAME HUB_ID: waits until hub NAME's log shows its binding of HUB_ID.
gim_fed_wait_bound() {
    local _
    for _ in $(seq 1 60); do
        docker logs "$1" 2>&1 | grep "peer hub bound" | grep -q "$2" && return 0
        sleep 1
    done
    echo "error: $1 did not bind peer hub $2" >&2
    docker logs --tail 30 "$1" >&2 || true
    return 1
}

gim_start_hub2() {
    local name=$1 name2=$2 root=$3
    [[ -n ${GIM_FED_HUB_ID_B:-} ]] || { echo "error: start $name with gim_start_hub ... fed first" >&2; return 1; }
    echo "starting gim-hub B ($GIM_HUB_IMAGE) at $GIM_EMULATOR_SERVER_URL2 in $name's network namespace"
    gim_fed_args 4002 "$root"
    docker run -d --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$name2" \
        --network "container:$name" -v "${name}2-data:/data" \
        -e GIM_HUB_LISTEN=0.0.0.0:8081 -e GIM_HUB_DATA_DIR=/data -e "GIM_HUB_PUBLIC_URL=$GIM_EMULATOR_SERVER_URL2" \
        -e RUST_LOG=info "${GIM_FED_ARGS[@]}" "${GIM_HUB_PUSH_ARGS[@]}" "$GIM_HUB_IMAGE" >/dev/null
    gim_wait_hub_healthy "$name2" || return 1
    gim_fed_wait_bound "$name" "$GIM_FED_HUB_ID_B" && gim_fed_wait_bound "$name2" "$GIM_FED_HUB_ID_A" || return 1
    echo "hubs $name and $name2 federated"
}

gim_cleanup_hubs() {
    docker volume ls -q --filter "label=ai-agent-run=$GIM_RUN_LABEL" | xargs -r docker volume rm >/dev/null 2>&1 || true
    rm -rf "$(gim_fed_dir "$1")"
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
