# aps-startup

Read-only Rust startup analysis supports actual existing input formats:

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
Perfetto phase correlation, live experiment execution, Baseline Profile handling,
JSON report import and statistical regression qualification remain separate work.

Test sources are provided but deliberately unexecuted until the complete rewrite
is implemented. No startup capture, device mutation or real-device probe was run.
