# aps-startup

Rust startup analysis supports bounded, explicit platform experiments and
actual existing input formats:

- `am start -W` text (`parse_am_start` / `open_am_start`), with explicit target
  package and requested mode supplied by the caller.
- A log scoped to a **single launch** (`with_event_log`) containing
  `wm_activity_launch_time`, `wm_fully_drawn_time`, `Displayed` or `Fully drawn`.
  Target component package matching is exact, not a substring match. Multiple
  matching observations are rejected rather than silently taking the latest.
- Existing `StartupCsvExporter` reports (`parse_csv` / `open_csv`), parsed by
  named columns, supporting doubled quotes and quoted commas/newlines. Additional
  compilation/environment/export fields remain in `imported_fields`.

Sessions retain run-level missing metrics, requested vs observed launch mode and
sources. `TotalTime`, `ThisTime` and `WaitTime` remain Activity Manager fields:
none substitutes for TTID. TTID requires Displayed evidence; TTFD requires Fully
drawn evidence, never fallback to TTID or an Agent-first-frame event. Missing
`LaunchState` stays observed-unknown even if requested mode is Cold/Warm/Hot.

Summaries report independent metric availability, mean and nearest-rank
percentiles; pages filter requested/observed mode or TTID availability and return
at most 512 runs. Input budgets are 64 MiB and 100,000 runs.

All supported values are launch-relative millisecond durations, not absolute
timestamps; they cannot reconstruct a cross-clock phase waterfall. CSV exports
lack complete target/session identity and do not prove comparable cohorts.
Compilation fields are preserved, not independently verified. Agent transport,
Perfetto phase correlation, Baseline Profile preparation, JSON report import and
statistical regression qualification remain separate work.

## Explicit platform experiments

`capture::capture_startup` is a blocking operation for a background executor,
called only after a target-specific Review / Confirm flow. It freezes the
approved device, package and configuration, verifies the initial selected app
process, and revalidates boot ID, current Android user, package UID, version,
installation timestamps and code path throughout the experiment.

- Cold force-stops only the approved package for its Android user, then launches
  its resolved MAIN / LAUNCHER Activity.
- Warm preserves the process, sends HOME, and starts a new cleared Activity task.
- Hot sends HOME and resumes the Activity. If the package has no process,
  Warm/Hot preparation launches it first; that launch is not a measured run.
- There are 1–20 runs, bounded launch/delay/event windows and a 15-minute total
  deadline. Cancellation stops subsequent commands and owned host processes,
  but does not undo already completed app lifecycle changes.
- No data/log reset, root, permission modification or compilation/Profile
  preparation is performed. Current compilation state is not verified.
- Preparation and measured commands must return `Status: ok`, `Complete`, and an
  explicit Activity in the approved package. Failed/incomplete launches abort
  publication instead of replacing an existing report with partial evidence.

Observed mode comes only from a recognized platform `LaunchState`, never from
the request or a PID heuristic. TTID/TTFD require new events for the exact
Android user and launched Activity, correlated by Activity token. Missing,
unmatched, malformed or ambiguous events stay unavailable; `TotalTime` is not a
substitute. This intentionally means Hot resumes often have no launch metrics.
No correlated phase waterfall is reconstructed.

Artifacts contain per-run Activity Manager output, only target-package event
lines, and a bounded experiment manifest in a private, exclusively owned
temporary directory. Keep `StartupCaptureArtifact` alive while using its paths;
Drop removes only that directory. Failed/cancelled runs do not replace the
previously published report. CSV metadata cannot declare the typed live origin.

The desktop current-page projection preserves its seven business columns and
adds an eighth typed `Evidence source` column. CSV/JSON exports and the bounded AI
projection therefore distinguish live platform, am-start text and imported CSV
without trusting imported source declarations. Filtering to an empty page does
not change the published session's source. These projections are not complete
artifact exports and do not add phase, compilation or cohort qualification.
Desktop exports select a persistent destination outside the entire owned
`startup-captures` tree; generation/cancellation checks reject stale pickers and
the evidence owner stays retained through the background write.

Test sources are provided but deliberately unexecuted until the complete rewrite
is implemented. Current development evidence is formatting and compilation
only; no startup capture, device mutation or real-device probe was run.
