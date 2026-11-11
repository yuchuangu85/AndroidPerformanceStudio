# Android Performance Studio — Rust desktop

Independent Rust + GPUI Kit rewrite, alongside the existing Compose application.
The full scope and switch-over gates remain in
[`rust-gpui-kit-migration-plan.md`](../desktop-viewer/docs/design/rust-gpui-kit-migration-plan.md).
This directory is implementation in progress, **not** a replacement release.

## Development

- Rust **1.99.1**; `rust-toolchain.toml` pins rustup installations.
- GPUI Kit **0.9.0**, upstream backend, default assets. Do not add a second GPUI source.
- Upstream desktop requirements: macOS **15+**, Windows 10+ with MSVC/CMake,
  Linux Ubuntu 24.04 with a graphical session and Vulkan. These are dependencies'
  requirements, not a claim of application acceptance on all platforms.
- macOS needs Xcode command-line tools / SDK and a working Metal toolchain.

```sh
cd rust-desktop
cargo run --locked -p aps-app
```

The legacy application is unchanged: `cd desktop-viewer && ./gradlew :desktop-app:run`.
Rust settings and storage use their own application-data root and never migrate
or overwrite Compose settings automatically.

## Validation policy

The November 11 request to test all functionality supersedes the earlier
development-only test deferral. Run targeted regressions for each changed path
and the locked offline workspace suite after integration. Track remaining
Compose parity work in [FUNCTIONAL_PARITY.md](FUNCTIONAL_PARITY.md).
Compilation, domain tests, same-input parity, native interaction, device/provider
execution and packaging remain separate evidence classes.

See [migration progress](MIGRATION.md) for current implementation and remaining gates.

## Localization

Rust UI copy is independent of Compose and lives in
`crates/aps-ui-foundation/resources/values/*.json` (English) and
`crates/aps-ui-foundation/resources/values-zh/*.json` (Simplified Chinese).
The files are split by workspace. Add the same `snake_case` key to both locales;
resource values must be nonempty strings. No runtime resource path is required.

`aps-ui-foundation/build.rs` uses the existing strict `aps-json` parser, validates
the catalogs, and generates `TextKey` plus embedded static text into Cargo's
`OUT_DIR`. Missing/extra keys, duplicate keys (within or across files), invalid
JSON, identifier collisions, and mismatched named parameters fail the build.
Resource changes trigger regeneration. Generated code is not checked in.

```rust
use aps_ui_foundation::{Language, TextKey};

let title = Language::SimplifiedChinese.text(TextKey::FoundationSettings);
let version = Language::English.format(
    TextKey::ShellMigrationBuildVersion,
    &[("version", &"0.1.0")],
);
```

Templates use `{name}` placeholders and may reorder parameters between locales.
Arguments are inserted once and are never interpreted as templates. Translate
application-owned labels, summaries, and prompts only. Preserve source symbols,
SQL, paths, protocol identifiers, exports, imported diagnostics, and AI response
text. Language switches update input placeholders without replacing their values.
GPUI Kit built-in control strings and native application menus follow the same
language preference.

Resource contract tests live in
`crates/aps-ui-foundation/tests/localization.rs`. Per the validation policy above,
they can be compiled with `cargo check --locked --workspace --all-targets --offline`
without executing tests. Catalog validation itself also runs on normal builds.

## Profiler UI Scope

Data-import workspaces show **Import time** in their bottom status bar. The
monotonic wall-clock interval begins after file/folder selection and includes
conversion, parsing and initial page preparation, not time spent in the picker,
recording duration or the artifact's timestamps. Durations belong to the active
artifact/snapshot (including independent Native/ART/Bitmap datasets and Compose
compiler reports); queries and exports do not replace them. Failed/cancelled
imports retain the previous value while its data remains, and closing or
replacing it with unmeasured captured data clears the value. These are observed
import costs, not benchmark or capture performance claims.

Memory and Frame now use dedicated GPUI workspaces instead of the generic import
table. Memory follows the Compose Memory Profiler page: a command toolbar
(import, capture actions, Overview/Classes/Dominators/Diff, auxiliary views,
Mapping, AI analysis), snapshot tabs, evidence/warnings strip and status bar.
Classes has the primary filter bar, eight KPI cards, heap distribution,
resizable classifier tree (heap/package grouping, sorting, expansion, leak and
duplicate tints), instance list paging and the inline object inspector
(Fields/References, nearest GC root, drill-in navigation and array paging). Dominators has KPIs, distribution, filters, a lazy dominator tree and
the inspector; Diff compares class counts/shallow/retained between two dumps
without cross-dump identity; Leak Analysis renders HPROF heuristic findings.
Native Heap and ART Samples use bounded heapprofd trace projections in dedicated
pages; Bitmap image dumps use isolated extraction and image preview/export.
These artifacts remain separate from ordinary Java HPROF findings, and missing
samples/unsupported image dumps remain explicit. The typed `aps-memory` domain ports the Compose presenter
(including a bounded dependency-free regex subset) and runs one bounded
snapshot read plus typed instance/detail/dominator pages through the isolated
HPROF worker. Mapping import (ProGuard/R8) and seven HPROF export formats are
wired through the File menu with bounded, digest-checked writes. Frame
provides offline gfxinfo FrameStats summary cards, deadline filtering, paging,
window filtering, frame selection, a Canvas-rendered duration timeline with
bounded paging/zoom/hover, problem-frame navigation, trustworthy budget
provenance, stage breakdown details, warnings, evidence copy, and the same
bounded export lifecycle.

These pages are deliberately bounded by the Rust engines currently available:
Memory supports offline HPROF analysis and explicit Java heap capture from the
title-bar device/process target. Dump Heap runs bounded ADB commands, retains
the captured file for its snapshot tab, and loads it through the isolated
worker. The device must permit `am dumpheap` for that process; the application
does not root the device, restart a process or bypass Android permissions.
Cancellation or a changed target discards the pending result and preserves the
previous snapshot. Closing its tab removes the owned local capture files.
Native/ART and FrameTimeline recording use a shared, duration/size-bounded
Perfetto adapter with owned detached-session keys. Cancellation stops only the
owned session; an unconfirmed stop retains exact recovery paths. Native/ART
also recheck boot/process identity and retain their local recording while its
dataset is open. Bitmap capture requests Android's supported bitmap heap-dump
extension, then extracts PNGs in the bounded memory worker; host cancellation
does not claim to terminate Android's heap-dump operation. This development
follow-up does not establish FrameMetrics/JankStats, LeakCanary/Shark or complete
multi-source runtime acceptance. Unsupported actions stay visibly unavailable.
Snapshot comparison
uses only class aggregates from two independent dumps; equal object IDs never
imply shared identity. Live timeline/GC markers and native/bitmap metrics are
not inferred from an offline HPROF.

## Network workspace

Network now has a dedicated workspace following the Compose screen's command
bar, five summary metrics, coverage/activity regions, Requests/Domains tabs,
request table and scrollable request details. It uses English/Chinese resources,
theme tokens and width-aware stacked or side-by-side panes.

The primary controls now mirror Requests/Domains, outcome/count and elastic
search; HTTP status/method/sort extensions live in More filters with a visible
active-filter count. Table columns use the reference weights and details follow
its method/bytes summary, confidence legend, ten phase rows and four content
blocks. Only the detail title remains fixed; all evidence and copy controls scroll
together. The page retains usable list/detail space in short windows and uses
one natural-height page scroller beneath its fixed toolbar.

HAR status/method/search filters and duration/response-size sorting apply to the
whole imported session before 100-row pagination. Median/P95 come from valid
source-provided durations; missing values and unknown outcomes stay explicit.
Copy/save JSON and CSV export only the published minimized page. Selecting a
request does not authorize AI transport; the AI action opens existing preflight. Unknown
transfer sizes stay unknown; partial known bytes are labeled a subtotal, not the
session total. Copy-details reconstructs only the safe minimized field whitelist.
HAR hashing and parsing use the same validated file descriptor with cancellation
and before/after identity checks, including ordinary pathname replacement.

This is **not full Network feature parity**. The existing minimization policy
removes URLs/hosts, timestamps, headers and bodies. Domain aggregation, a real
activity timeline, per-phase timing and content sections therefore show explicit
unavailable states for HAR evidence. Live Android capture now connects the
existing APS Agent v2 after explicit consent, using owned ADB forwarding,
bounded polling, observed outcomes and nullable phase timing. It does not
recover raw URLs, headers or content to populate missing regions; domain
aggregation and HAR export remain unavailable. The Android Agent's normal
Gradle build is currently blocked before Kotlin compilation by an AGP
`KotlinAndroidTarget` / missing `BaseVariant` configuration failure. Native
visual, device, provider and packaged acceptance are separate deferred gates.

## Startup workspace

Startup now has its own reference-shaped workspace instead of the generic import
placeholder: a fixed command bar, Cold/Warm/Hot cards, scenario controls, four
metric cards, waterfall region, seven-column runs table, baseline boundary and
300dp evidence rail. It uses the current Compose implementation as the reference,
shared Studio typography/colors and actual-container responsive layout. Empty
states retain the complete page structure. Narrow views stack evidence below the
main content; the table scrolls horizontally without separating its header.

The adapters support Startup CSV and explicitly reviewed, bounded live
Cold/Warm/Hot experiments. The review identifies the target and any required
force-stop/launch preparation; the application does not change global compiler
or permission state. Mode cards filter requested
mode, while observed mode remains distinct and can be Unknown. Whole-cohort
statistics run off-thread; rows, summary, pagination, export and AI share one
published projection. Stable run-ID selection no longer reads/writes Frame state.
Failed/cancelled operations retain the previous publication. Missing TTID/TTFD
never use TotalTime or an Agent declaration as a fallback. MAD follows the same
nearest-rank median convention as the reference overview.

This is **not full Startup feature or pixel parity**. Extended experiment
configuration, Agent first-frame/phase intervals, comparable baseline statistics
and trace navigation/correlation need additional adapters. Their regions remain
visible with explicit unavailable explanations; no synthetic phase bars, device
identity, verification, root-cause or regression delta is inferred. Compiler,
Profile, device and trace fields in imported CSV are declarations, not
independently verified facts. Live runs retain their own bounded target and
platform-event evidence; missing/ambiguous launch events stay unassigned.
JSON/CSV exports contain the current published page only, not a
lossless report, and cannot be reimported as Startup CSV. Regression sources are
authored/compiled only. Native empty-state previews were inspected in wide and
stacked layouts; populated-report interaction, pixel matching, keyboard,
real-device, provider and package acceptance remain deferred separately.

## Battery live capture

Battery supports explicitly reviewed, read-only snapshots bound to the selected
UID, boot and package-installation/process identity. Stop publishes conservative
cumulative differences; cancel or stale results retain the previous report.
The optional minimized evidence ZIP contains the complete analysis JSON,
manifest and bounded parsed snapshots, not whole-device raw history. No global
BatteryStats reset is performed. Historian, qualified baseline comparison,
saved sessions and whole-bundle import remain separate unavailable capabilities.

See `LIVE_CAPTURE_DEVELOPMENT.md` for this development follow-up's implementation,
compile/static evidence and deliberate no-test/no-device/no-GUI boundaries.

## Dependency provenance

- <https://github.com/longbridge/gpui-kit/tree/v0.9.0>
- <https://gpui-kit.com/docs/installation>
- <https://gpui-kit.com/docs/getting-started>

`gpui-kit = "=0.9.0"` fixes its GPUI core/platform at `gpui-pre = 0.3.11`.
Its Apache-2.0 license does not replace third-party notices for transitive
dependencies or for bundled Android/Perfetto tooling; notices are a release gate.

## Settings workspace

Settings now follows the Compose six-section layout: General, Configuration,
Layout Inspector, Simpleperf, AI and About. System theme/language, eight accent
presets/custom HSV and hex colors, SDK path management, offline Inspector view
and border preferences, capture defaults and native chart tooltip mode are wired
to Rust behavior. Preferences use version 5 with older-version read compatibility; the
separate SDK file and all writes remain Rust-owned and atomic.

CPU Settings and the capture planner now share one live draft: five Compose
sampling templates, event, Frequency/Period, USER/KERNEL/BOTH and DWARF/Frame
Pointer/None feed the actual recorder. Prepared requests and confirmation values
are immutable; later edits require Inspect again. Valid Hz/duration defaults are
persisted, including a mode-only switch to Frequency; period counts never become
Hz preferences. Invalid inputs remain visible rather than silently clamping.
CPU toolbar/Tools actions and its capture shortcut now open this explicit planner
without starting device recording. Current bounds remain 1..300 integer seconds
and 1..4000 Hz; manual duration/Stop-and-save and event discovery are unfinished.

This is not full settings parity: Legacy/Firefox engines, device-dependent
capture configuration, live capture archive limits, extension/agent/skill
package management and sandboxed script execution still need Rust adapters and
remain visibly disabled. AI provider connections, local Codex/Claude config
import, model discovery, built-in agent selection, report format/language and
retention controls are wired to the Rust AI runtime. General scaling covers base
fonts and rem-based controls, not every explicit-pixel profiler element. Current
automated evidence is recorded in the continuation report; native visual matching
and device/provider/package acceptance remain separate open gates.

## AI Analysis workspace

The AI route is now the Compose-shaped conversation workspace. It has the
260 dp conversations sidebar (search, Recent/Archived, pin/rename/branch/
archive/copy-id/delete with confirmations), the centered chat column with empty
state, user bubbles, safe Markdown answers (headings, lists, quotes, tables,
code fences with language/wrap/copy), findings with evidence/source chips,
verified-report and model rows, retry states, the preflight approval card
(provider/model, evidence and source counts and bytes, planned calls, report
format/language, prior dialogue, SHA-256 and permission list, Approve/Cancel),
an activity toggle, and the composer with queued questions, bound-context row,
include-source consent, a model picker with loading/empty/error/refresh states
and a send/stop button. Enter sends, Shift+Enter adds a newline, Up recalls the
last question, two Escapes within 500 ms stop the run, Cmd/Ctrl+N/Ctrl+K are
wired. The DSH chat colors and type scale are scoped to this page only.

The Rust AI engine under `crates/aps-ai` owns saved preferences, a Keychain-
backed connection credential store, a bounded SQLite conversation store
(pin/rename/archive/delete/fork, restore without replay, retention), the
non-streaming Responses API client for Codex/Custom connections, the no-tools
Claude CLI provider, local Codex/Claude config import, model discovery with
cache, SHA-256-addressed HTML/Markdown report artifacts, and the freeze ->
explicit approve -> execute -> validate -> persist pipeline. Profiler AI entries
still publish their existing bounded `AnalysisRequest` and open this workspace
bound to that context.

Explicit boundaries: this is a route in the single application window, not a
second native AI window; the Compose Agent graph, sub-agents, Skills and
sandboxed scripts are not ported (built-in agent descriptors are static and
script execution is never granted); Claude runs through the CLI; Keychain access
uses the bounded `security` helper; the activity panel is session-local; answers
are not rendered token-by-token; and no live provider, interactive approval,
IME, cross-platform or packaged acceptance was exercised here. See the
2026-11-08 AI entry in MIGRATION.md for the evidence and fixtures.

## Home dashboard

Home now follows the current Compose dashboard: two real metrics, four shortcut
cards, eleven ordered module entries, a five-column recent table and a local
date/device rail. It uses the shared theme/typography and actual-container
responsive breakpoints. Trace/HPROF picker cancellation keeps the current page;
Start Capture and Run Startup Test navigate without automatically running work.
The native File menu exposes Home import actions only while Home handles them.

Device facts come from the existing shared ADB discovery and distinguish loading,
zero devices and errors. Recent history now interleaves Layout, CPU, Trace and
Memory sources in Compose source order, with at most six usable entries and
bounded per-source scans. Successful imports record paths in separate Rust-owned
indexes; captured Trace sessions also use the existing read-only session catalog.
Corrupt sources are isolated and missing/nonregular artifacts are skipped without
repairing the index. Unknown package/capture time remain unavailable; import time
and file mtime never impersonate capture time. Reopening a captured Trace keeps
its known timestamp even after its path enters the MRU list.

Successful Java heap captures retain a digest-verified private history copy only
after current-generation publication. Copy and index registration form one
background transaction; registration failure removes only a newly created copy,
not existing deduplicated evidence. Scratch ownership and cleanup are unchanged.
Clearing history does not delete retained evidence. Inspector now has an async
ten-file recent menu with full-path labels for same-name archives and nondestructive
Clear Menu. Shell mutations run in UI event order, so earlier delayed records
cannot restore cleared history. CPU/Trace/Battery recent-menu separation, Memory
saved-session menus, retention quotas and crash-orphan recovery remain open work.

Passive Home discovery does not parse profiles, initialize storage or migrate
Compose indexes. Opening delegates to each feature importer; CPU retains its
saved-session/digest checks. This is not pixel or complete native interaction
acceptance. On November 11, current native imports published real CPU/HPROF/Trace
and a Layout fixture into isolated history, but the locked macOS session blocked
screenshots and click/reopen acceptance. See the current continuation report.

## Source Workspaces

Source Workspaces now follows the Compose page: a fixed command bar with
"+ Local", "AI Settings" and a selection-gated AI analysis action; a three-pane
browser (workspace cards / file tree / source) with draggable 7 px splitters and
protected 220/220/360 minimum pane widths; a live phase/progress card with
Sync, AI source-upload consent and Remove; a 28 px file search and a virtualized,
directory-first tree that starts fully collapsed, expands a selected file's
ancestors and exports Compose's 20 px row / 14 px indent metrics with
language-colored icons; and the read-only One Dark viewer with line-number
gutter, current-line band, token colors, in-view search (n/m, wrap-around),
horizontal/vertical scrolling, drag selection and copy.

The data layer mirrors the Compose source-workspace module: explicit local
roots, Git revision plus dirty-content digest, a content-addressed local cache,
structural Kotlin/Java/XML/C/C++ declaration index, evidence resolution with
Compose confidence/reason strings, and a Rust-owned SQLite schema for
workspaces, snapshots, files, symbols and candidates. Reads are verified against
the immutable content hash and marked stale when the working tree changed.
Source text is cached only under the Rust application-data directory; nothing is
uploaded.

The AI preflight receives metadata-only selection evidence (snapshot identity,
content hash, language, content state, size and line bounds). A candidate is
included only while that workspace's upload grant is enabled, and the existing
AI page still requires its separate explicit source consent.

Only the Local provider is visible, exactly like the Compose page (GitHub/AOSP
controls are hidden there). Remote retrieval, Layout Inspector Compose-source
candidate dialogs and archived-source rebinding are not connected yet; a
persisted remote workspace fails refresh with an explicit unavailable error.
CPU symbol navigation and AI-result candidate opening are wired for non-Weak
candidates. The full Rust test suite now passes (392 tests, 0 failures) and a
native interaction pass with a seeded fixture exercised selection, Sync, AI
upload consent, Copy location, tree/search interactions, code search, drag
selection/copy, stale detection, remove, AI settings, AI preflight,
symbol-candidate navigation and add-local (screenshots under
`build/source-workspace/`). Pixel comparison, packaging and provider requests
were not executed, so this remains functional/smoke evidence.

## Battery workspace

Battery now uses its own reference-shaped UI: a compact toolbar, searchable
session rail, adaptive metrics, six-column run table, snapshot/history panels,
eight resource tabs, searchable paged evidence with inspector/copy, thermal
windows and diagnostics. It reuses the Studio theme and typography. Runs,
resources, windows and diagnostics are bounded/paged, not silently truncated.
Native File import and toolbar import use the existing generation-bound adapter.
JSON/CSV export is explicitly the published runs page, not a session archive.

Imported schema-v1 reports preserve available source identity/clock metadata,
timer confidence and thermal windows. Missing fields remain unknown; no charge
curve, history samples, request events or compatible baseline is inferred from
totals. Energy identities are kept separate and temperatures can be negative.

Only the current imported report appears in the session rail. Persistent saved
Battery sessions, live experiments, app launch, global BatteryStats reset,
Historian/bugreports and raw bundles still need Rust adapters; their controls are
visibly disabled. Configuration is a read-only reference dialog, not a set of
ineffective saved preferences. Format, compilation and static checks do not prove
pixel parity or real capture behavior. Tests, native visual/keyboard interaction,
provider/device and packaged acceptance remain deferred separately.


## GPU Analysis Hub

GPU Inspector now follows the Compose Analysis Hub: a compact action strip,
four summary cards, a searchable artifact rail, device graphics context,
Overview/Artifacts/Related Evidence/Tool Capability tabs, and capability/open-with
rails. It uses the actual content width: 1100dp selects three columns, 900dp
selects four summary cards rather than two rows; narrower layouts stack regions.
Shared typography, surfaces, borders and bilingual resources are retained.

Explicit actions import content-identified artifacts, verify SHA-256 and size,
relocate matching copies, reveal verified files, route Perfetto artifacts to
Trace Analyzer, and probe/launch a selected local AGI executable. AGI launch is
GUI-only and does not claim to open a selected artifact or infer CLI arguments.
Shared device selection can be explicitly refreshed for graphics properties.
AI preflight receives minimized artifact identity rather than local paths.

This is not complete backend or pixel parity. The in-memory catalog is session
local; device/package/capture/API metadata and related-artifact matching are not
connected. File modification time is never presented as capture time. AGI frame
metrics, shaders, draw calls and overdraw remain explicitly unavailable. Tests
were authored/compiled but not executed under the rewrite policy. Compilation,
a native dark Chinese empty-state screenshot and process startup are separate
from comprehensive GUI, device, real AGI, persistence and packaged acceptance.
