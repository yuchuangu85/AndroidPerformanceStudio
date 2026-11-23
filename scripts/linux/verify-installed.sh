#!/usr/bin/env bash

# Verify installed package bytes and CLI dependencies, not GUI functionality.
set -euo pipefail
export LC_ALL=C

fail() {
  printf 'verify-installed: %s\n' "$*" >&2
  exit 1
}

[[ $# -eq 3 ]] || fail "Usage: bash $0 <staged-package-root> <installed-prefix> <expected-trace-processor-sha256>"
[[ "$3" =~ ^[[:xdigit:]]{64}$ ]] || fail "Expected Trace Processor SHA256 must be exactly 64 hex digits"
[[ "$(uname -s)" == Linux ]] || fail "Linux is required"
uid="$(id -u)" || fail "Cannot verify non-root ordinary-user identity"
[[ "$uid" =~ ^[1-9][0-9]*$ ]] || fail "Run as a non-root ordinary user"

for utility in cmp find mktemp readelf readlink rm sha256sum timeout tr ldd; do
  command -v "$utility" >/dev/null || fail "Required command not found: $utility"
done

[[ -n "$1" && -d "$1" ]] || fail "Staged package root must be an existing directory"
[[ -n "$2" && -d "$2" ]] || fail "Installed prefix must be an existing directory"
staged="$(cd -- "$1" && pwd -P)" || fail "Cannot access staged package directory: $1"
prefix="$(cd -- "$2" && pwd -P)" || fail "Cannot access installed prefix directory: $2"
prefix="${prefix%/}"
[[ -d "$staged/usr" && ! -L "$staged/usr" ]] || fail "Staged package must contain a usr directory"
expected="$(printf '%s' "$3" | tr 'A-F' 'a-f')"

lib=usr/lib/aps-desktop
tool="$prefix/$lib/resources/perfetto-tools/trace_processor_shell"
binaries=("$lib/aps-app" "$lib/aps-memory-worker" "$lib/resources/perfetto-tools/trace_processor_shell")
for relative in "${binaries[@]}"; do
  [[ -f "$staged/$relative" && ! -L "$staged/$relative" ]] || fail "Missing staged regular binary: $relative"
  installed="$prefix/$relative"
  [[ -f "$installed" && ! -L "$installed" ]] || fail "Missing installed regular binary: $relative"
  [[ -r "$installed" ]] || fail "Installed binary is not readable: $relative"
  [[ -x "$installed" ]] || fail "Installed binary is not executable: $relative"
done

for binary in aps-app aps-memory-worker; do
  target="../lib/aps-desktop/$binary"
  for root in "$staged" "$prefix"; do
    link="$root/usr/bin/$binary"
    [[ -L "$link" ]] || fail "Missing app/worker symlink: $link"
    [[ "$(readlink -- "$link")" == "$target" ]] || fail "Incorrect app/worker symlink target: $link (expected $target)"
  done
  [[ -x "$prefix/usr/bin/$binary" ]] || fail "Installed symlink is not executable: usr/bin/$binary"
done

# Materialize the NUL-delimited list so a failed find cannot be lost in a
# process substitution, silently omitting an unreadable staged subtree.
file_list="$(mktemp)" || fail "Cannot create staged file list"
trap 'rm -f -- "$file_list"' EXIT
if ! (cd -- "$staged" && find usr -type f -print0) > "$file_list"; then
  fail "Cannot enumerate all staged regular files under usr"
fi
count=0
while IFS= read -r -d '' relative; do
  installed="$prefix/$relative"
  [[ -f "$installed" && ! -L "$installed" ]] || fail "Missing installed regular file: $relative"
  [[ -r "$staged/$relative" && -r "$installed" ]] || fail "Staged/installed file is not readable: $relative"
  cmp -s -- "$staged/$relative" "$installed" || fail "Installed payload mismatch: $relative"
  count=$((count + 1))
done < "$file_list"

# The caller's external pin is the trust boundary, not the staged tool bytes
# or a checksum sidecar shipped alongside the executable.
checksum="$(sha256sum < "$tool")" || fail "Cannot compute Trace Processor SHA256: $tool"
[[ "${checksum%% *}" == "$expected" ]] || fail "Installed Trace Processor SHA256 mismatch: $tool"

inspection_errors="$(mktemp)" || fail "Cannot create ELF inspection error file"
trap 'rm -f -- "$file_list" "$inspection_errors"' EXIT
elf_type=$'(^|\n)[[:blank:]]*Type:[[:blank:]]+(EXEC|DYN)[[:blank:]]'
entry_point=$'(^|\n)[[:blank:]]*Entry point address:[[:blank:]]+0x0*[1-9a-fA-F][[:xdigit:]]*[[:blank:]]*(\n|$)'
load_segment=$'(^|\n)[[:blank:]]*LOAD[[:blank:]]+0x[[:xdigit:]]+'
interpreter=$'(^|\n)[[:blank:]]*INTERP[[:blank:]]'
dynamic_segment=$'(^|\n)[[:blank:]]*DYNAMIC[[:blank:]]'
dynamic_section=$'(^|\n)Dynamic section at offset 0x[[:xdigit:]]+ contains [1-9][0-9]* entr(y|ies):'
dynamic_end=$'(^|\n)[[:blank:]]*0x0+[[:blank:]]+\\(NULL\\)[[:blank:]]'
for relative in "${binaries[@]}"; do
  installed="$prefix/$relative"
  if ! elf="$(timeout --kill-after=5s 15s readelf --file-header --program-headers --dynamic "$installed" 2> "$inspection_errors")" || [[ -s "$inspection_errors" ]]; then
    printf '%s\n' "$elf" "$(< "$inspection_errors")" >&2
    fail "readelf inspection failed or timed out: $installed"
  fi
  [[ "$elf" == "ELF Header:"$'\n'* && "$elf" =~ $elf_type && "$elf" =~ $entry_point &&
     "$elf" == *$'\nProgram Headers:\n'* && "$elf" =~ $load_segment ]] || fail "readelf did not prove an executable ELF: $installed"

  # Require a complete dynamic-section result before treating absent tags as
  # proof of static linkage. A static PIE can have a dynamic section.
  if [[ "$elf" == *"There is no dynamic section in this file."* ]]; then
    [[ ! "$elf" =~ $dynamic_segment && ! "$elf" =~ $dynamic_section ]] || fail "readelf returned inconsistent dynamic metadata: $installed"
  else
    [[ "$elf" =~ $dynamic_section && "$elf" =~ $dynamic_end ]] || fail "readelf returned incomplete dynamic metadata: $installed"
  fi
  if [[ ! "$elf" =~ $interpreter && "$elf" != *"(NEEDED)"* ]]; then
    printf 'Verified static ELF (no PT_INTERP or DT_NEEDED): %s\n' "$installed"
    continue
  fi

  if ! output="$(timeout --kill-after=5s 15s ldd "$installed" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "ldd failed or timed out: $installed"
  fi
  if [[ "$output" == *"not found"* ]]; then
    printf '%s\n' "$output" >&2
    fail "ldd reports missing libraries: $installed"
  fi
done

if ! timeout --kill-after=5s 15s "$tool" --version; then
  fail "Installed Trace Processor --version failed or timed out: $tool"
fi
printf 'Verified %s installed payload files, app/worker symlinks, Trace Processor pin/version, and ELF dependencies. No GUI launched.\n' "$count"
