# aps-battery

Read-only offline Battery Snapshot domain. `parse_snapshot` accepts the existing
`dumpsys batterystats --checkin`, report UID modeled-energy lines, and
`dumpsys battery` key/value state as separate evidence channels. It does not
execute ADB or reconstruct missing fields.

`BatteryDelta::between` computes UID resource/network/system-model differences
only when the same UID, device ID, boot ID, statistics period, monotonic elapsed
domain and present UID records establish continuity. Missing identity, counter
resets, changed periods and reboots produce unavailable deltas, never negative
usage or synthetic zero. Charge-level difference remains device state and is not
called UID energy. Shared UID is explicitly retained as a scope warning; it does
not prove package ownership.

`BatteryReport` imports the existing schema-version-1 derived JSON exporter for
paginated inspection. Derived reports deliberately cannot restore raw snapshot
continuity or measured-zero semantics and report that limitation. Input, line,
record and collection bounds are enforced. Battery Historian, thermal/DVFS rail
causality, live capture and real-device acceptance are separate gates.

Tests are written but deliberately unexecuted until the complete rewrite is
implemented.
