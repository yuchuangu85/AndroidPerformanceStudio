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

# --- running copies --------------------------------------------------------
# A packaged Electron application reads its bundles once, at startup: replacing
# the bundle under a running instance changes nothing on screen. The packaging
# then looks stale when the window is merely old, which is expensive to tell
# apart by eye, so a running instance is named before the build and again after
# it.
product_name="$(sed -n 's/^productName:[[:space:]]*//p' "$desktop/electron-builder.yml" 2>/dev/null | head -1)"
running_instances() {
  [ -n "$product_name" ] || return 0
  pgrep -fl "$product_name" 2>/dev/null | head -5
}
if [ -n "$(running_instances)" ]; then
  echo "warning: $product_name is running; its window keeps the build it started with" >&2
  running_instances | sed 's/^/  /' >&2
fi

# --- caches ----------------------------------------------------------------
# electron-builder unpacks its dmgbuild helper and the Electron binary into
# $HOME/Library/Caches. Where the home is not writable — the DSH file sandbox, a
# hardened container — that fails with EPERM before anything is packed, so fall
# back to the caches this checkout is already set up to keep. Both directories
# are gitignored, and a machine with a writable home never reaches this branch.
if ! { [ -n "${HOME:-}" ] && mkdir -p "$HOME/Library/Caches" 2>/dev/null && [ -w "$HOME/Library/Caches" ]; }; then
  echo "the home cache is not writable; keeping the downloads inside the checkout" >&2
  export HOME="$repository_root/electron-viewer/.home"
  export ELECTRON_BUILDER_CACHE="${ELECTRON_BUILDER_CACHE:-$repository_root/electron-viewer/.electron-builder-cache}"
  export ELECTRON_CACHE="${ELECTRON_CACHE:-$repository_root/electron-viewer/.electron-cache}"
  mkdir -p "$HOME" "$ELECTRON_BUILDER_CACHE" "$ELECTRON_CACHE"
fi

# --- build and package -----------------------------------------------------
# --publish never keeps even a full run away from any release; --dir (or any
# other electron-builder flag) reaches the packager through "$@".
#
# The installers already in release/ are recorded first. A run that leaves a
# target out — no hdiutil, one platform — leaves the previous file sitting
# there, and reporting it as this run's output is how a stale installer gets
# shipped twice.
stamp_of() {
  if stat -f '%m:%z' "$1" >/dev/null 2>&1; then
    stat -f '%m:%z' "$1"
  else
    stat -c '%Y:%s' "$1"
  fi
}

installers() {
  for file in "$release"/*.dmg "$release"/*.pkg "$release"/*.exe "$release"/*.msi "$release"/*.deb "$release"/*.rpm; do
    [ -e "$file" ] || continue
    printf '%s %s\n' "$(stamp_of "$file")" "$file"
  done
}

installers_before="$(installers)"

# True when this run rewrote the file: a leftover keeps the stamp it had.
produced() {
  ! printf '%s\n' "$installers_before" | grep -qxF "$(stamp_of "$1") $1"
}

(cd "$desktop" && ./node_modules/.bin/electron-vite build)

builder_args=(--publish never)
if [ -n "${ELECTRON_DIST:-}" ]; then
  builder_args+=("-c.electronDist=$ELECTRON_DIST")
fi

# dmgbuild shells out to hdiutil, and the file sandbox denies the disk-image
# helper. Without this the whole run dies inside the vendored Python with a
# stack trace and no package at all, so probe once and pack the target that does
# work, saying plainly which one was left out.
if [ "$(uname -s)" = "Darwin" ] && command -v hdiutil >/dev/null 2>&1; then
  probe="$release/.hdiutil-probe.dmg"
  mkdir -p "$release"
  if hdiutil create -size 1m -fs HFS+ -volname aps-probe "$probe" >/dev/null 2>&1; then
    rm -f "$probe"
  else
    rm -f "$probe"
    echo "hdiutil cannot create disk images here: packaging without the DMG" >&2
    echo "  for a .dmg, run this script where the disk-image helper is allowed" >&2
    builder_args+=(-c.mac.target=pkg)
  fi
fi

echo "running electron-builder ${builder_args[*]} $*"
(cd "$desktop" && ./node_modules/.bin/electron-builder "${builder_args[@]}" "$@")

# --- verify ----------------------------------------------------------------
node "$desktop/scripts/verify-package.mjs" "$release"

if command -v hdiutil >/dev/null 2>&1; then
  for image in "$release"/*.dmg; do
    [ -e "$image" ] || continue
    produced "$image" || continue
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

# --- what will actually run -------------------------------------------------
# The package is not what this machine launches: an installed copy, a mounted
# image and the Dock all keep bundles of their own, and every one of them can be
# older than the build just made. Naming them is the difference between "the
# build is stale" and "that is not the build you opened".
built_app="$(ls -d "$release"/*/*.app 2>/dev/null | head -1)"
if [ -n "$built_app" ]; then
  bundle_name="$(basename "$built_app")"
  for candidate in "/Applications/$bundle_name" /Volumes/*"${bundle_name%.app}"*; do
    [ -e "$candidate" ] || continue
    echo "note: $candidate is a different copy; this run made $built_app" >&2
  done
fi

if [ -n "$(running_instances)" ]; then
  echo >&2
  echo "reminder: $product_name is still running and shows the previous build." >&2
  echo "  quit it (Cmd+Q) and open the build above to see this one." >&2
fi

echo
echo "output in $release"
for file in "$release"/*.dmg "$release"/*.pkg "$release"/*.exe "$release"/*.msi "$release"/*.deb "$release"/*.rpm; do
  [ -e "$file" ] || continue
  if ! produced "$file"; then
    printf '  %s (left over from an earlier run; not rebuilt)\n' "$(basename "$file")"
    continue
  fi
  printf '  %s (%s bytes)\n    sha256 %s\n' "$(basename "$file")" "$(wc -c < "$file" | tr -d ' ')" "$(digest "$file")"
done
