# aps-adb

Read-only Android target discovery on `aps-platform`; no third-party dependencies.

`AdbLocator::locate` checks a configured executable first (invalid explicit paths
fail without silent fallback), then `ADB_PATH`, `ADB`, `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, absolute PATH entries and the host's customary SDK directory.
It validates regular-file/executable properties and returns a canonical path.
It does not execute candidates, inspect credential files, read Compose settings or
accept batch files as Windows executables. Only the relevant environment keys are
retained. No Rust ADB resource bundle is packaged yet: checksum/manifest-verified
bundle integration remains a separate release gate, not an unverified fallback.

`AdbClient` operations are blocking and should run on a UI background executor:

- `list_devices`: `adb devices -l`, preserving online, offline, unauthorized,
  no-permission and future unknown states.
- `device_properties`: selected-serial `shell getprop`, exposing only identity,
  Android release/SDK and ABI fields.
- `list_processes`: selected-serial `shell ps -A -o PID,PPID,USER,NAME`.
- `device_snapshot`: the above property and process observations in sequence, not
  an atomic snapshot or proof of a durable process-instance identity.

Each selected-target command has an explicit validated `-s` serial. No arbitrary
remote shell input, install, push, root, forward or capture mutation API is exposed.
The adapter applies bounded output parsing and returns sanitized command failures
without captured stderr. Calling a discovery operation may start the normal local
ADB server; no ADB command or device operation was run during implementation.

Pure parser and fake-filesystem test sources are provided under `tests/`, but are
intentionally unexecuted until the complete rewrite is implemented. Native ADB,
real-device, cross-platform and packaged-bundle acceptance remain pending.
