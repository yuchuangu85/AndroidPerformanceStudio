#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
log_file="/tmp/desktop-app.log"

usage() {
  cat <<'EOF'
Usage: scripts/run-desktop-app.sh [--background]

Launches the AndroidPerformanceStudio desktop app.

  --background  Run detached with nohup, logging to /tmp/desktop-app.log.

Environment:
  JAVA_HOME      Must point to a JDK 21+ (default: the local JBR install).
  ANDROID_HOME   Android SDK location (default: /home/ts/Android/Sdk).

The script clears proxy environment variables for this run only (a stale
local proxy breaks adb/Gradle networking); nothing is persisted.
EOF
}

# Defaults matching the current developer workstation.
java_home="${JAVA_HOME:-/home/ts/.jbr/jbr_jcef-21.0.10-linux-x64-b1163.108}"
android_home="${ANDROID_HOME:-/home/ts/Android/Sdk}"

background=false
for argument in "$@"; do
  case "$argument" in
    --background) background=true ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 1 ;;
  esac
done

gradle_command=(
  env
  -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy
  -u all_proxy -u ALL_PROXY -u no_proxy -u NO_PROXY
  "JAVA_HOME=$java_home"
  "ANDROID_HOME=$android_home"
  ./gradlew :desktop-app:run
)

cd "$repository_root/desktop-viewer"

if [[ "$background" == true ]]; then
  nohup "${gradle_command[@]}" > "$log_file" 2>&1 &
  echo "Desktop app starting in background (pid $!). Log: $log_file"
else
  exec "${gradle_command[@]}"
fi
