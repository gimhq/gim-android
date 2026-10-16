#!/usr/bin/env bash
# CLI x Android interop E2E: two gim CLI users (alice, bob) and the Android app (real UI, real gim-core) talk
# through gim-hub at the commit pinned in e2e/GIM_HUB_VERSION.
#
# Usage: scripts/e2e-interop.sh
#
# Environment:
#   GIM_CORE_REPO   gim-core checkout the CLI is built from (read only), default /srv/gimhq/im-client/core
#   GIM_CORE_REF    gim-core commit of the CLI, default 50f7915 (the commit GIM_CORE_VERSION pins)
#   GIM_HUB_SRC     gim-hub checkout the hub image is exported from, default /srv/gimhq/im-client/hub
#   LOG_DIR         run log directory, default /tmp/gim-interop-<timestamp>
#   GRADLE_CACHE    Gradle user home, default /srv/.tmp/cache/gradle
#   CARGO_CACHE     CARGO_HOME, default /srv/.tmp/cache/cargo
#   CARGO_TARGET    cargo target dir of gim-cli, default /srv/.tmp/cache/cargo-target/gim-cli-<core sha12> (one per
#                   core commit: cargo would treat artifacts of another commit's workspace crates as fresh)
#   REBUILD_IMAGE=1 rebuild the emulator image (see scripts/lib/device.sh)
#   GIM_DEVICE=real run on the test phone attached to the gim-adb-host container instead of an emulator (see
#                   scripts/lib/real-device.sh for GIM_REAL_SERIAL / GIM_REAL_MODEL, GIM_REAL_NET=lan|reverse,
#                   GIM_REAL_LAN_HOST and the package rules). Exit 4: device not attached at the start; exit 5:
#                   INTERRUPTED (infra), the device went away or offline during the run (checked after every step
#                   and on any failure), not a scenario failure. Before the test starts the phone is woken
#                   (KEYCODE_WAKEUP / `wm dismiss-keyguard`, no setting changes); exit 6: NOT READY (infra), the
#                   screen is still off or locked then, or when a step failed (a secure lock screen needs a person): builds first (pinned debug keystore), then takes the device lock, starts gim-hub
#                   (published on the host in lan mode, in gim-adb-host's network namespace in reverse mode) with
#                   GIM_HUB_PUBLIC_URL = the phone's URL, which the CLI uses too (lan: the CLI container on the default
#                   bridge network reaches the host's LAN address; reverse: it shares gim-adb-host's namespace), updates im.g.apps and im.g.apps.test in place and
#                   `pm clear`s im.g.apps before and after the run (never uninstalls). logcat is limited to the
#                   app's UID; a failure saves screenshot.png.
#
# Exit codes: 0 all steps passed; 1 a step failed; 5 INTERRUPTED (infra): a container of this run (hub, emulator, CLI)
# was removed or stopped by someone else, or the hub's /healthz stopped answering (checked after every step and on
# any failure); in real mode also 4 / 5 / 6 as described above.
#
# Outputs (in LOG_DIR, printed at start and end): script.log, hub.log, gradle.log, cargo.log,
# emulator.log, logcat.txt, markers.log (test step markers), instrumentation.txt (am instrument -r),
# cli-alice.log / cli-bob.log (every gim command, exit code and JSON output), bob-listen.jsonl.
#
# Flow:
#   1. gim-hub (ai-agent/gim-hub:<sha12>, see scripts/lib/device.sh) with an in-memory /data; the emulator (--device /dev/kvm, API 35 x86_64
#      google_apis, headless) and a rust:1 container running the CLI share its network namespace. The hub's
#      GIM_HUB_PUBLIC_URL is http://10.0.2.2:8080 and both use that URL (URL-bound sign-in): the app through the
#      emulator's host loopback, the CLI through 10.0.2.2 added to lo in that namespace (scripts/lib/device.sh).
#   2. gim-cli is built from `git archive GIM_CORE_REF` (exported to .tmp/gim-core-<sha>/, reused) in rust:1;
#      the debug and androidTest APKs in ghcr.io/cirruslabs/android-sdk:35.
#   3. app/src/androidTest/.../interop/InteropTest runs with `am instrument`, getting the run id, handles and
#      names as instrumentation arguments, while this script drives alice and bob step by step.
#
# Handshake: the test logs `STEP <name>` under logcat tag GimInterop (followed into markers.log) when it has
# done its part of a step; the script waits for that marker, then asserts and acts with the CLI (`--json`,
# `wait-for`). The test waits on UI state the CLI produces; where it needs an explicit go (the CLI listener
# being up) the script sends `am broadcast -a im.g.apps.interop.GO --es step <name>`. Every wait is bounded
# (<= 120 s); on failure both sides' logs are dumped. Containers carry --label ai-agent=true and
# --label ai-agent-run=gim-interop-<run>, are named ai-agent-gim-interop-<run>-* and are removed on exit, as is the
# run's .tmp/e2e-interop-<run>/ directory.
set -euo pipefail

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
    sed -n '2,54p' "$0"
    exit 0
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
run=$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
GIM_RUN_LABEL=gim-interop-$run
# shellcheck source=lib/device.sh
source "$repo_root/scripts/lib/device.sh"
device=${GIM_DEVICE:-emulator}
[[ $device == emulator || $device == real ]] || { echo "error: GIM_DEVICE must be emulator or real" >&2; exit 1; }
# shellcheck source=lib/real-device.sh
[[ $device == emulator ]] || source "$repo_root/scripts/lib/real-device.sh"
gim_check_host_paths "$repo_root"
command -v jq >/dev/null || { echo "error: jq is required" >&2; exit 1; }

core_repo=${GIM_CORE_REPO:-/srv/gimhq/im-client/core}
core_ref=${GIM_CORE_REF:-50f7915}
gradle_cache=${GRADLE_CACHE:-/srv/.tmp/cache/gradle}
cargo_cache=${CARGO_CACHE:-/srv/.tmp/cache/cargo}
cargo_target=${CARGO_TARGET:-}
log_dir=${LOG_DIR:-/tmp/gim-interop-$(date +%Y%m%d-%H%M%S)}
prefix=ai-agent-gim-interop-$run
hub=$prefix-hub
emu=$prefix-emulator
cli=$prefix-cli
work=$repo_root/.tmp/e2e-interop-$run
photo=$repo_root/app/src/androidTest/assets/e2e-photo.png
timeout=60

alice=alice_$run
bob=bob_$run
droid=droid_$run
alice_name="Alice $run"
bob_name="Bob $run"
droid_name="Android $run"

mkdir -p "$log_dir"
exec > >(tee -a "$log_dir/script.log") 2>&1
echo "run log: $log_dir"
echo "run id: $run, gim-core ref: $core_ref, device: $device"
[[ $device == emulator ]] || gim_real_resolve_serial

passed=()
live=()
interrupted_exit=5
bg_pids=()
instrument_pid=

dump_logs() {
    echo "---- instrumentation (tail) ----"
    tail -n 40 "$log_dir/instrumentation.txt" 2>/dev/null || true
    echo "---- markers ----"
    cat "$log_dir/markers.log" 2>/dev/null || true
    echo "---- logcat (errors, tail) ----"
    grep -E ' [EF]/|GimInterop|TestRunner' "$log_dir/logcat.txt" 2>/dev/null | tail -n 60 || true
    echo "---- CLI (tail) ----"
    tail -n 20 "$log_dir/cli-alice.log" "$log_dir/cli-bob.log" 2>/dev/null || true
    echo "---- hub (tail) ----"
    docker logs --tail 40 "$hub" 2>&1 || true
    if [[ $device == real ]] && ((gim_real_locked)); then
        gim_real_screenshot "$log_dir/screenshot.png"
        echo "screenshot: $log_dir/screenshot.png"
    fi
}

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ $device == real ]] && ((status == 1)) && [[ -n $GIM_REAL_SERIAL ]] && ! gim_real_attached; then
        echo "INTERRUPTED (infra): device $GIM_REAL_SERIAL not attached/online"
        status=$interrupted_exit
    elif ((status == 1)) && containers_dead "(at exit)"; then
        status=$interrupted_exit
    fi
    ((${#bg_pids[@]})) && kill "${bg_pids[@]}" 2>/dev/null || true
    docker logs "$hub" >"$log_dir/hub.log" 2>&1 || true
    docker exec "$emu" cat /tmp/emulator.log >"$log_dir/emulator.log" 2>/dev/null || true
    if ((status)); then
        echo "FAILED (exit $status) after ${SECONDS}s"
        dump_logs
    fi
    # Files in $work are written by root inside the CLI container; remove them from there.
    docker exec "$cli" rm -rf "$work" >/dev/null 2>&1 || true
    rm -rf "$work" 2>/dev/null || true
    if [[ $device == real ]] && ((gim_real_locked)); then
        gim_real_kill_bg
        gim_real_reset
        gim_real_stop_hub "$hub"
        gim_real_unlock
    fi
    docker rm -f "$emu" "$cli" "$hub" >/dev/null 2>&1 || true
    gim_cleanup_hubs "$repo_root"
    echo "run log: $log_dir"
    exit "$status"
}
trap cleanup EXIT INT TERM

# Containers this run started (hub, emulator, CLI) and that the scenario needs; one removed or stopped by someone else,
# or a hub whose /healthz stops answering, is an infrastructure interruption (exit 5), not a scenario result.
containers_dead() {
    ((${#live[@]})) || return 1
    local dead
    dead=$(gim_containers_dead "${live[@]}" | paste -sd',')
    [[ -n $dead ]] || return 1
    echo "INTERRUPTED (infra): container $dead $1 (passed so far: ${#passed[@]})" >&2
}

check_containers() {
    containers_dead "$1" || return 0
    exit "$interrupted_exit"
}

# On the phone, a lost transport is an infrastructure interruption (exit 5), not a scenario result.
check_attached() {
    [[ $device == real ]] || return 0
    [[ -n $GIM_REAL_SERIAL ]] && ! gim_real_attached || return 0
    echo "INTERRUPTED (infra): device $GIM_REAL_SERIAL not attached/online $1 (passed so far: ${#passed[@]})" >&2
    exit "$interrupted_exit"
}

fail() {
    check_containers "($*)"
    check_attached "($*)"
    [[ $device == emulator ]] || gim_real_require_ready
    echo "error: $*" >&2
    exit 1
}

pass() {
    passed+=("$1")
    echo "PASS $1"
    check_containers "after: $1"
    check_attached "after: $1"
}

# --- CLI helpers --------------------------------------------------------------------------------------

# cli WHO ARGS...: runs `gim --json ARGS` with WHO's profile; sets CLI_OUT and CLI_RC, logs both.
cli() {
    local who=$1
    shift
    CLI_RC=0
    CLI_OUT=$(docker exec -e GIM_PROFILE="$work/$who" "$cli" "$work/gim" --json "$@" 2>>"$log_dir/cli-$who.log") \
        || CLI_RC=$?
    printf '$ gim %s  [exit %s]\n%s\n' "$*" "$CLI_RC" "$CLI_OUT" >>"$log_dir/cli-$who.log"
}

cli_ok() {
    cli "$@"
    ((CLI_RC == 0)) || fail "$1: gim ${*:2} exited $CLI_RC: $CLI_OUT"
}

out() {
    jq -r "$1" <<<"$CLI_OUT"
}

# expect JQ_FILTER DESCRIPTION: asserts the filter is true for CLI_OUT.
expect() {
    [[ $(jq -r "$1" <<<"$CLI_OUT") == true ]] || fail "$2: $CLI_OUT"
}

# poll DESCRIPTION CMD...: retries CMD every second for up to $timeout seconds.
poll() {
    local what=$1
    shift
    local _
    for _ in $(seq 1 "$timeout"); do
        "$@" && return 0
        sleep 1
    done
    fail "timed out waiting for $what"
}

# --- Android helpers ----------------------------------------------------------------------------------

adb_() {
    if [[ $device == real ]]; then
        gim_real_adb "$@"
    else
        docker exec "$emu" adb "$@"
    fi
}

# adb_bg NAME ARGS...: a long-running adb command (logcat, am instrument) the caller puts in the background; on the
# phone its container-side process is tracked so cleanup can stop it.
adb_bg() {
    if [[ $device == real ]]; then
        gim_real_bg "$@"
    else
        adb_ "${@:2}"
    fi
}

has_marker() {
    grep -qE "STEP $1\$" "$log_dir/markers.log"
}

instrument_running() {
    kill -0 "$instrument_pid" 2>/dev/null
}

# wait_marker STEP: waits for the test's `STEP <step>` marker. The test may wait up to $timeout per UI state
# between two markers, so this waits twice that, leaving the test time to fail with its own message first.
wait_marker() {
    local _
    for _ in $(seq 1 $((timeout * 2))); do
        has_marker "$1" && return 0
        instrument_running || {
            has_marker "$1" && return 0
            fail "test ended before step '$1': $(grep -m1 ' FAIL ' "$log_dir/markers.log" || echo 'no FAIL marker')"
        }
        sleep 1
    done
    fail "timed out waiting for test step '$1'"
}

signal() {
    adb_ shell am broadcast -a im.g.apps.interop.GO -p im.g.apps --es step "$1" >/dev/null
}

# --- Setup --------------------------------------------------------------------------------------------

if [[ $device == emulator ]]; then
    gim_ensure_emulator_image
    gim_start_hub "$hub" "$repo_root"
    live+=("$hub")
    url=$GIM_EMULATOR_SERVER_URL
    app_url=$GIM_EMULATOR_SERVER_URL
    cli_network=container:$hub

    echo "starting emulator"
    docker run -d --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$emu" \
        --device /dev/kvm --network "container:$hub" \
        -v "$repo_root:$repo_root:ro" \
        "$GIM_EMULATOR_IMAGE" sleep infinity >/dev/null
    live+=("$emu")
    docker exec "$emu" bash -c "$GIM_EMULATOR_BOOT" >"$log_dir/emulator-boot.log" 2>&1 &
    boot_pid=$!
    bg_pids+=("$boot_pid")
fi

echo "building gim-cli at $core_ref"
core_sha=$(git -C "$core_repo" rev-parse --short=12 "$core_ref^{commit}")
cargo_target=${cargo_target:-/srv/.tmp/cache/cargo-target/gim-cli-$core_sha}
core_src=$repo_root/.tmp/gim-core-$core_sha
if [[ ! -f $core_src/.exported ]]; then
    rm -rf "$core_src"
    mkdir -p "$core_src"
    # -m: extraction time as mtime, so cargo never takes the commit-time sources for older than its artifacts.
    git -C "$core_repo" archive "$core_sha" | tar -x -m -C "$core_src"
    touch "$core_src/.exported"
fi
mkdir -p "$cargo_cache" "$cargo_target" "$work"
docker run --rm --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$prefix-cargo" \
    -v "$core_src:$core_src" -w "$core_src" \
    -v "$cargo_cache:/cache/cargo" -e CARGO_HOME=/cache/cargo \
    -v "$cargo_target:/cache/target" -e CARGO_TARGET_DIR=/cache/target \
    rust:1 cargo build -p gim-cli \
    >"$log_dir/cargo.log" 2>&1 || { tail -n 30 "$log_dir/cargo.log"; fail "gim-cli build failed"; }
cp "$cargo_target/debug/gim" "$work/gim"
start_cli() {
    docker run -d --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$cli" --network "$cli_network" \
        -v "$work:$work" -w "$work" rust:1 sleep infinity >/dev/null
    live+=("$cli")
}
# On the phone the hub starts once the builds are done and the device lock is held; the CLI follows it.
[[ $device == real ]] || start_cli
cp "$photo" "$work/bob-photo.png"

echo "building APKs"
mkdir -p "$gradle_cache"
signing=()
if [[ $device == real ]]; then
    gim_real_gradle_env
    signing=("${GIM_REAL_GRADLE_ENV[@]}")
fi
docker run --rm --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$prefix-gradle" \
    -v "$repo_root:$repo_root" -w "$repo_root" \
    -v "$gradle_cache:/cache/gradle" -e GRADLE_USER_HOME=/cache/gradle "${signing[@]}" \
    ghcr.io/cirruslabs/android-sdk:35 ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest \
    >"$log_dir/gradle.log" 2>&1 || { tail -n 40 "$log_dir/gradle.log"; fail "gradle build failed"; }

if [[ $device == emulator ]]; then
    wait "$boot_pid" || { cat "$log_dir/emulator-boot.log"; fail "emulator did not boot"; }
    cat "$log_dir/emulator-boot.log"
    # boot_completed comes before Android has a default network; InteropTest waits for the hub's /healthz itself.
    adb_ install -r -t "$repo_root/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
    adb_ install -r -t "$repo_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
else
    gim_real_lock
    gim_real_start_hub "$hub" "$repo_root"
    live+=("$hub")
    app_url=$GIM_REAL_HUB_URL
    if [[ $GIM_REAL_NET == reverse ]]; then
        cli_network=container:$GIM_REAL_ADB_CONTAINER
        url=$GIM_REAL_HUB_URL
    else
        # The hub's public URL is the host's LAN address; the default bridge network reaches its published port.
        cli_network=bridge
        url=$GIM_REAL_HUB_URL
    fi
    start_cli
    echo "device: $(adb_ shell getprop ro.product.model | tr -d '\r'), app hub URL $app_url, CLI hub URL $url"
    gim_real_install "$repo_root/app/build/outputs/apk/debug/app-debug.apk" \
        "$repo_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    adb_ shell pm clear im.g.apps >/dev/null
fi
echo "setup done after ${SECONDS}s"

# --- Scenario -----------------------------------------------------------------------------------------

# a. alice and bob create identities and claim handles (CLI `register`), then the test does it through onboarding.
cli_ok alice --server "$url" register "$alice" "$alice_name"
expect ".handle == \"$alice\" and (.user_id | length > 0) and (.recovery_code | length > 0)" "alice registered"
alice_id=$(out .user_id)
cli_ok bob --server "$url" register "$bob" "$bob_name"
expect ".handle == \"$bob\"" "bob registered"

if [[ $device == real ]]; then
    gim_real_wake
    gim_real_require_ready
    # Never clear the phone's log buffer: read from now on, and only the app's UID.
    since=$(adb_ shell date "'+%m-%d %H:%M:%S.000'" | tr -d '\r')
    app_uid=$(adb_ shell pm list packages -U im.g.apps | tr -d '\r' | sed -nE 's/^package:im\.g\.apps uid:([0-9]+)$/\1/p')
    logcat_args=(-T "$since" --uid="$app_uid")
else
    adb_ logcat -c
    logcat_args=()
fi
adb_bg logcat logcat -v time "${logcat_args[@]}" >"$log_dir/logcat.txt" 2>&1 &
bg_pids+=($!)
: >"$log_dir/markers.log"
adb_bg markers logcat -v brief "${logcat_args[@]}" -s GimInterop:I >"$log_dir/markers.log" 2>&1 &
bg_pids+=($!)
adb_bg instrument shell "am instrument -w -r -e class im.g.apps.interop.InteropTest \
    -e gimServerUrl $app_url -e interopRun $run \
    -e aliceName '$alice_name' -e aliceHandle $alice -e aliceUserId $alice_id -e bobName '$bob_name' -e androidHandle $droid -e androidName '$droid_name' \
    im.g.apps.test/androidx.test.runner.AndroidJUnitRunner" >"$log_dir/instrumentation.txt" 2>&1 &
instrument_pid=$!
bg_pids+=("$instrument_pid")

wait_marker registered
cli_ok alice users search "$droid"
# The hub directory knows handles only; display names travel in chats (findUser shows the handle).
expect "(.users | length == 1) and .users[0].handle == \"$droid\"" "alice finds android"
pass "a. identities + handles: alice (CLI), bob (CLI), android (onboarding UI); alice finds android"

# h. User ids: android found alice by handle with the id from alice's register JSON (asserted in the test);
# alice's lookup of android's handle returns the id android reports for itself.
wait_marker user-ids
droid_id=$(sed -nE 's/.*VALUE android-user-id ([^[:space:]]+).*/\1/p' "$log_dir/markers.log" | tail -n 1)
[[ -n $droid_id ]] || fail "android did not report its user id"
expect ".users[0].user_id == \"$droid_id\"" "alice's lookup of @$droid returns android's user id $droid_id"
pass "h. user ids: android looks up @$alice -> $alice_id (alice's CLI JSON); alice looks up @$droid -> $droid_id (android's own)"

# b. alice DMs android; android replies; alice gets the reply. Read receipts both ways.
cli_ok alice dm "$droid"
dm=$(out .chat_id)
cli_ok alice send "$dm" "Ahoy from alice $run"
alice_dm_msg=$(out .message_id)
wait_marker dm-replied
cli_ok alice wait-for "$dm" --text "Ahoy from android $run" --from "$droid" --timeout "$timeout"
pass "b. alice -> android DM shown on Android; android reply received by alice"
cli_ok alice wait-for "$dm" --msg "$alice_dm_msg" --read-by "$droid" --timeout "$timeout"
expect ".message.receipts[\"$droid\"] == \"read\"" "android's read receipt"
pass "e. read: android viewed alice's DM -> CLI JSON shows read"
cli_ok alice read "$dm"
wait_marker dm-read-tick
pass "e. read: alice read the chat -> android shows the read tick"

# c. bob creates a group with alice and android.
cli_ok bob group create "Crew $run" "$alice" "$droid"
group=$(out .chat_id)
cli_ok bob send "$group" "Welcome aboard $run"
cli_ok alice wait-for "$group" --text "Welcome aboard $run" --from "$bob" --timeout "$timeout"
wait_marker group-open
cli_ok bob members "$group"
expect '.members | length == 3' "three members"
pass "c. bob's group with alice + android seen by all three"

# d. Text both ways, images both ways.
cli_ok alice wait-for "$group" --text "Android in the group $run" --from "$droid" --timeout "$timeout"
droid_group_msg=$(out .message.id)
cli_ok bob wait-for "$group" --text "Android in the group $run" --from "$droid" --timeout "$timeout"
cli_ok alice send "$group" "Alice in the group $run"
cli_ok bob wait-for "$group" --text "Alice in the group $run" --from "$alice" --timeout "$timeout"
cli_ok bob send-image "$group" "$work/bob-photo.png" --caption "Bob photo $run"
wait_marker cli-image-shown
pass "d. group text android <-> alice/bob"
pass "d. image bob (CLI) -> shown on android"
wait_marker photo-sent
cli_ok bob wait-for "$group" --text "Android photo $run" --from "$droid" --timeout "$timeout"
expect '.message.kind == "media" and (.message.media.mime | startswith("image/"))' "android's photo is an image"
cli_ok bob download "$(out .message.id)" "$work/android-photo.png"
cmp "$photo" "$work/android-photo.png" || fail "downloaded photo differs from the test asset"
pass "d. image android (stubbed picker) -> downloaded by bob, byte-identical"

# e. Reply, reaction, edit, delete, typing.
cli_ok alice send "$group" "Alice replies $run" --reply "$droid_group_msg"
alice_reply=$(out .message_id)
wait_marker android-replied
cli_ok alice wait-for "$group" --text "Android replies $run" --from "$droid" --timeout "$timeout"
droid_reply=$(out .message.id)
expect ".message.reply_to == \"$alice_reply\"" "android's reply quotes alice's message"
pass "e. reply: quoted preview on android; reply_to in CLI JSON"

cli_ok bob react "$droid_reply" ⛵
wait_marker android-reacted
cli_ok alice wait-for "$group" --msg "$alice_reply" --reaction 👍 --from "$droid" --timeout "$timeout"
pass "e. reaction: bob -> android chip; android -> alice CLI JSON"

cli_ok alice edit "$alice_reply" "Alice edited $run"
wait_marker android-edited
cli_ok alice wait-for "$group" --msg "$droid_reply" --edited --timeout "$timeout"
expect ".message.text == \"Android edited $run\"" "android's edit"
pass "e. edit: alice -> android shows edited text; android -> CLI JSON edited"

cli_ok alice delete "$alice_reply"
wait_marker android-deleted
cli_ok alice wait-for "$group" --msg "$droid_reply" --deleted --timeout "$timeout"
pass "e. delete: alice -> android shows deleted; android -> CLI JSON deleted"

wait_marker typing-ready
cli_ok alice typing "$group"
docker exec -e GIM_PROFILE="$work/bob" "$cli" "$work/gim" --json listen --chat "$group" --timeout "$timeout" \
    >"$log_dir/bob-listen.jsonl" 2>>"$log_dir/cli-bob.log" &
listen_pid=$!
bg_pids+=("$listen_pid")
poll "bob's listener to connect" grep -q '"state":"connected"' "$log_dir/bob-listen.jsonl"
signal cli-listening
wait_marker android-typing
poll "android's typing event" \
    jq -es "any(.[]; .event == \"typing\" and .user == \"$droid\" and .active)" "$log_dir/bob-listen.jsonl"
docker exec "$cli" pkill -INT -f "gim --json listen" || true
wait "$listen_pid" || true
signal typing-seen
pass "e. typing: alice -> android indicator; android -> bob listen typing event"

# f. android renames the group in Group info; both CLIs see the name.
wait_marker renamed
renamed() {
    cli_ok "$1" chats list
    [[ $(out ".chats[] | select(.id == \"$group\") | .name") == "Crew renamed $run" ]]
}
poll "alice to see the new group name" renamed alice
poll "bob to see the new group name" renamed bob
pass "f. android renamed the group; alice and bob see the new name"

# g. bob removes alice; bob and android keep talking, alice gets nothing new.
cli_ok bob group remove "$group" "$alice"
cli_ok bob send "$group" "Bob after removal $run"
wait_marker android-after-removal
cli_ok bob wait-for "$group" --text "Android after removal $run" --from "$droid" --timeout "$timeout"
for text in "Bob after removal $run" "Android after removal $run"; do
    cli alice wait-for "$group" --text "$text" --timeout 15
    ((CLI_RC == 3)) || fail "alice: wait-for '$text' after removal exited $CLI_RC (want 3, timeout): $CLI_OUT"
done
pass "g. bob removed alice; bob <-> android still deliver; alice's wait-for times out (exit 3)"

# The test ends after its last step; it must report success.
for _ in $(seq 1 "$timeout"); do
    instrument_running || break
    sleep 1
done
instrument_running && fail "instrumentation did not finish"
grep -q '^OK (1 test)' "$log_dir/instrumentation.txt" || fail "instrumentation did not pass"
pass "android: InteropTest passed"

echo
echo "all steps passed in ${SECONDS}s:"
printf '  %s\n' "${passed[@]}"
