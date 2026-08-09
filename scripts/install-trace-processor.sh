#!/usr/bin/env bash

set -euo pipefail

version="v57.2"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checksum_manifest="$repository_root/desktop-viewer/platform-perfetto/trace-processor-manifest.json"
install_dir="${PERFETTO_TOOLS_DIR:-$HOME/.android-performance-studio/tools/perfetto/$version}"
temporary="$(mktemp -t trace_processor.XXXXXX)"

cleanup() {
  rm -f "$temporary"
}
trap cleanup EXIT

mkdir -p "$install_dir"
case "$(uname -s)" in
  Darwin) host_os=macos ;;
  Linux) host_os=linux ;;
  MINGW*|MSYS*|CYGWIN*) host_os=windows ;;
  *) echo "Unsupported Trace Processor host OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64) host_arch=x64 ;;
  arm64|aarch64) host_arch=arm64 ;;
  *) echo "Unsupported Trace Processor host architecture: $(uname -m)" >&2; exit 1 ;;
esac
host_key="$host_os-$host_arch"
IFS=$'\t' read -r download_url expected_checksum < <(python3 - "$checksum_manifest" "$host_key" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
if manifest["version"] != "v57.2":
    raise SystemExit(f"unexpected Trace Processor manifest version: {manifest['version']}")
entry = manifest["artifacts"].get(sys.argv[2])
if entry is None:
    raise SystemExit(f"unsupported Trace Processor host: {sys.argv[2]}")
print(entry["url"], entry["sha256"], sep="\t")
PY
)

curl --fail --location --retry 3 --output "$temporary" "$download_url"
actual_checksum="$(python3 - "$temporary" <<'PY'
import hashlib, sys
with open(sys.argv[1], "rb") as source:
    print(hashlib.sha256(source.read()).hexdigest())
PY
)"
if [[ "$actual_checksum" != "$expected_checksum" ]]; then
  echo "Trace Processor checksum mismatch for $host_key" >&2
  exit 1
fi
case "$host_os" in
  windows) native_target="$install_dir/trace_processor_shell.exe" ;;
  *) native_target="$install_dir/trace_processor_shell" ;;
esac
mv "$temporary" "$native_target"
chmod +x "$native_target"
if ! "$native_target" --version 2>&1 | grep -F "$version" >/dev/null; then
  echo "Trace Processor binary does not report pinned version $version" >&2
  exit 1
fi
echo "trace_processor $version native binary installed at $native_target"
