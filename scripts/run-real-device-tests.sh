#!/usr/bin/env bash
# Runs the app's instrumented tests (im.g.apps.test) on a real test phone attached to the long-running `ai-agent-adb`
# container, against gim-hub at the commit pinned in e2e/GIM_HUB_VERSION. See scripts/lib/real-device.sh for the
# package and networking rules.
#
# Usage: scripts/run-real-device-tests.sh [AM_INSTRUMENT_ARGS...]
#   AM_INSTRUMENT_ARGS  extra `am instrument` arguments for the app suite, e.g. -e class im.g.apps.e2e.LinkDeviceE2eTest
#
# Environment:
#   GIM_REAL_SERIAL     adb serial of the phone, default 3ffc81dc
#   GIM_REAL_ADB_CONTAINER  container owning the adb server and the USB device, default ai-agent-adb
#   GIM_REAL_NET        lan (default: hub published on the host, phone uses http://GIM_REAL_LAN_HOST:<port>) or
#                       reverse (hub in ai-agent-adb's network namespace + adb reverse, phone uses 127.0.0.1)
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
#      the suite with `am instrument -w -r` (gimServerUrl, timeoutScale, realCamera=true for CameraScannerE2eTest).
#   4. Always: `pm clear im.g.apps` (packages stay installed), removes the reverse, the hub container and the lock.
#
# Outputs (LOG_DIR): summary.txt (PASS/FAIL/SKIP per test with durations), instrumentation.txt (raw `-r` output),
# logcat.txt (im.g.apps and test package UIDs only), hub.log, gradle.log, screenshots/ (taken by tests at the
# failing step, plus one by this script per failed test). Exit code: 0 when every test passed, 1 otherwise, 3 when the
# phone cannot be updated unattended (signature mismatch, refused or unverifiable install confirmation).
set -euo pipefail

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
    sed -n '2,38p' "$0"
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

mkdir -p "$log_dir/screenshots"
exec > >(tee -a "$log_dir/script.log") 2>&1
echo "run log: $log_dir"

cleanup() {
    local status=$?
    trap - EXIT INT TERM
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

# instrument NAME TARGET ARGS...: runs `am instrument -w -r ARGS TARGET`, appends the raw output to
# instrumentation.txt and one line per test to summary.txt; returns 1 when a test failed or the run broke.
instrument() {
    local name=$1 target=$2 status=0
    shift 2
    echo "== $name: am instrument -w -r $* $target" | tee -a "$log_dir/instrumentation.txt"
    # Like gim_real_bg, but bounded: the pid file lets cleanup stop the container-side adb after a timeout.
    # shellcheck disable=SC2016 # expanded by the container's sh
    timeout "${INSTRUMENT_TIMEOUT:-1800}" docker exec "$GIM_REAL_ADB_CONTAINER" sh -c 'echo $$ >"$0"; exec "$@"' \
        "/tmp/$GIM_RUN_LABEL/instrument.pid" adb -s "$GIM_REAL_SERIAL" shell am instrument -w -r "$@" "$target" </dev/null |
        tr -d '\r' | tee -a "$log_dir/instrumentation.txt" | summarize || status=$?
    return "$status"
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
                case $code in
                    0) result=PASS ;;
                    -3 | -4) result=SKIP ;;
                    *)
                        result=FAIL
                        failed=1
                        gim_real_screenshot "$log_dir/screenshots/script-${class##*.}-$test.png"
                        ;;
                esac
                secs=$((SECONDS - started))
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
        echo "FAIL instrumentation did not complete (tests reported: $tests)" | tee -a "$log_dir/summary.txt"
        return 1
    fi
    return "$failed"
}

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
gim_real_adb shell getprop ro.product.model | tr -d '\r' | sed 's/^/device: /'

gim_real_install "$app_apk" "$test_apk"
gim_real_adb shell pm clear "$app" >/dev/null
gim_real_adb shell pm grant "$app" android.permission.CAMERA

uids=$(gim_real_adb shell pm list packages -U "$app" | tr -d '\r' | sed -nE 's/.* uid:([0-9]+).*/\1/p' | sort -u | paste -sd,)
since=$(gim_real_adb shell date "'+%m-%d %H:%M:%S.000'" | tr -d '\r')
gim_real_bg logcat logcat -v threadtime -T "$since" --uid="$uids" >"$log_dir/logcat.txt" 2>&1 &

: >"$log_dir/summary.txt"
echo "setup done after ${SECONDS}s; hub $url"
status=0
instrument app "$test_pkg/$runner" -e gimServerUrl "$url" -e timeoutScale "${TIMEOUT_SCALE:-1}" -e realCamera true "$@" ||
    status=1

echo
echo "summary ($log_dir/summary.txt):"
cat "$log_dir/summary.txt"
if ((status)); then
    echo "real-device tests FAILED; gim-hub log tail:"
    docker logs --tail 30 "$hub" 2>&1 || true
else
    echo "real-device tests passed"
fi
exit "$status"
