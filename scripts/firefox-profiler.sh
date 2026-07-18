#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
profiler_path="$repository_root/third_party/firefox-profiler"

usage() {
  cat <<'EOF'
Usage: scripts/firefox-profiler.sh <command>

Commands:
  init     Initialize the pinned Git submodule checkout.
  verify   Verify the submodule revision and Node/Yarn toolchain.
  install  Install dependencies with the pinned yarn.lock.
  build    Build production static assets into third_party/firefox-profiler/dist.
  all      Initialize, install, and build.
EOF
}

initialize_submodule() {
  git -C "$repository_root" submodule update --init --depth 1 --recursive -- third_party/firefox-profiler
}

expected_revision() {
  git -C "$repository_root" ls-files --stage third_party/firefox-profiler | awk '{ print $2 }'
}

verify_checkout() {
  if [[ ! -f "$profiler_path/package.json" ]]; then
    echo "Firefox Profiler submodule is not initialized. Run: scripts/firefox-profiler.sh init" >&2
    exit 1
  fi

  local expected actual
  expected="$(expected_revision)"
  actual="$(git -C "$profiler_path" rev-parse HEAD)"
  if [[ -z "$expected" || "$actual" != "$expected" ]]; then
    echo "Firefox Profiler revision mismatch: expected $expected, found $actual" >&2
    exit 1
  fi
}

activate_node_toolchain() {
  local node_major=""
  if command -v node >/dev/null; then
    node_major="$(node --version | sed -E 's/^v([0-9]+).*/\1/')"
  fi
  if [[ "$node_major" == "24" ]]; then
    return
  fi

  export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
  if [[ -s "$NVM_DIR/nvm.sh" ]]; then
    set +u
    source "$NVM_DIR/nvm.sh"
    nvm use --silent 24 >/dev/null
    set -u
  fi
}

verify_toolchain() {
  activate_node_toolchain
  command -v node >/dev/null || { echo "Node.js 24 is required." >&2; exit 1; }
  command -v yarn >/dev/null || { echo "Yarn Classic is required." >&2; exit 1; }

  local node_major yarn_major
  node_major="$(node --version | sed -E 's/^v([0-9]+).*/\1/')"
  yarn_major="$(yarn --version | cut -d. -f1)"
  if [[ "$node_major" != "24" ]]; then
    echo "Firefox Profiler requires Node.js 24; found $(node --version)." >&2
    exit 1
  fi
  if [[ "$yarn_major" != "1" ]]; then
    echo "Firefox Profiler requires Yarn Classic 1.x; found $(yarn --version)." >&2
    exit 1
  fi
}

verify_all() {
  verify_checkout
  verify_toolchain
  echo "Firefox Profiler $(git -C "$profiler_path" rev-parse HEAD)"
  echo "Node $(node --version), Yarn $(yarn --version)"
}

install_dependencies() {
  verify_all
  (cd "$profiler_path" && yarn install --frozen-lockfile)
}

build_profiler() {
  verify_all
  if [[ ! -d "$profiler_path/node_modules" ]]; then
    echo "Dependencies are not installed. Run: scripts/firefox-profiler.sh install" >&2
    exit 1
  fi
  (cd "$profiler_path" && yarn build-prod)
}

command="${1:-}"
case "$command" in
  init)
    initialize_submodule
    ;;
  verify)
    verify_all
    ;;
  install)
    install_dependencies
    ;;
  build)
    build_profiler
    ;;
  all)
    initialize_submodule
    install_dependencies
    build_profiler
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
