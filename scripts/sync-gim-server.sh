#!/usr/bin/env bash
# Vendors gim-server (the mock delivery/auth service used by device tests) into e2e/gim-server.
#
# Usage: scripts/sync-gim-server.sh [SERVER_DIR]
#   SERVER_DIR  gim-server checkout, default /srv/gimhq/im-client/server
#
# Copies package.json, bun.lock, tsconfig.json and src/ as committed at SERVER_DIR's HEAD (no tests,
# no Dockerfile, no node_modules) and pins e2e/gim-server/VERSION. Re-running with an unchanged
# checkout produces no diff.
#
# Why vendored: decision uqrmw8ux (server repository visibility) is still open, so CI must not read
# another private repository. Like the gim-core artifacts (scripts/sync-gim-core.sh), the server is
# copied into this repository and refreshed explicitly.
set -euo pipefail

server_dir=/srv/gimhq/im-client/server
for arg in "$@"; do
    case "$arg" in
        -h | --help)
            sed -n '2,13p' "$0"
            exit 0
            ;;
        -*)
            echo "error: unknown option: $arg" >&2
            exit 2
            ;;
        *) server_dir=$arg ;;
    esac
done

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
dest=$repo_root/e2e/gim-server

[[ -d $server_dir ]] || { echo "error: gim-server checkout not found: $server_dir" >&2; exit 1; }
command -v git >/dev/null || { echo "error: git is required" >&2; exit 1; }
commit=$(git -C "$server_dir" rev-parse --short HEAD)

paths=(package.json bun.lock tsconfig.json src)
staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT
# Export from HEAD rather than the working tree so local edits and untracked files never leak in.
git -C "$server_dir" archive --format=tar HEAD -- "${paths[@]}" | tar -x -C "$staging"
printf 'gim-server %s\n' "$commit" >"$staging/VERSION"

mkdir -p "$dest"
rm -rf "$dest/src"
for path in "${paths[@]}" VERSION; do
    cp -R "$staging/$path" "$dest/$path"
done
echo "vendored e2e/gim-server: gim-server $commit"
