#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
perfetto_path="$repository_root/third_party/perfetto"

usage() {
  cat <<'EOF'
Usage: scripts/build-perfetto-ui.sh <command>

Commands:
  init     Initialize the pinned Perfetto Git submodule checkout.
  verify   Verify the submodule revision and toolchain.
  install  Install Node.js + pnpm + dependencies via tools/install-build-deps --ui.
  build    Build the Perfetto UI (JS/CSS/HTML only, no WASM).
           Optionally download pre-built WASM from GitHub Releases.
  all      Initialize, install, and build (full bootstrap).
  full     Initialize, install, and full build (includes C++ WASM, needs gn/ninja/clang).

The submodule must be added first (requires network):
  git submodule add --depth 1 https://github.com/google/perfetto third_party/perfetto

After adding, pin to a specific release commit (e.g., v57.2):
  cd third_party/perfetto
  git fetch origin tag v57.2
  git checkout v57.2
EOF
}

# ---- submodule ----

initialize_submodule() {
  if [[ ! -f "$perfetto_path/ui/package.json" ]]; then
    echo "Perfetto submodule is not present. Add it first:" >&2
    echo "  git submodule add --depth 1 https://github.com/google/perfetto third_party/perfetto" >&2
    exit 1
  fi
  git -C "$repository_root" submodule update --init --depth 1 -- third_party/perfetto
}

expected_revision() {
  git -C "$repository_root" ls-files --stage third_party/perfetto | awk '{ print $2 }'
}

verify_checkout() {
  if [[ ! -f "$perfetto_path/ui/package.json" ]]; then
    echo "Perfetto submodule is not initialized." >&2
    echo "Run: scripts/build-perfetto-ui.sh init   (or add the submodule first)" >&2
    exit 1
  fi

  local expected actual
  expected="$(expected_revision)"
  actual="$(git -C "$perfetto_path" rev-parse HEAD)"
  if [[ -z "$expected" ]]; then
    echo "Perfetto submodule has no pinned revision in .gitmodules." >&2
    echo "Pin a commit and commit the change:" >&2
    echo "  cd third_party/perfetto && git checkout <commit>" >&2
    echo "  cd ../.. && git add third_party/perfetto" >&2
  elif [[ "$actual" != "$expected" ]]; then
    echo "Perfetto revision mismatch: expected $expected, found $actual" >&2
    exit 1
  fi
}

# ---- toolchain ----

install_dependencies() {
  verify_checkout
  echo "=== Installing Perfetto UI build dependencies ==="
  echo "This downloads Node.js 22.23.1 + pnpm 10.34.5 + npm packages (~200 MB first time)."
  (cd "$perfetto_path" && tools/install-build-deps --ui)
}

verify_toolchain() {
  verify_checkout
  local node_bin="$perfetto_path/buildtools/nodejs"
  if [[ ! -x "$node_bin" ]]; then
    echo "Node.js is not installed. Run: scripts/build-perfetto-ui.sh install" >&2
    exit 1
  fi
  local node_version
  node_version="$("$node_bin" --version)"
  echo "Perfetto $(git -C "$perfetto_path" rev-parse HEAD)"
  echo "Node $node_version, pnpm $(ls "$perfetto_path/third_party/pnpm/" 2>/dev/null || echo 'not found')"
}

# ---- build ----

build_ui() {
  verify_toolchain
  if [[ ! -d "$perfetto_path/ui/node_modules" ]]; then
    echo "Dependencies are not installed. Run: scripts/build-perfetto-ui.sh install" >&2
    exit 1
  fi
  echo "=== Building Perfetto UI (JS/CSS/HTML only, --no-wasm) ==="
  (cd "$perfetto_path" && ui/build --no-wasm)
  echo ""
  echo "Build complete. Output: $perfetto_path/out/ui/dist/"
  echo ""
  echo "To include trace_processor.wasm (required for local trace loading), either:"
  echo "  A) Build with C++ toolchain: scripts/build-perfetto-ui.sh full"
  echo "  B) Download pre-built from GitHub Releases:"
  echo "     curl -sL https://github.com/google/perfetto/releases/download/v57.2/perfetto-ui.zip |"
  echo "     bsdtar -xf - -C $perfetto_path/out/ui/dist/ --strip-components 1"
}

build_ui_full() {
  verify_toolchain
  if [[ ! -d "$perfetto_path/ui/node_modules" ]]; then
    echo "Dependencies are not installed. Run: scripts/build-perfetto-ui.sh install" >&2
    exit 1
  fi
  echo "=== Building Perfetto UI (full, with C++ WASM) ==="
  echo "Requires: gn, ninja, clang++, Android NDK (for trace_processor)."
  echo "This may take 5-15 minutes."
  (cd "$perfetto_path" && ui/build)
  echo ""
  echo "Full build complete. Output: $perfetto_path/out/ui/dist/"
}

# ---- orchestration ----

all_in_one() {
  initialize_submodule
  install_dependencies
  build_ui
}

full_bootstrap() {
  initialize_submodule
  install_dependencies
  build_ui_full
}

# ---- dispatch ----

command="${1:-}"
case "$command" in
  init)
    initialize_submodule
    ;;
  verify)
    verify_toolchain
    ;;
  install)
    install_dependencies
    ;;
  build)
    build_ui
    ;;
  full)
    full_bootstrap
    ;;
  all)
    all_in_one
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
