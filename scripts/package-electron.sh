#!/usr/bin/env bash
#
# Builds and packages the Electron desktop app.
#
#   ./scripts/package-electron.sh                 # installers for this host platform
#   ./scripts/package-electron.sh --dir           # unpacked bundle only (fast smoke build)
#   ./scripts/package-electron.sh --mac dmg       # anything else is passed to electron-builder
#
# pnpm is not required: the script drives the local electron-vite and
# electron-builder binaries, and only reaches for a package manager when the
# workspace has not been installed yet. The version comes from
# electron-viewer/apps/desktop/package.json; set it with
# `node electron-viewer/apps/desktop/scripts/set-version.mjs <version>`.
#
# electron-builder packs whatever `out/` holds, and its beforePack hook refuses a
# bundle older than `src/`, so this script always builds before it packs.
#
# Environment: ELECTRON_DIST points at an already unpacked Electron (for example
# electron-viewer/apps/desktop/node_modules/electron/dist) to skip the download;
# electron-builder reads ELECTRON_BUILDER_CACHE for its own tool cache by itself.

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
desktop="$repository_root/electron-viewer/apps/desktop"
manifest="$desktop/package.json"
release="$desktop/release"

usage() {
  # Every leading comment line after the shebang, so the help cannot drift from
  # the header the way a hard-coded line range does.
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac

command -v node >/dev/null 2>&1 || { echo "node is not on PATH" >&2; exit 1; }
[ -f "$manifest" ] || { echo "the desktop package is missing: $manifest" >&2; exit 1; }

# --- dependencies ----------------------------------------------------------
# A checkout without node_modules has no electron-vite to build with. Prefer an
# installed pnpm, then the corepack shim, then a pinned npx run: whichever the
# machine happens to have, without asking the user to install anything first.
run_pnpm() {
  if command -v pnpm >/dev/null 2>&1; then
    pnpm "$@"
  elif command -v corepack >/dev/null 2>&1; then
    corepack pnpm "$@"
  else
    # The workspace pins the version, so npx runs the same pnpm the other paths do.
    local pinned
    pinned="$(node -e 'process.stdout.write(String(require(process.argv[1]).packageManager))' "$repository_root/electron-viewer/package.json")"
    npx --yes "$pinned" "$@"
  fi
}

if [ ! -d "$desktop/node_modules/electron-vite" ]; then
  echo "installing the workspace (first run only)"
  (cd "$repository_root/electron-viewer" && run_pnpm install --frozen-lockfile)
fi

# --- runtime assets --------------------------------------------------------
# electron-builder copies these into the package as extraResources. A checkout
# has them only after the two fetchers have run, and a package built without
# them starts and then has no Trace Processor and no bundled Perfetto UI.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) trace_processor="$repository_root/build/perfetto-tools/trace_processor_shell.exe" ;;
  *) trace_processor="$repository_root/build/perfetto-tools/trace_processor_shell" ;;
esac
perfetto_ui="$repository_root/third_party/perfetto/out/ui/dist/index.html"

missing=0
[ -f "$perfetto_ui" ] || { echo "missing the bundled Perfetto UI: $perfetto_ui" >&2; missing=1; }
[ -f "$trace_processor" ] || { echo "missing the pinned Trace Processor: $trace_processor" >&2; missing=1; }
if [ "$missing" -ne 0 ]; then
  echo "fetch them first:" >&2
  echo "  $repository_root/scripts/build-perfetto-ui.sh download" >&2
  echo "  $repository_root/scripts/install-trace-processor.sh" >&2
  exit 1
fi

version="$(node -e 'process.stdout.write(require(process.argv[1]).version)' "$manifest")"
echo "packaging Android Performance Studio $version"

# --- build and package -----------------------------------------------------
# --publish never keeps even a full run away from any release; --dir (or any
# other electron-builder flag) reaches the packager through "$@".
(cd "$desktop" && ./node_modules/.bin/electron-vite build)

builder_args=(--publish never)
if [ -n "${ELECTRON_DIST:-}" ]; then
  builder_args+=("-c.electronDist=$ELECTRON_DIST")
fi

echo "running electron-builder ${builder_args[*]} $*"
(cd "$desktop" && ./node_modules/.bin/electron-builder "${builder_args[@]}" "$@")

# --- verify ----------------------------------------------------------------
node "$desktop/scripts/verify-package.mjs" "$release"

if command -v hdiutil >/dev/null 2>&1; then
  for image in "$release"/*.dmg; do
    [ -e "$image" ] || continue
    hdiutil verify "$image" >/dev/null
    echo "verified $(basename "$image")"
  done
fi

# --- what was produced -----------------------------------------------------
digest() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    echo '(no sha256 tool on PATH)'
  fi
}

echo
echo "output in $release"
for file in "$release"/*.dmg "$release"/*.pkg "$release"/*.exe "$release"/*.msi "$release"/*.deb "$release"/*.rpm; do
  [ -e "$file" ] || continue
  printf '  %s (%s bytes)\n    sha256 %s\n' "$(basename "$file")" "$(wc -c < "$file" | tr -d ' ')" "$(digest "$file")"
done
