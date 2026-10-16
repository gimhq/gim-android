# Shared plumbing for running against a real (dedicated test) phone attached to the long-running `ai-agent-adb`
# container (scripts/run-real-device-tests.sh, GIM_DEVICE=real scripts/e2e-interop.sh). Source it after
# scripts/lib/device.sh; it defines functions and variables only.
#
# The phone is reached only through the adb server inside that container (`docker exec ai-agent-adb adb -s SERIAL`);
# never start another adb server. Two ways for the phone to reach gim-hub (GIM_REAL_NET):
#   lan      (default) the hub is a sibling container publishing -p <port>:8080 on the host; the phone, on the same
#            LAN, uses http://$GIM_REAL_LAN_HOST:<port> (default host 192.168.68.220).
#   reverse  fallback: the hub runs in ai-agent-adb's network namespace on 127.0.0.1:<port> and `adb reverse` maps the
#            same port on the phone, which uses http://127.0.0.1:<port>.
#
# Package rules: im.g.apps and im.g.apps.test stay installed. They are only ever updated in place (`install -r -t`,
# which needs no confirmation on the phone), reset with `pm clear`, and never uninstalled. Updating needs the same
# signing key as the installed packages, so real-device builds use the persistent debug keystore in
# GIM_REAL_ANDROID_HOME (see gim_real_gradle_env). A fresh install (package missing) makes HyperOS show its
# "Install via USB" confirmation; gim_real_install accepts it only under the guard in gim_real_accept_install.
#
#   gim_real_lock                 take the device lock (mkdir /tmp/.lock-device in the adb container), waiting up to
#                                 LOCK_WAIT seconds (default 900) while another run holds it
#   gim_real_unlock               release the lock if this run took it
#   gim_real_adb ARGS...          adb -s $GIM_REAL_SERIAL ARGS... inside the adb container
#   gim_real_gradle_env           docker run arguments (array GIM_REAL_GRADLE_ENV) that pin the debug keystore
#   gim_real_start_hub NAME ROOT  start the pinned gim-hub image as NAME (GIM_REAL_NET mode, free port from 18080 up),
#                                 wait for /healthz; sets GIM_REAL_PORT and GIM_REAL_HUB_URL (the phone's URL)
#   gim_real_stop_hub NAME        remove the reverse (reverse mode) and the hub container NAME
#   gim_real_install APK...       copy the APKs into the adb container (/tmp/$GIM_RUN_LABEL) and `install -r -t`
#                                 them. Exits 3 ("blocked") on a signature mismatch with the installed package or
#                                 when the phone refuses the install.
#   gim_real_reset                `pm clear im.g.apps`, remove the copied APKs
#   gim_real_screenshot FILE      save a screenshot of the phone to FILE
#   gim_real_bg NAME ARGS...      run `adb -s SERIAL ARGS...` in the adb container in the foreground of this shell
#                                 (callers background it with &), recording its container-side pid as NAME
#   gim_real_kill_bg              kill every process started with gim_real_bg (killing the local `docker exec`
#                                 client would leave it running in the container)
#
# No device setting is changed; the only screen input is the guarded install confirmation.

GIM_REAL_ADB_CONTAINER=${GIM_REAL_ADB_CONTAINER:-ai-agent-adb}
GIM_REAL_SERIAL=${GIM_REAL_SERIAL:-3ffc81dc}
GIM_REAL_NET=${GIM_REAL_NET:-lan}
GIM_REAL_LAN_HOST=${GIM_REAL_LAN_HOST:-192.168.68.220}
GIM_REAL_ANDROID_HOME=${GIM_REAL_ANDROID_HOME:-/srv/.tmp/cache/android-debug-keystore}
GIM_REAL_LOCK=/tmp/.lock-device
GIM_REAL_APP=im.g.apps
GIM_REAL_PORT=
gim_real_locked=0

gim_real_adb() {
    docker exec "$GIM_REAL_ADB_CONTAINER" adb -s "$GIM_REAL_SERIAL" "$@"
}

gim_real_lock() {
    local waited=0 wait=${LOCK_WAIT:-900}
    docker inspect "$GIM_REAL_ADB_CONTAINER" >/dev/null 2>&1 || {
        echo "error: container $GIM_REAL_ADB_CONTAINER is not running" >&2
        return 1
    }
    until docker exec "$GIM_REAL_ADB_CONTAINER" mkdir "$GIM_REAL_LOCK" 2>/dev/null; do
        if ((waited >= wait)); then
            echo "error: device lock $GIM_REAL_LOCK still held by another run after ${wait}s" >&2
            return 1
        fi
        ((waited % 60)) || echo "device lock held by another run; waiting"
        sleep 10
        waited=$((waited + 10))
    done
    gim_real_locked=1
    echo "device lock taken"
    [[ $(gim_real_adb get-state 2>/dev/null) == device ]] || {
        echo "error: device $GIM_REAL_SERIAL is not attached to $GIM_REAL_ADB_CONTAINER" >&2
        return 1
    }
}

gim_real_unlock() {
    ((gim_real_locked)) || return 0
    docker exec "$GIM_REAL_ADB_CONTAINER" rmdir "$GIM_REAL_LOCK" >/dev/null 2>&1 || true
    gim_real_locked=0
}

# AGP signs debug builds with $ANDROID_USER_HOME/debug.keystore and creates it when missing; a throwaway build
# container would create a new key every run, and the phone refuses to update a package signed with another key.
gim_real_gradle_env() {
    mkdir -p "$GIM_REAL_ANDROID_HOME"
    GIM_REAL_GRADLE_ENV=(-v "$GIM_REAL_ANDROID_HOME:/cache/android-user-home" -e ANDROID_USER_HOME=/cache/android-user-home)
}

# Ports free in ai-agent-adb's network namespace and not reversed on the phone (reverse mode).
gim_real_netns_ports() {
    local used reversed port
    used=$(docker exec "$GIM_REAL_ADB_CONTAINER" cat /proc/net/tcp /proc/net/tcp6 |
        awk 'NR > 1 && $2 ~ /:/ { split($2, a, ":"); print a[2] }')
    reversed=$(gim_real_adb reverse --list 2>/dev/null || true)
    for port in $(seq 18080 18179); do
        grep -qix "$(printf '%04X' "$port")" <<<"$used" && continue
        grep -q "tcp:$port " <<<"$reversed" && continue
        echo "$port"
    done
}

gim_real_start_hub() {
    local name=$1 root=$2 port _ out
    gim_ensure_hub_image "$root"
    # shellcheck disable=SC2054 # the commas belong to the --tmpfs options
    local run_args=(-d --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$name"
        --tmpfs /data:rw,uid=10001,gid=10001,mode=0700 -e GIM_HUB_DATA_DIR=/data)
    case $GIM_REAL_NET in
        lan)
            # The host's free ports are not visible from here; let the daemon refuse taken ones.
            for port in $(seq 18080 18179); do
                if out=$(docker run "${run_args[@]}" -p "$port:8080" -e GIM_HUB_LISTEN=0.0.0.0:8080 \
                    "$GIM_HUB_IMAGE" 2>&1); then
                    GIM_REAL_PORT=$port
                    break
                fi
                docker rm -f "$name" >/dev/null 2>&1 || true
                grep -qiE 'already allocated|address already in use' <<<"$out" || {
                    echo "error: starting gim-hub failed: $out" >&2
                    return 1
                }
            done
            GIM_REAL_HUB_URL=http://$GIM_REAL_LAN_HOST:$GIM_REAL_PORT
            ;;
        reverse)
            port=$(gim_real_netns_ports | head -n 1)
            docker run "${run_args[@]}" --network "container:$GIM_REAL_ADB_CONTAINER" \
                -e GIM_HUB_LISTEN="127.0.0.1:$port" "$GIM_HUB_IMAGE" >/dev/null
            GIM_REAL_PORT=$port
            GIM_REAL_HUB_URL=http://127.0.0.1:$port
            ;;
        *)
            echo "error: GIM_REAL_NET must be lan or reverse, got $GIM_REAL_NET" >&2
            return 1
            ;;
    esac
    [[ -n $GIM_REAL_PORT ]] || {
        echo "error: no free port in 18080-18179" >&2
        return 1
    }
    echo "gim-hub ($GIM_HUB_IMAGE, $GIM_REAL_NET) at $GIM_REAL_HUB_URL"
    for _ in $(seq 1 60); do
        if docker exec "$name" gim-hub healthcheck >/dev/null 2>&1; then
            echo "gim-hub healthy"
            [[ $GIM_REAL_NET != reverse ]] || gim_real_adb reverse "tcp:$GIM_REAL_PORT" "tcp:$GIM_REAL_PORT" >/dev/null
            return 0
        fi
        sleep 1
    done
    echo "error: gim-hub did not become healthy" >&2
    docker logs "$name" >&2 || true
    return 1
}

gim_real_stop_hub() {
    local name=$1
    if [[ $GIM_REAL_NET == reverse && -n $GIM_REAL_PORT ]]; then
        gim_real_adb reverse --remove "tcp:$GIM_REAL_PORT" >/dev/null 2>&1 || true
    fi
    docker rm -f "$name" >/dev/null 2>&1 || true
}

gim_real_focus() {
    gim_real_adb shell dumpsys window | tr -d '\r' | grep -m1 mCurrentFocus || true
}

# gim_real_accept_install PKG: while this run's install of PKG is in flight (and the device lock is held), waits up
# to 30 s for HyperOS's "Install via USB" activity, checks in a UI dump that it names the GIM app, and taps the
# accept button found by its text in that dump. Returns 0 when nothing needed accepting or it was accepted, 1 when the
# dialog did not match (the caller cancels the install).
gim_real_accept_install() {
    local pkg=$1 deadline=$((SECONDS + 30)) seen=0 xml node bounds x y
    ((gim_real_locked)) || return 1
    while ((SECONDS < deadline)); do
        if gim_real_focus | grep -q 'com.miui.securitycenter/com.miui.permcenter.install.AdbInstallActivity'; then
            seen=1
            xml=$(gim_real_adb exec-out uiautomator dump /dev/tty 2>/dev/null | tr -d '\r')
            if ! grep -qE 'text="GIM"|im\.g\.apps' <<<"$xml"; then
                echo "auto-accept: install dialog does not name GIM/$pkg; not tapping" >&2
                return 1
            fi
            # One <node .../> per line; the accept button by its text (its label may sit in a child of the button).
            node=$(sed 's/<node /\n<node /g' <<<"$xml" |
                grep -E 'text="(继续安装|Continue installation|Install|安装)"' | head -n 1)
            if [[ -z $node ]] || ! grep -q 'enabled="true"' <<<"$node"; then
                sleep 1 # countdown or not yet enabled
                continue
            fi
            bounds=$(sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p' <<<"$node")
            read -r x y < <(awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }' <<<"$bounds")
            gim_real_focus | grep -q 'AdbInstallActivity' || continue
            gim_real_adb shell input tap "$x" "$y"
            echo "auto-accept: $(date -Is) package $pkg button '$(sed -nE 's/.*text="([^"]*)".*/\1/p' <<<"$node")' at $x,$y"
            return 0
        fi
        sleep 1
    done
    if ((seen)); then
        echo "auto-accept: install dialog for $pkg seen but its accept button never became usable" >&2
        return 1
    fi
    return 0
}

gim_real_install() {
    local dir=/tmp/$GIM_RUN_LABEL apk dest out pkg fresh install_pid install_out
    docker exec "$GIM_REAL_ADB_CONTAINER" mkdir -p "$dir"
    for apk in "$@"; do
        dest=$dir/$(basename "$apk")
        docker cp "$apk" "$GIM_REAL_ADB_CONTAINER:$dest" >/dev/null
        pkg=$GIM_REAL_APP
        [[ $apk != *androidTest* ]] || pkg=$GIM_REAL_APP.test
        fresh=0
        gim_real_adb shell pm path "$pkg" >/dev/null 2>&1 || fresh=1
        if ((fresh)); then
            echo "$pkg is not installed: the phone asks for confirmation"
            install_out=$(mktemp)
            gim_real_adb install -r -t "$dest" >"$install_out" 2>&1 &
            install_pid=$!
            if ! gim_real_accept_install "$pkg"; then
                # Ends the install session; HyperOS drops its dialog with it.
                docker exec "$GIM_REAL_ADB_CONTAINER" pkill -f "install -r -t $dest" >/dev/null 2>&1 || true
                kill "$install_pid" 2>/dev/null || true
                rm -f "$install_out"
                echo "blocked: install confirmation for $pkg did not match; install cancelled" >&2
                exit 3
            fi
            wait "$install_pid" || true
            out=$(cat "$install_out")
            rm -f "$install_out"
        else
            out=$(gim_real_adb install -r -t "$dest" 2>&1) || true
        fi
        if grep -qE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_.*SIGNATURE' <<<"$out"; then
            echo "blocked: $pkg on the phone is signed with another key; not uninstalling ($out)" >&2
            exit 3
        fi
        if grep -q INSTALL_FAILED_USER_RESTRICTED <<<"$out"; then
            echo "blocked: device install needs a human tap ($apk: $out)" >&2
            exit 3
        fi
        grep -q '^Success' <<<"$out" || {
            echo "error: installing $apk failed: $out" >&2
            return 1
        }
        echo "installed $(basename "$apk") ($pkg)"
    done
}

gim_real_reset() {
    gim_real_adb shell pm clear "$GIM_REAL_APP" >/dev/null 2>&1 && echo "cleared $GIM_REAL_APP"
    docker exec "$GIM_REAL_ADB_CONTAINER" rm -rf "/tmp/$GIM_RUN_LABEL" >/dev/null 2>&1 || true
}

gim_real_bg() {
    local name=$1
    shift
    docker exec "$GIM_REAL_ADB_CONTAINER" mkdir -p "/tmp/$GIM_RUN_LABEL"
    # shellcheck disable=SC2016 # expanded by the container's sh
    docker exec "$GIM_REAL_ADB_CONTAINER" sh -c 'echo $$ >"$0"; exec "$@"' "/tmp/$GIM_RUN_LABEL/$name.pid" \
        adb -s "$GIM_REAL_SERIAL" "$@"
}

gim_real_kill_bg() {
    # shellcheck disable=SC2016 # expanded by the container's sh
    docker exec "$GIM_REAL_ADB_CONTAINER" sh -c 'for f in "$0"/*.pid; do [ -f "$f" ] && kill "$(cat "$f")"; rm -f "$f"; done' \
        "/tmp/$GIM_RUN_LABEL" >/dev/null 2>&1 || true
}

gim_real_screenshot() {
    docker exec "$GIM_REAL_ADB_CONTAINER" adb -s "$GIM_REAL_SERIAL" exec-out screencap -p >"$1" 2>/dev/null || true
}
