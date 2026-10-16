# gim-android

Android client of the gim end-to-end encrypted IM, written in Kotlin with Jetpack Compose. Protocol,
crypto and storage live in gim-core (Rust), consumed through UniFFI-generated Kotlin bindings over JNA;
the native libraries and bindings are vendored into `:core:bridge`.

## Modules

| Module                | Role                                                             |
| --------------------- | ---------------------------------------------------------------- |
| `:app`                | Application entry point, navigation, dependency wiring           |
| `:core:bridge`        | gim-core UniFFI bindings and vendored `libgim_core.so`           |
| `:core:designsystem`  | Shared Compose theme and components                              |
| `:feature:onboarding` | Onboarding (hub URL, new identity, recovery code, handle)        |
| `:feature:chatlist`   | Conversation list                                                |
| `:feature:chat`       | Conversation screen                                              |
| `:feature:contacts`   | Contacts and groups                                              |

Shared Gradle configuration (SDK levels, Kotlin/JVM 17, test runner) lives in the `build-logic`
included build. compileSdk/targetSdk 35, minSdk 26. The APK ships `arm64-v8a` and `x86_64` only.

## Build and checks

```bash
./gradlew check assembleDebug assembleRelease
```

`check` runs lint and JVM unit tests. CI (`.github/workflows/ci.yml`, job `check`) runs
`./gradlew lint testDebugUnitTest assembleDebug assembleRelease --no-daemon`.

JVM unit tests run on the **debug variant only**. Release-variant unit tests are disabled in
build-logic (`disableReleaseUnitTests()` in
`build-logic/convention/src/main/kotlin/im/g/apps/buildlogic/KotlinAndroid.kt`) because:

- Robolectric Compose tests need the debug-only `ui-test-manifest`;
- demo mode is debug-only;
- JNA's host dispatch library is not available to release unit tests.

This lets `check` run together with `assembleRelease`.

Release builds are minified and signed with the debug key; there are no release keys in M2.

## Configuration

The onboarding server field is prefilled from `BuildConfig.DEFAULT_HUB_URL`, resolved at build time from:

1. Gradle property `gim.defaultHubUrl` (`gradle.properties` or `-Pgim.defaultHubUrl=...`), if not blank;
2. otherwise the environment variable `GIM_DEFAULT_HUB_URL`;
3. otherwise empty (the field starts blank).

```bash
./gradlew assembleDebug -Pgim.defaultHubUrl=http://10.0.2.2:8080
```

`10.0.2.2` is the emulator's alias for the host loopback.

## Onboarding

New accounts follow gim-core's identity model: Welcome (hub address) -> **Create new account** -> display name
(`createIdentity`) -> the recovery code, shown once (copy button; "I have saved my recovery code" must be ticked)
-> optional handle (`claimHandle`; a taken handle is reported on the field, **Skip for now** finishes without
one). **Link to existing device** and **Restore with recovery code** are shown disabled until their flows land.
The app also starts at onboarding when gim-core reports `localStateReset()` (the local database was discarded).

## Device tests

Instrumented tests (`core/bridge/src/androidTest`, `app/src/androidTest`) run against a real gim-hub. The hub URL
comes from the `gimServerUrl` instrumentation argument, default `http://10.0.2.2:8080`:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.gimServerUrl=http://10.0.2.2:8080
```

- **Locally:** `scripts/run-device-tests.sh [GRADLE_ARGS...]` builds (once per commit) and starts gim-hub
  (image `ai-agent/gim-hub:<sha12>`, see below) and an API 35 x86_64 emulator in sibling Docker containers
  (requires `/dev/kvm` on the host), then runs `connectedDebugAndroidTest`. Extra args are passed to Gradle, e.g.
  `scripts/run-device-tests.sh :core:bridge:connectedDebugAndroidTest`. `REBUILD_IMAGE=1` rebuilds
  the emulator image; `GRADLE_CACHE` sets the host Gradle user home (default
  `/srv/.tmp/cache/gradle`). The worktree must be on a host-visible path. See `--help`.
  `CI_LIKE=1` reproduces a small CI runner: the emulator's default 320x640 device, 2 cores, 3 GB RAM,
  boot from a freshly saved snapshot and doubled test timeouts (each knob can be overridden, e.g.
  `DEVICE_PROFILE`, `CORES`, `GPU`). On failure the full device logcat is written to
  `.tmp/device-tests/`.
- **CI:** job `device-tests` in `.github/workflows/ci.yml` checks out `gimhq/gim-hub` at the pinned commit
  (deploy key secret `GIM_HUB_DEPLOY_KEY`), builds it with its Dockerfile (the image is cached per commit),
  publishes it on port 8080 and runs the same Gradle task on an API 35 `pixel_6` emulator. The hub log is
  uploaded on failure.

### CLI x Android interop

`scripts/e2e-interop.sh` runs two gim CLI users (alice, bob; `gim-cli` built from gim-core `GIM_CORE_REF`,
default `7077421`) and the app (`app/src/androidTest/.../interop/InteropTest`, real UI and gim-core) against the
pinned gim-hub on the same emulator setup as the device tests. alice and bob create identities and claim handles
with `gim register`; the app goes through onboarding (create identity, acknowledge the recovery code, claim a
handle). Steps: DM both ways with read receipts, bob's group of three, text and images both ways, reply,
reaction, edit, delete, typing, rename from the app, bob removes alice (alice gets nothing new), and user ids
resolved by handle on both sides (CLI JSON and the app's `findUser`). `leave_chat` is not exercised. Each run
writes its logs to `LOG_DIR` (default `/tmp/gim-interop-<timestamp>`) and prints the passed steps; see `--help`.
`InteropTest` is skipped in plain `connectedDebugAndroidTest` runs.

### Real device

A dedicated test phone connected over wireless adb to the adb server in the long-running `gim-adb-host` container
(owned by the user, no ai-agent label; the scripts only `docker exec` into it and never stop, remove or restart it)
runs the app suite and the interop. Its serial is resolved at the start of each run from `adb devices -l` by model
(`GIM_REAL_MODEL`, default `2607DRA18G`) and must be a wireless `host:port` serial (it changes after reconnects);
`GIM_REAL_SERIAL` overrides it and must be `host:port` too. A USB-only attachment is refused ("wireless adb
required", exit 4). The scripts never run `adb connect`, `pair`, `disconnect` or `usb`. By default gim-hub is a sibling
container published on the host (`-p <port>:8080`, first free port from 18080) and the phone, on the same LAN, uses
`http://GIM_REAL_LAN_HOST:<port>` (default host `192.168.68.220`). `GIM_REAL_NET=reverse` is the fallback: the hub
runs in `gim-adb-host`'s network namespace on `127.0.0.1:<port>` and `adb reverse` maps the port on the phone.

Runs take the device lock (`/tmp/.lock-device` in the container) and never change device settings. `im.g.apps` and
`im.g.apps.test` stay installed: they are updated in place (`adb install -r -t`, no confirmation on the phone),
reset with `pm clear im.g.apps` and never uninstalled. Updating needs the key the installed packages are signed
with, so real-device builds sign with the persistent debug keystore in `GIM_REAL_ANDROID_HOME` (default
`/srv/.tmp/cache/android-debug-keystore`, used as `ANDROID_USER_HOME`). A first install makes HyperOS ask for
confirmation ("Install via USB"); the scripts accept it only while their own install of an `im.g.apps` package is
running, after a UI dump shows the dialog names GIM, by tapping the accept button found by its text, and log it.

- `scripts/run-real-device-tests.sh [AM_INSTRUMENT_ARGS...]` builds the debug and androidTest APKs, grants
  `im.g.apps` CAMERA, runs `im.g.apps.test` with `am instrument -w -r` (the suite without `CameraScannerE2eTest`,
  then that test alone, so a camera crash cannot hide other results) and writes `/tmp/gim-realdevice-<timestamp>/`
  (`summary.txt` with PASS/FAIL/SKIP and duration per test, `instrumentation.txt`, `logcat.txt` limited to the
  app's UIDs, `hub.log`, `screenshots/`). Exit codes: 0 passed, 1 test (or build/setup) failure, 3 the phone could
  not be updated unattended (signature mismatch, refused confirmation), 4 device not attached, 5 INTERRUPTED: the
  device went away or offline during the run, or a class failed while someone touched the phone (an infrastructure
  problem, recorded in `summary.txt`). Touches are found as MIUIInput events on the app's windows in the class's run
  window (Compose test clicks never log them; taps right after the tests' own system-dialog dismissals are
  attributed to those): a failed class becomes `INTERRUPTED (external input)` with the touch times, a passed one
  keeps PASS with a `WARN` line.
- `GIM_DEVICE=real scripts/e2e-interop.sh` runs the CLI x Android interop on the phone; the CLI container shares
  the hub's network namespace. Exit codes 4 and 5 as above (the device is checked after every step).
- `CameraScannerE2eTest` (instrumentation argument `realCamera=true`, skipped on emulators) opens the link-device
  scanner with the real CameraX pipeline: the back camera is opened, preview and analysis streams are configured,
  the scanner keeps running and leaving the screen releases the camera. Scanning a physical QR code with the
  phone's camera is manual: show the new device's QR code (for example on an emulator window or another phone), open
  **Link a new device** on the phone and point it at the code; `LinkDeviceE2eTest` covers the QR payload end to end.

### Pinned gim-hub

`e2e/GIM_HUB_VERSION` holds the full gim-hub commit the device tests run against. Locally the image is built
from `git archive <commit>` of the checkout in `GIM_HUB_SRC` (default `/srv/gimhq/im-client/hub`) with the hub's
own Dockerfile and reused while the tag exists. To move to another hub commit, change the file; both the local
script and CI follow it.

## Vendored gim-core

`:core:bridge` contains gim-core's Android artifacts:

- `core/bridge/src/main/jniLibs/{arm64-v8a,x86_64}/libgim_core.so`
- `core/bridge/src/main/kotlin/im/g/gimcore/gim_core.kt` (UniFFI bindings)

The vendored version and source commit are pinned in `GIM_CORE_VERSION` at the repo root. Refresh
them from a gim-core checkout:

```bash
scripts/sync-gim-core.sh [--build] [GIM_CORE_DIR]   # default /srv/gimhq/im-client/core
```

`--build` runs gim-core's `scripts/build-android.sh` before copying. Re-running with unchanged inputs
produces no diff.
