#!/usr/bin/env bash

set -euo pipefail

version="v57.2"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
perfetto_root="$repository_root/third_party/perfetto"
launcher_source="$perfetto_root/tools/trace_processor"
install_dir="${PERFETTO_TOOLS_DIR:-$HOME/.android-performance-studio/tools/perfetto/$version}"
target="$install_dir/trace_processor"
temporary="$(mktemp -t trace_processor.XXXXXX)"

cleanup() {
  rm -f "$temporary"
}
trap cleanup EXIT

mkdir -p "$install_dir"
if [[ ! -f "$launcher_source" ]]; then
  echo "Pinned Perfetto launcher is missing; initialize third_party/perfetto first" >&2
  exit 1
fi
# GitHub Actions checks submodules out at their pinned gitlink with depth 1, so
# the release tag itself is intentionally not guaranteed to be present locally.
# The superproject gitlink is the authoritative revision for the launcher.
expected_revision="$(git -C "$repository_root" ls-files --stage -- third_party/perfetto | awk '{ print $2 }')"
if [[ -z "$expected_revision" ]]; then
  echo "third_party/perfetto has no pinned gitlink in the superproject" >&2
  exit 1
fi
actual_revision="$(git -C "$perfetto_root" rev-parse HEAD)"
if [[ "$actual_revision" != "$expected_revision" ]]; then
  echo "third_party/perfetto is $actual_revision, expected $version ($expected_revision)" >&2
  exit 1
fi
cp "$launcher_source" "$temporary"

chmod +x "$temporary"
mv "$temporary" "$target"

# Pre-warm the launcher so the matching native binary is available offline.
"$target" --help >/dev/null

native_source="$(python3 - "$target" <<'PY'
import ast
import os
import platform
import sys

source_path = sys.argv[1]
tree = ast.parse(open(source_path, encoding="utf-8").read(), source_path)
manifest = None
for node in tree.body:
    if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == "TRACE_PROCESSOR_SHELL_MANIFEST" for target in node.targets):
        manifest = ast.literal_eval(node.value)
        break
if manifest is None:
    raise SystemExit("trace_processor launcher does not contain a prebuilt manifest")
host_platform = sys.platform.lower()
host_machine = platform.machine().lower()
entry = next((item for item in manifest if item.get("platform") == host_platform and host_machine in item.get("machine", [])), None)
if entry is None:
    raise SystemExit(f"No trace_processor prebuilt for {host_platform}-{host_machine}")
root, extension = os.path.splitext(entry["file_name"])
filename = f"{root}-{entry['sha256'][:16]}{extension}"
print(os.path.join(os.path.expanduser("~"), ".local", "share", "perfetto", "prebuilts", filename))
PY
)"

if [[ ! -x "$native_source" ]]; then
  echo "Pre-warmed trace_processor binary was not found at $native_source" >&2
  exit 1
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) native_target="$install_dir/trace_processor_shell.exe" ;;
  *) native_target="$install_dir/trace_processor_shell" ;;
esac
cp "$native_source" "$native_target"
chmod +x "$native_target"
echo "trace_processor $version launcher installed at $target"
echo "trace_processor $version native binary installed at $native_target"
