#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 || ! -d "$1" || "$2" != *.dmg ]]; then
  echo "usage: $0 <application bundle> <output.dmg>" >&2
  exit 2
fi

app="$1"
output="$2"
scratch="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/aps-dmg.XXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT
stage="$scratch/stage"
mkdir "$stage"
ditto "$app" "$stage/Android Performance Studio.app"
ln -s /Applications "$stage/Applications"

for attempt in 1 2 3 4; do
  image="$scratch/attempt-$attempt.dmg"
  log="$scratch/hdiutil.log"
  if LC_ALL=C hdiutil create -volname "Android Performance Studio" \
    -srcfolder "$stage" -nospotlight -format UDZO "$image" >"$log" 2>&1; then
    cat "$log"
    hdiutil verify "$image"
    mv "$image" "$output"
    exit 0
  else
    status=$?
  fi
  cat "$log" >&2
  if [[ "$attempt" == 4 ]] || ! grep -Fq 'Resource busy' "$log"; then
    exit "$status"
  fi
  echo "hdiutil reports a busy resource (attempt $attempt/4); retrying" >&2
  sleep "$((attempt * 5))"
done
