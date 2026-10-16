#!/usr/bin/env bash
# Vendors gim-core's Android artifacts (native libraries + UniFFI Kotlin bindings) into :core:bridge.
#
# Usage: scripts/sync-gim-core.sh [--build] [--commit SHA] [GIM_CORE_DIR]
#   --build       run $GIM_CORE_DIR/scripts/build-android.sh first (SKIP_IMAGE_BUILD is passed through)
#   --commit SHA  gim-core commit GIM_CORE_DIR holds (also GIM_CORE_COMMIT). Required, as a full 40-hex sha, unless
#                 GIM_CORE_DIR is the top level of a git checkout; for a checkout it must match its HEAD.
#   GIM_CORE_DIR  gim-core source: a checkout, or an export such as `git archive <sha> | tar -x -m -C DIR`;
#                 default /srv/gimhq/im-client/core
#
# Copies $GIM_CORE_DIR/out/android/* into the paths below and pins GIM_CORE_VERSION at the repo root as
# <crate version>+<commit, 7 hex> and commit=<full sha>. Re-running with unchanged inputs produces no diff.
set -euo pipefail

build=0
gim_core_dir=/srv/gimhq/im-client/core
commit=${GIM_CORE_COMMIT:-}
while (($#)); do
    case "$1" in
        --build) build=1 ;;
        --commit)
            [[ -n ${2:-} ]] || { echo "error: --commit needs a sha" >&2; exit 2; }
            commit=$2
            shift
            ;;
        -h | --help)
            sed -n '2,12p' "$0"
            exit 0
            ;;
        -*)
            echo "error: unknown option: $1" >&2
            exit 2
            ;;
        *) gim_core_dir=$1 ;;
    esac
    shift
done

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
bridge=$repo_root/core/bridge/src/main

[[ -d $gim_core_dir ]] || { echo "error: gim-core checkout not found: $gim_core_dir" >&2; exit 1; }
gim_core_dir=$(cd "$gim_core_dir" && pwd)
command -v git >/dev/null || { echo "error: git is required" >&2; exit 1; }

# A directory nested in another repository (e.g. an export under this worktree's .tmp/) is not a checkout of gim-core.
if [[ $(git -C "$gim_core_dir" rev-parse --show-toplevel 2>/dev/null) == "$gim_core_dir" ]]; then
    head=$(git -C "$gim_core_dir" rev-parse HEAD)
    if [[ -n $commit ]]; then
        resolved=$(git -C "$gim_core_dir" rev-parse --verify --quiet "$commit^{commit}") || {
            echo "error: commit $commit not found in $gim_core_dir" >&2
            exit 1
        }
        [[ $resolved == "$head" ]] || {
            echo "error: $gim_core_dir is at $head, not at --commit $commit" >&2
            exit 1
        }
    fi
    commit=$head
elif [[ ! $commit =~ ^[0-9a-f]{40}$ ]]; then
    echo "error: $gim_core_dir is not a git checkout; pass the full commit sha it was exported from with --commit" >&2
    exit 1
fi

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

# build-android.sh appends the short HEAD of the enclosing repository, which is wrong for an export.
version=$(tr -d '[:space:]' <"$out/GIM_CORE_VERSION")
version=${version%%+*}+${commit:0:7}

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
