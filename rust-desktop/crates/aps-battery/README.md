# aps-battery

Read-only Battery Snapshot domain. `parse_snapshot` accepts the existing
`dumpsys batterystats --checkin`, report UID modeled-energy lines, and
`dumpsys battery` key/value state as separate evidence channels. It does not
execute ADB or reconstruct missing fields. The separate `capture_live` adapter
uses an explicitly supplied `AdbClient`, after user approval, off the UI thread.

## Explicit live capture

`BatteryCaptureTarget` freezes the selected serial, PID, process and installed
package. Capture verifies that target against `AdbClient` discovery, then binds
the boot ID, `/proc` process start ticks and actual UID. Read-only package UID
enumeration identifies shared UIDs; discovery/process observations bracket every
snapshot and attribution is rechecked before publication. The selected active
PackageManager record's `codePath`, `versionCode` and `lastUpdateTime` are frozen
before collection and rechecked inside process-identity brackets at completion.
Hidden system-package records and other packages cannot supply those fields.
This is metadata continuity, not APK-content/signature verification; sibling
packages sharing the UID are not independently installation-verified.
Process death/PID reuse,
reboot, permission errors or changing package/UID/accumulator identity abort the
operation without replacing the previously displayed report.

Supported modes are bounded Interactive, Timed, Repeated and low-frequency Online.
Durations are at most 600 seconds per run, runs at most 10, and snapshot count at
most 1024. Online periodic polling is at least 30 seconds. Per-command output and
timeouts plus a separate finite session deadline apply. `stop_and_analyze` obtains
a final verified observation and publishes completed runs; cancellation terminates
the owned host ADB operation and discards the pending result. No global
BatteryStats reset, package launch/kill, settings mutation, remote file, port
forwarding, agent or persistent service is created.

Every full snapshot reads checkin, optional system-model energy, battery state
and optional device-wide thermal status. Missing channels stay unavailable.
Battery sensor temperature is not CPU/GPU temperature; thermal status does not
prove app-specific throttling, and modeled mAh is not measured hardware energy.
Host progress time is separate from device uptime-based observation duration.

For native checkin v9, accumulator identity comes from the global `bt` start-clock
field. Typed job/sensor/wakelock records already contain milliseconds. Partial
wakelocks are retained without double-counting their background subset; APK
service duration is not interpreted as alarm activity. Explicit legacy snapshot
imports retain their older marked projection. Unknown layouts are omitted with
diagnostics, never guessed. An intermediate missing or reset cumulative counter
stays unavailable for the entire run even if it later exceeds the baseline.

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
causality, saved sessions, full raw device/history evidence and real-device acceptance are
separate gates. `BatteryReport::from_live_snapshots` publishes actual capture into
the same report/workspace projection; imported documents cannot claim live
provenance by setting an untrusted field.

## Owned minimized evidence

A successful live outcome owns immutable private bytes for exactly three flat
entries: `manifest.json`, `analysis.json` and `snapshots.jsonl`. The manifest binds
the selected serial/PID/process, boot/start ticks, UID package set, installation
metadata, requested configuration and completed observation counts. The ledger
retains parsed selected-UID cumulative counters, battery state, device-wide thermal
status, device uptime and availability diagnostics for every actual observation.
It does not contain whole-device dumpsys text, other UID counters, raw history,
APK bytes or signatures. Its alarm durations are count-only placeholders as
explicitly diagnosed, not measured alarm execution time. Report duration remains
the sequential observation interval, not an atomic workload interval.

Budgets are 256 KiB per ledger record, 2 MiB for the complete ledger, 1 MiB for the
complete schema-v1 analysis and 32 KiB for the manifest. Budget/encoding failures
abort publication rather than silently truncating evidence. `BatteryReport::to_json`
preserves optional metadata, missing versus explicit-empty timer/window arrays,
measured zero, confidence and all runs; extracted `analysis.json` reimports through
the existing importer as a derived report, never as live provenance. ZIP bundle
import and saved-session persistence are not implemented by this adapter.

The UI retains the last successful report and its `Arc`-owned artifact across a
failed/cancelled/new pending capture or import. Export is available only when the
active report is live and its session identity matches that artifact. A successful
replacement capture atomically replaces both; imported/closed reports cannot
export an earlier capture's evidence. The explicit toolbar export writes a fixed
three-entry ZIP via the shared create-new 4 MiB exporter, with private permissions
and no overwriting. There is no automatic artifact persistence or device upload.

Tests are written but deliberately unexecuted until the complete rewrite is
implemented.
