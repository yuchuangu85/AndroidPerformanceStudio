# Live capture development — 2026-11-11

## Objective and working constraints

Fix the three findings in `build/device-functional-audit-20261110/REPORT.md`,
implement Startup/Battery/Network live capture, and connect Native Heap, ART
sampling and Bitmap capture to their actual analysis workspaces.

The user explicitly requests development only: **do not execute tests, device
probes, GUI acceptance or benchmarks**. Formatting, compilation and static source
review are development checks, not runtime acceptance. Preserve the dirty
worktree, existing revisions and unrelated concurrent work. No commit/push.

## Work breakdown and ownership

- [x] CPU: remote shell/PID framing, user-space app-scoped sampling, verified
  recorder completion, cancellation and bounded artifacts.
- [x] Inspector: correct negative-double formatting without duplicate signs.
- [x] Settings: correct the obsolete choice-chip border assertion for native
  radio controls; do not redesign the page.
- [x] Startup: explicit target approval, Cold/Warm/Hot launch collection,
  platform-derived TTID/TTFD and observed-state boundaries, result publication.
- [x] Battery: target/UID/boot-bound read-only snapshots, polling, stop/cancel,
  conservative cumulative differences and report publication. No global reset.
- [x] Network: existing APS Agent v2 transport, explicit consent, scoped
  forwarding, bounded incremental polling, privacy minimization and cleanup.
- [x] Native Heap: real heapprofd trace collection, bounded Trace Processor
  projections, import/export and dedicated page integration.
- [x] ART: real `com.android.art` heap sampling, bounded projections and page
  integration; no exact-allocation or retention claims from sampled evidence.
- [x] Bitmap: Android bitmap heap dump capture, isolated decoding/projection,
  image/summary page integration and export; distinguish ordinary HPROF findings.
- [x] Shared integration: title-bar device/target changes, operation cancellation,
  stale-result guards, paired localization, menus, docs and locked offline build.

Startup, Battery and Network are independent delegated implementation lanes.
The primary lane owns shared `import.rs`/`shell.rs`, resources, CPU fixes,
advanced Memory, integration and the final source/compile audit. Delegated lanes
must not run tests or mutate shared files.

## Completion evidence

Every requested capture action must call a real bounded transport, retain
device/process identity, cancel and clean only owned resources, publish actual
results into the existing feature page, and report unsupported/permission errors
without fabricated data. A button becoming enabled alone is not completion.

Keep this goal active until all items above are implemented and inspected.
Checked items record source implementation, not test execution or runtime
acceptance. Development completion does not constitute real-device or release
acceptance; that evidence is deliberately deferred by the user's no-tests
instruction.

## Integration review

- Native/ART and FrameTimeline now share the owned Perfetto recorder. It reserves
  a private remote directory, uses a unique detached-session key, caps duration
  and trace bytes, and checks stop completion before pulling/deleting. No
  `sh -c` argument joining, broad process kills or global tracing stop is used.
  Unknown stop/cleanup state retains exact paths and a recovery diagnostic.
- Native/ART additionally recheck the frozen boot ID and process start ticks
  before/during/after recording. The dataset owns the local recording throughout
  queries and export; stale/failed publication releases only owned files.
- Advanced memory views never export an older Java heap as their AI evidence.
  AI export for these views is explicitly unavailable instead of misattributing
  another dataset. Bitmap progress also distinguishes host cancellation from a
  device heap dump which may complete independently.
- The shared Memory view-menu regression source uses the real `Dashboard`
  variant rather than the nonexistent `Overview` variant. Native radio controls
  retain their existing theme; the obsolete settings choice-chip assertion is
  removed without changing the UI design.

## Explicit remaining boundaries

- No new tests, device probes, GUI acceptance, benchmarks, installation,
  commit or push were performed in this development follow-up. Regression-test
  sources are compiled only. The original device report is a historical
  pre-fix observation, not post-fix acceptance.
- Network's Kotlin/Android Agent cannot currently pass its normal Gradle build:
  AGP `com.android.library` 9.4.1 fails during plugin configuration with
  `KotlinAndroidTarget` / missing `com.android.build.gradle.api.BaseVariant`,
  before Kotlin source compilation. Both normal and stacktrace logs are under
  `desktop-viewer/build/network-agent-compile/`. No dependency versions or
  build manifests were changed to conceal that blocker.
- Native/ART traces with no sampled allocations remain explicitly empty;
  nonempty transport output is not evidence of profiling permission. Samples
  are not exact allocations, complete coverage, GC or retention proof.
- Bitmap images require the supported Android bitmap dump extension; ordinary
  HPROF bitmap instances are not promoted into image evidence. PNG extraction
  is isolated and bounded, and omitted images are shown.
- Battery's export is a minimized, UID-bound evidence bundle, not whole-device
  raw history. Historian, qualified baselines, saved-session/whole-bundle import
  and other separately unavailable capabilities are not silently enabled.

## Final development verification

Fresh main-lane logs are under
`build/live-capture-development-20261111/` at the repository root:

- `workspace-check.log`: `cargo check --locked --offline --workspace
  --all-targets` passed. This compiles regression sources; it does not run them.
- `build.log`: locked offline builds of `aps-app` and `aps-memory-worker` passed;
  neither binary was launched.
- `scoped-clippy.log`: all-targets/no-deps Clippy passed for `aps-app`,
  `aps-capture`, `aps-layout-inspector`, `aps-memory`, `aps-network`,
  `aps-battery`, `aps-startup`, `aps-perfetto` and `aps-ui-foundation`.
  Existing warnings remain; no lint-clean claim is made.
- Scoped formatting, paired resource keys/placeholders and `git diff --check`
  passed. `exit-codes.txt` and `summary.json` retain the exact evidence boundary.
- Full-workspace Clippy also ran but is not a pass: the unchanged
  `aps-benchmark/src/util.rs:135` test literal triggers `clippy::approx_constant`.
  The failure log is `workspace-clippy-preexisting-failure.log`.
- Full-workspace formatting is not a pass: unchanged `aps-adb/src/model.rs`
  and `aps-app/src/studio.rs` contain existing format differences. They were
  deliberately left untouched; `workspace-format.log` identifies both.

All implementation checklist items above are complete at source-development
scope. Device/GUI/test/release acceptance and the Android Agent build blocker
remain explicitly open; they are not converted into passing results.
