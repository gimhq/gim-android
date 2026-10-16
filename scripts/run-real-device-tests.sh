#!/usr/bin/env bash
# Runs the app's instrumented tests (im.g.apps.test) on a real test phone attached to the long-running `gim-adb-host`
# container, against gim-hub at the commit pinned in e2e/GIM_HUB_VERSION. See scripts/lib/real-device.sh for the
# package and networking rules.
#
# Usage: scripts/run-real-device-tests.sh [AM_INSTRUMENT_ARGS...]
#   AM_INSTRUMENT_ARGS  extra `am instrument` arguments for the app suite, e.g. -e class im.g.apps.e2e.LinkDeviceE2eTest
#
# Environment:
#   GIM_REAL_SERIAL     wireless adb serial (host:port) of the phone; default: the online wireless device of model
#                       GIM_REAL_MODEL (USB serials are never used)
#   GIM_REAL_MODEL      model in `adb devices -l` used to find the phone, default 2607DRA18G
#   GIM_REAL_ADB_CONTAINER  container owning the adb server and the device connection, default gim-adb-host
#   GIM_REAL_NET        lan (default: hub published on the host, phone uses http://GIM_REAL_LAN_HOST:<port>) or
#                       reverse (hub in gim-adb-host's network namespace + adb reverse, phone uses 127.0.0.1)
#   GIM_REAL_LAN_HOST   the host's LAN address as seen from the phone, default 192.168.68.220
#   GIM_REAL_ANDROID_HOME  persistent ANDROID_USER_HOME holding the debug keystore the installed packages are signed
#                       with, default /srv/.tmp/cache/android-debug-keystore
#   TIMEOUT_SCALE       instrumentation argument timeoutScale (multiplies the tests' per-step waits), default 1
#   LOCK_WAIT           seconds to wait for another run's device lock, default 900
#   INSTRUMENT_TIMEOUT  seconds per `am instrument` run, default 1800
#   LOG_DIR             run log directory, default /tmp/gim-realdevice-<timestamp>
#   GRADLE_CACHE        Gradle user home on the host, default /srv/.tmp/cache/gradle
#   GIM_HUB_SRC         gim-hub checkout the image is exported from, default /srv/gimhq/im-client/hub
#
# Flow:
#   1. Builds the debug and androidTest APKs in ghcr.io/cirruslabs/android-sdk:35 with the pinned debug keystore.
#   2. Takes the device lock, starts gim-hub (ai-agent/gim-hub:<sha12>) on a free port from 18080 (GIM_REAL_NET).
#   3. Updates im.g.apps and im.g.apps.test in place (`install -r -t`), clears im.g.apps, grants it CAMERA and runs
#      one `am instrument -w -r` per test class (gimServerUrl, timeoutScale; listed with the runner's `-e log true`
#      dry run), CameraScannerE2eTest (realCamera=true) last, so a class that loses the foreground or crashes the
#      process cannot hide the other results. Before each run the phone is woken (`input keyevent KEYCODE_WAKEUP` if
#      the screen is off, `wm dismiss-keyguard` if the lock screen shows; no setting changes).
#   4. Always: `pm clear im.g.apps` (packages stay installed), removes the reverse, the hub container and the lock.
#
# Outputs (LOG_DIR): summary.txt (PASS/FAIL/SKIP per test with durations), instrumentation.txt (raw `-r` output),
# logcat.txt (im.g.apps and test package UIDs only), hub.log, gradle.log, screenshots/ (taken by tests at the
# failing step, plus one by this script per failed test). Exit codes: 0 every test passed; 1 a test failed (or the
# build / setup failed); 3 the phone cannot be updated unattended (signature mismatch, refused or unverifiable install
# confirmation); 4 device not attached at the start (or only over USB: wireless adb is required); 5 INTERRUPTED
# (infra): the device went away or offline during the run, or a class failed while someone touched the phone
# ("INTERRUPTED (external input)" with the touch times; a class that passes despite touches keeps PASS with a WARN
# line); 1 outranks this 5 when another class genuinely failed. summary.txt says which. 6 NOT READY
# (infra): the screen stays off or locked after the wake-up (a secure lock screen needs a person), before a run or when
# a run failed.
set -euo pipefail

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
    sed -n '2,45p' "$0"
    exit 0
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
run=$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
GIM_RUN_LABEL=gim-realdevice-$run
# shellcheck source=lib/device.sh
source "$repo_root/scripts/lib/device.sh"
# shellcheck source=lib/real-device.sh
source "$repo_root/scripts/lib/real-device.sh"
gim_check_host_paths "$repo_root"

gradle_cache=${GRADLE_CACHE:-/srv/.tmp/cache/gradle}
log_dir=${LOG_DIR:-/tmp/gim-realdevice-$(date +%Y%m%d-%H%M%S)}
hub=ai-agent-$GIM_RUN_LABEL-hub
app_apk=$repo_root/app/build/outputs/apk/debug/app-debug.apk
test_apk=$repo_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
app=$GIM_REAL_APP
test_pkg=$app.test
runner=androidx.test.runner.AndroidJUnitRunner
camera_test=im.g.apps.e2e.CameraScannerE2eTest

mkdir -p "$log_dir/screenshots"
exec > >(tee -a "$log_dir/script.log") 2>&1
echo "run log: $log_dir"

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    # A step that broke because the transport went away is an infrastructure interruption, not a test result.
    if ((status != 0 && status != GIM_REAL_EXIT_NOT_ATTACHED && status != GIM_REAL_EXIT_INTERRUPTED &&
        status != GIM_REAL_EXIT_NOT_READY)) &&
        [[ -n $GIM_REAL_SERIAL ]] && ((gim_real_locked)) && ! gim_real_attached; then
        interrupted "during setup"
        status=$GIM_REAL_EXIT_INTERRUPTED
    fi
    if ((gim_real_locked)); then
        gim_real_kill_bg
        pull_test_screenshots
        gim_real_reset
        docker logs "$hub" >"$log_dir/hub.log" 2>&1 || true
        gim_real_stop_hub "$hub"
        gim_real_unlock
    else
        docker rm -f "$hub" >/dev/null 2>&1 || true
    fi
    echo "finished (exit $status) after ${SECONDS}s; run log: $log_dir"
    exit "$status"
}
trap cleanup EXIT INT TERM

# Copies the screenshots tests saved at their failing step (FailureScreenshot.kt) out of the app's cache.
pull_test_screenshots() {
    local name
    for name in $(gim_real_adb shell run-as "$app" ls cache/test-screenshots 2>/dev/null | tr -d '\r'); do
        [[ $name == *.png ]] || continue
        gim_real_adb exec-out run-as "$app" cat "cache/test-screenshots/$name" >"$log_dir/screenshots/$name" || true
    done
}

not_ready() {
    local why
    why=$(gim_real_not_ready | paste -sd';')
    [[ -n $why ]] || return 1
    echo "NOT READY (infra): $why $1; wake and unlock the phone" | tee -a "$log_dir/summary.txt"
}

# test_seconds METHOD CLASS: the test's duration from the device's TestRunner started/finished lines in logcat.txt.
test_seconds() {
    awk -v t="$1($2)" '
        function ms(clock) { split(clock, a, /[:.]/); return ((a[1] * 60 + a[2]) * 60 + a[3]) * 1000 + a[4] }
        $0 ~ /TestRunner: started: / && index($0, t) { s = ms($2) }
        $0 ~ /TestRunner: finished: / && index($0, t) && s { d = ms($2) - s; if (d < 0) d += 86400000; f = d }
        END { if (f != "") printf "%.1f", f / 1000 }' "$log_dir/logcat.txt"
}

# external_touches CLASS: touches on the app's windows while CLASS ran (its first TestRunner "started" to its last
# "finished" in logcat.txt), one "MM-DD HH:MM:SS.mmm" per line. Compose test clicks are dispatched inside the app and
# never log MIUIInput; real touches (a person, another `adb shell input`) do. A touch within 2 s after the tests'
# own "SystemDialogs: dismissing" line is attributed to that UiAutomator dismissal and printed with "(dismissal)".
external_touches() {
    awk -v cls="($1)" '
        function ms(clock) { split(clock, a, /[:.]/); return ((a[1] * 60 + a[2]) * 60 + a[3]) * 1000 + a[4] }
        / TestRunner: started: / && index($0, cls) && start == "" { start = $1 " " $2 }
        / TestRunner: finished: / && index($0, cls) { end = $1 " " $2 }
        / SystemDialogs: dismissing / { dismissed[++n] = $1 " " $2 }
        /MIUIInput: \[MotionEvent\]/ && /windowName .im\.g\.apps/ && /action=ACTION_DOWN/ { touch[++m] = $1 " " $2 }
        END {
            if (start == "") exit
            for (i = 1; i <= m; i++) {
                if (touch[i] < start || (end != "" && touch[i] > end)) continue
                note = ""
                for (j = 1; j <= n; j++) {
                    d = ms(substr(touch[i], 7)) - ms(substr(dismissed[j], 7))
                    if (substr(touch[i], 1, 5) == substr(dismissed[j], 1, 5) && d >= 0 && d <= 2000) note = " (dismissal)"
                }
                print touch[i] note
            }
        }' "$log_dir/logcat.txt"
}

# check_external_input CLASS RESULT: prints the class result after looking for external touches. A failed class
# with external touches is "INTERRUPTED (external input)" in summary.txt (returns 5), a passed one keeps PASS with a
# WARN line; otherwise RESULT is returned unchanged.
check_external_input() {
    local class=$1 result=$2 touches
    touches=$(external_touches "$class" | grep -v '(dismissal)' | paste -sd',' || true)
    [[ -n $touches ]] || return "$result"
    if ((result == 1)); then
        awk -v c="$class#" -v t="$touches" '
            index($0, "FAIL " c) == 1 { sub(/^FAIL /, "INTERRUPTED (external input) "); $0 = $0 " touches at " t }
            { print }' "$log_dir/summary.txt" >"$log_dir/summary.tmp" && mv "$log_dir/summary.tmp" "$log_dir/summary.txt"
        echo "INTERRUPTED (external input): $class failed with touches on the phone at $touches"
        return "$GIM_REAL_EXIT_INTERRUPTED"
    fi
    echo "WARN external input during $class at $touches (result kept)" | tee -a "$log_dir/summary.txt"
    return "$result"
}

interrupted() {
    echo "INTERRUPTED (infra): device $GIM_REAL_SERIAL not attached/online $1" | tee -a "$log_dir/summary.txt"
}

# instrument NAME TARGET ARGS...: runs `am instrument -w -r ARGS TARGET`, appends the raw output to
# instrumentation.txt and one line per test to summary.txt; returns 1 when a test failed or the run broke, 5 when
# the device went away (before or during the run).
instrument() {
    local name=$1 target=$2 status=0
    shift 2
    gim_real_attached || {
        interrupted "before the $name run"
        return "$GIM_REAL_EXIT_INTERRUPTED"
    }
    gim_real_wake
    if not_ready "before the $name run"; then
        return "$GIM_REAL_EXIT_NOT_READY"
    fi
    echo "== $name: am instrument -w -r $* $target" | tee -a "$log_dir/instrumentation.txt"
    # Like gim_real_bg, but bounded: the pid file lets cleanup stop the container-side adb after a timeout.
    # shellcheck disable=SC2016 # expanded by the container's sh
    timeout "${INSTRUMENT_TIMEOUT:-1800}" docker exec "$GIM_REAL_ADB_CONTAINER" sh -c 'echo $$ >"$0"; exec "$@"' \
        "/tmp/$GIM_RUN_LABEL/instrument.pid" adb -s "$GIM_REAL_SERIAL" shell am instrument -w -r "$@" "$target" </dev/null |
        tr -d '\r' | tee -a "$log_dir/instrumentation.txt" | summarize || status=$?
    ((status)) || return 0
    if ! gim_real_attached; then
        interrupted "during the $name run (results above are partial)"
        return "$GIM_REAL_EXIT_INTERRUPTED"
    fi
    if ((status == 2)); then
        local why
        why=$(tac "$log_dir/instrumentation.txt" | sed -n '/^== /q; s/^INSTRUMENTATION_RESULT: shortMsg=//p' | head -n 1)
        echo "FAIL $name: instrumentation did not complete${why:+ ($why see logcat.txt: Fatal signal / AndroidRuntime)}" |
            tee -a "$log_dir/summary.txt"
    fi
    if not_ready "after the $name run (its failures may come from that)"; then
        return "$GIM_REAL_EXIT_NOT_READY"
    fi
    return 1
}

# Reads `am instrument -r` output, prints and records each test result as it arrives.
summarize() {
    local line class='' test='' started=0 code result failed=0 tests=0 finished=0 secs
    while IFS= read -r line; do
        case $line in
            "INSTRUMENTATION_STATUS: class="*) class=${line#*=} ;;
            "INSTRUMENTATION_STATUS: test="*) test=${line#*=} ;;
            "INSTRUMENTATION_STATUS_CODE: "*)
                code=${line#*: }
                if [[ $code == 1 ]]; then
                    started=$SECONDS
                    echo "RUN  $class#$test"
                    continue
                fi
                # The output can arrive late (e.g. while a screenshot is taken); prefer the device's own timing.
                sleep 1
                secs=$(test_seconds "$test" "$class")
                [[ -n $secs ]] || secs="~$((SECONDS - started))"
                case $code in
                    0) result=PASS ;;
                    -3 | -4) result=SKIP ;;
                    *)
                        result=FAIL
                        failed=1
                        gim_real_screenshot "$log_dir/screenshots/script-${class##*.}-$test.png"
                        ;;
                esac
                tests=$((tests + 1))
                printf '%s %s#%s (%ss)\n' "$result" "$class" "$test" "$secs" | tee -a "$log_dir/summary.txt"
                ;;
            "INSTRUMENTATION_CODE: "*) finished=1 ;;
            *"Process crashed"* | "INSTRUMENTATION_FAILED"* | *"INSTRUMENTATION_ABORTED"*)
                echo "$line"
                failed=1
                ;;
        esac
    done
    if ((!finished || !tests)); then
        echo "instrumentation did not complete (tests reported: $tests)"
        return 2
    fi
    return "$failed"
}

gim_real_resolve_serial

echo "building APKs"
mkdir -p "$gradle_cache"
gim_real_gradle_env
docker run --rm --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "ai-agent-$GIM_RUN_LABEL-gradle" \
    -v "$repo_root:$repo_root" -w "$repo_root" \
    -v "$gradle_cache:/cache/gradle" -e GRADLE_USER_HOME=/cache/gradle "${GIM_REAL_GRADLE_ENV[@]}" \
    ghcr.io/cirruslabs/android-sdk:35 ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest \
    >"$log_dir/gradle.log" 2>&1 || {
    tail -n 40 "$log_dir/gradle.log"
    echo "error: gradle build failed" >&2
    exit 1
}

gim_real_lock
gim_real_start_hub "$hub" "$repo_root"
url=$GIM_REAL_HUB_URL

gim_real_install "$app_apk" "$test_apk"
gim_real_adb shell pm clear "$app" >/dev/null
gim_real_adb shell pm grant "$app" android.permission.CAMERA

uids=$(gim_real_adb shell pm list packages -U "$app" | tr -d '\r' | sed -nE 's/.* uid:([0-9]+).*/\1/p' | sort -u | paste -sd,)
since=$(gim_real_adb shell date "'+%m-%d %H:%M:%S.000'" | tr -d '\r')
gim_real_bg logcat logcat -v threadtime -T "$since" --uid="$uids" >"$log_dir/logcat.txt" 2>&1 &

: >"$log_dir/summary.txt"
echo "setup done after ${SECONDS}s; hub $url"
# One `am instrument` per test class (the runner's dry run, `-e log true`, lists them), CameraScannerE2eTest last: a
# class that loses the foreground or crashes the process cannot take the following classes with it, and the phone is
# woken before each.
classes=$(gim_real_adb shell am instrument -w -r -e log true -e notClass "$camera_test" "$@" "$test_pkg/$runner" |
    tr -d '\r' | sed -n 's/^INSTRUMENTATION_STATUS: class=//p' | awk '!seen[$0]++')
[[ -n $classes ]] || {
    echo "error: no test classes listed by $test_pkg" >&2
    exit 1
}
echo "test classes: $(paste -sd' ' <<<"$classes") + ${camera_test##*.}"
status=0
external=0
for class in $classes "$camera_test"; do
    result=0
    if [[ $class == "$camera_test" ]]; then
        instrument "${class##*.}" "$test_pkg/$runner" -e gimServerUrl "$url" -e timeoutScale "${TIMEOUT_SCALE:-1}" \
            -e realCamera true -e class "$class" || result=$?
    else
        instrument "${class##*.}" "$test_pkg/$runner" -e gimServerUrl "$url" -e timeoutScale "${TIMEOUT_SCALE:-1}" \
            -e class "$class" || result=$?
    fi
    if ((result == GIM_REAL_EXIT_INTERRUPTED || result == GIM_REAL_EXIT_NOT_READY)); then
        status=$result
        break
    fi
    check_external_input "$class" "$result" || result=$?
    if ((result == GIM_REAL_EXIT_INTERRUPTED)); then
        external=1
    elif ((result)); then
        status=1
    fi
done
# A genuine test failure outranks a class interrupted by external input.
((status || !external)) || status=$GIM_REAL_EXIT_INTERRUPTED

echo
echo "summary ($log_dir/summary.txt):"
cat "$log_dir/summary.txt"
if ((status == GIM_REAL_EXIT_INTERRUPTED)); then
    echo "real-device run INTERRUPTED (infra): see summary.txt (device went away, or external input on the phone)"
elif ((status == GIM_REAL_EXIT_NOT_READY)); then
    echo "real-device run NOT READY (infra): the phone's screen is off or locked"
elif ((status)); then
    echo "real-device tests FAILED; gim-hub log tail:"
    docker logs --tail 30 "$hub" 2>&1 || true
else
    echo "real-device tests passed"
fi
exit "$status"
