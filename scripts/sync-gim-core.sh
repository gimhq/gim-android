#!/usr/bin/env bash
# Vendors gim-core's Android artifacts (native libraries + UniFFI Kotlin bindings) into :core:bridge.
#
# Usage: scripts/sync-gim-core.sh [--build] [GIM_CORE_DIR]
#   --build       run $GIM_CORE_DIR/scripts/build-android.sh first (SKIP_IMAGE_BUILD is passed through)
#   GIM_CORE_DIR  gim-core checkout, default /srv/gimhq/im-client/core
#
# Copies $GIM_CORE_DIR/out/android/* into the paths below and pins GIM_CORE_VERSION at the repo root.
# Re-running with unchanged inputs produces no diff.
set -euo pipefail

build=0
gim_core_dir=/srv/gimhq/im-client/core
for arg in "$@"; do
    case "$arg" in
        --build) build=1 ;;
        -h | --help)
            sed -n '2,9p' "$0"
            exit 0
            ;;
        -*)
            echo "error: unknown option: $arg" >&2
            exit 2
            ;;
        *) gim_core_dir=$arg ;;
    esac
done

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
bridge=$repo_root/core/bridge/src/main

[[ -d $gim_core_dir ]] || { echo "error: gim-core checkout not found: $gim_core_dir" >&2; exit 1; }
gim_core_dir=$(cd "$gim_core_dir" && pwd)
command -v git >/dev/null || { echo "error: git is required" >&2; exit 1; }

if ((build)); then
    build_script=$gim_core_dir/scripts/build-android.sh
    [[ -x $build_script ]] || { echo "error: missing or not executable: $build_script" >&2; exit 1; }
    "$build_script" # inherits SKIP_IMAGE_BUILD from the environment
fi

out=$gim_core_dir/out/android
# source (relative to $out) -> destination (relative to $bridge)
artifacts=(
    "jniLibs/arm64-v8a/libgim_core.so:jniLibs/arm64-v8a/libgim_core.so"
    "jniLibs/x86_64/libgim_core.so:jniLibs/x86_64/libgim_core.so"
    "kotlin/im/g/gimcore/gim_core.kt:kotlin/im/g/gimcore/gim_core.kt"
)

missing=()
for entry in "${artifacts[@]}" "GIM_CORE_VERSION:"; do
    src=$out/${entry%%:*}
    [[ -s $src ]] || missing+=("$src")
done
if ((${#missing[@]})); then
    echo "error: missing gim-core Android artifacts (run with --build?):" >&2
    printf '  %s\n' "${missing[@]}" >&2
    exit 1
fi

version=$(tr -d '[:space:]' <"$out/GIM_CORE_VERSION")
commit=$(git -C "$gim_core_dir" rev-parse HEAD)

for entry in "${artifacts[@]}"; do
    src=$out/${entry%%:*}
    dest=$bridge/${entry#*:}
    mkdir -p "$(dirname "$dest")"
    if ! cmp -s "$src" "$dest"; then
        cp "$src" "$dest"
    fi
    chmod 0644 "$dest"
    echo "vendored ${dest#"$repo_root"/}"
done

printf '%s\ncommit=%s\n' "$version" "$commit" >"$repo_root/GIM_CORE_VERSION"
echo "GIM_CORE_VERSION: $version (commit=$commit)"
