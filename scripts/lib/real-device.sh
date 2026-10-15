# Shared plumbing for running against a real phone attached to the long-running `ai-agent-adb` container
# (scripts/run-real-device-tests.sh, GIM_DEVICE=real scripts/e2e-interop.sh). Source it after scripts/lib/device.sh;
# it defines functions and variables only.
#
# The phone is reached only through the adb server inside that container (`docker exec ai-agent-adb adb -s SERIAL`);
# never start another adb server. The phone cannot reach this host over TCP, so gim-hub runs in the container's
# network namespace on 127.0.0.1:<port> and `adb reverse` maps the same port on the phone.
#
#   gim_real_lock                 take the device lock (mkdir /tmp/.lock-device in the adb container), waiting up to
#                                 LOCK_WAIT seconds (default 900) while another run holds it
#   gim_real_unlock               release the lock if this run took it
#   gim_real_adb ARGS...          adb -s $GIM_REAL_SERIAL ARGS... inside the adb container
#   gim_real_free_port            print a port from 18080 up that is free in the adb container's network namespace
#                                 and not reversed on the phone
#   gim_real_start_hub NAME PORT ROOT
#                                 run the pinned gim-hub image as NAME in the adb container's network namespace,
#                                 listening on 127.0.0.1:PORT, wait for /healthz, then `adb reverse tcp:PORT`
#   gim_real_stop_hub NAME PORT   remove the reverse and the hub container NAME
#   gim_real_install APK...       copy the APKs into the adb container (/tmp/$GIM_RUN_LABEL) and install them; a
#                                 previous install signed with another debug key is uninstalled first. Exits 3 with
#                                 "blocked" when the phone refuses the install (INSTALL_FAILED_USER_RESTRICTED).
#   gim_real_uninstall PKG...     uninstall the packages that are installed, remove the copied APKs
#   gim_real_screenshot FILE      save a screenshot of the phone to FILE
#   gim_real_bg NAME ARGS...      run `adb -s SERIAL ARGS...` in the adb container in the foreground of this shell
#                                 (callers background it with &), recording its container-side pid as NAME
#   gim_real_kill_bg              kill every process started with gim_real_bg (killing the local `docker exec`
#                                 client would leave it running in the container)
#
# Only im.g.apps and its test packages are touched; no device setting is changed.

GIM_REAL_ADB_CONTAINER=${GIM_REAL_ADB_CONTAINER:-ai-agent-adb}
GIM_REAL_SERIAL=${GIM_REAL_SERIAL:-3ffc81dc}
GIM_REAL_LOCK=/tmp/.lock-device
GIM_REAL_APP=im.g.apps
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

gim_real_free_port() {
    local used reversed port
    # Local ports of every socket in the namespace (hex), and the phone's reversed ports.
    used=$(docker exec "$GIM_REAL_ADB_CONTAINER" cat /proc/net/tcp /proc/net/tcp6 |
        awk 'NR > 1 && $2 ~ /:/ { split($2, a, ":"); print a[2] }')
    reversed=$(gim_real_adb reverse --list 2>/dev/null || true)
    for port in $(seq 18080 18179); do
        grep -qix "$(printf '%04X' "$port")" <<<"$used" && continue
        grep -q "tcp:$port " <<<"$reversed" && continue
        echo "$port"
        return 0
    done
    echo "error: no free port in 18080-18179" >&2
    return 1
}

gim_real_start_hub() {
    local name=$1 port=$2 root=$3 _
    gim_ensure_hub_image "$root"
    echo "starting gim-hub ($GIM_HUB_IMAGE) in $GIM_REAL_ADB_CONTAINER's network on 127.0.0.1:$port"
    docker run -d --label ai-agent=true --label "ai-agent-run=$GIM_RUN_LABEL" --name "$name" \
        --network "container:$GIM_REAL_ADB_CONTAINER" \
        --tmpfs /data:rw,uid=10001,gid=10001,mode=0700 \
        -e GIM_HUB_LISTEN="127.0.0.1:$port" -e GIM_HUB_DATA_DIR=/data \
        "$GIM_HUB_IMAGE" >/dev/null
    for _ in $(seq 1 60); do
        if docker exec "$name" gim-hub healthcheck >/dev/null 2>&1; then
            echo "gim-hub healthy"
            gim_real_adb reverse "tcp:$port" "tcp:$port" >/dev/null
            return 0
        fi
        sleep 1
    done
    echo "error: gim-hub did not become healthy" >&2
    docker logs "$name" >&2 || true
    return 1
}

gim_real_stop_hub() {
    local name=$1 port=$2
    [[ -z $port ]] || gim_real_adb reverse --remove "tcp:$port" >/dev/null 2>&1 || true
    docker rm -f "$name" >/dev/null 2>&1 || true
}

gim_real_install() {
    local dir=/tmp/$GIM_RUN_LABEL apk dest out
    docker exec "$GIM_REAL_ADB_CONTAINER" mkdir -p "$dir"
    for apk in "$@"; do
        dest=$dir/$(basename "$apk")
        docker cp "$apk" "$GIM_REAL_ADB_CONTAINER:$dest" >/dev/null
        out=$(gim_real_adb install -r -t "$dest" 2>&1) || true
        if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
            # Debug builds are signed with a per-build key; replace an install signed with another one.
            local pkg
            pkg=$(sed -nE 's/.*Existing package ([^ ]+) signatures.*/\1/p' <<<"$out")
            [[ $pkg == "$GIM_REAL_APP" || $pkg == "$GIM_REAL_APP".* ]] || {
                echo "error: $apk: $out" >&2
                return 1
            }
            echo "uninstalling $pkg (signed with another key)"
            gim_real_adb uninstall "$pkg" >/dev/null
            out=$(gim_real_adb install -r -t "$dest" 2>&1) || true
        fi
        if grep -q INSTALL_FAILED_USER_RESTRICTED <<<"$out"; then
            echo "blocked: device install needs a human tap ($apk: $out)" >&2
            exit 3
        fi
        grep -q '^Success' <<<"$out" || {
            echo "error: installing $apk failed: $out" >&2
            return 1
        }
        echo "installed $(basename "$apk")"
    done
}

gim_real_uninstall() {
    local pkg installed
    installed=$(gim_real_adb shell pm list packages "$GIM_REAL_APP" 2>/dev/null | tr -d '\r')
    for pkg in "$@"; do
        grep -qx "package:$pkg" <<<"$installed" || continue
        gim_real_adb uninstall "$pkg" >/dev/null 2>&1 && echo "uninstalled $pkg"
    done
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
