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
| `:feature:onboarding` | Onboarding (server URL, account)                                 |
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

## Device tests

Instrumented tests (`core/bridge/src/androidTest`) run against a real gim-server. The server URL comes from the
`gimServerUrl` instrumentation argument, default `http://10.0.2.2:8080`:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.gimServerUrl=http://10.0.2.2:8080
```

- **Locally:** `scripts/run-device-tests.sh [GRADLE_ARGS...]` starts the vendored gim-server
  (`oven/bun:1.4`) and an API 35 x86_64 emulator in sibling Docker containers (requires `/dev/kvm` on
  the host), then runs `connectedDebugAndroidTest`. Extra args are passed to Gradle, e.g.
  `scripts/run-device-tests.sh :core:bridge:connectedDebugAndroidTest`. `REBUILD_IMAGE=1` rebuilds
  the emulator image; `GRADLE_CACHE` sets the host Gradle user home (default
  `/srv/.tmp/cache/gradle`). The worktree must be on a host-visible path. See `--help`.
  `CI_LIKE=1` reproduces a small CI runner: the emulator's default 320x640 device, 2 cores, 3 GB RAM,
  boot from a freshly saved snapshot and doubled test timeouts (each knob can be overridden, e.g.
  `DEVICE_PROFILE`, `CORES`, `GPU`). On failure the full device logcat is written to
  `.tmp/device-tests/`.
- **CI:** job `device-tests` in `.github/workflows/ci.yml` starts gim-server with Bun on port 8080 and
  runs the same Gradle task on an API 35 `pixel_6` emulator.

### Vendored gim-server

`e2e/gim-server` holds a copy of gim-server (`package.json`, `bun.lock`, `tsconfig.json`, `src/`)
pinned by `e2e/gim-server/VERSION`. It is vendored because CI must not read another private
repository. Refresh it from a server checkout:

```bash
scripts/sync-gim-server.sh [SERVER_DIR]   # default /srv/gimhq/im-client/server
```

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
