# Profiler MacOS Workspace Unification Design

## Goal

Unify the outer layout and operation controls of the following workspaces with
the compact MacOS visual language already used by Layout Inspector:

- Memory Profiler
- Frame Profiler
- Startup Profiler
- Battery Profiler
- Network Profiler
- GPU Inspector
- Benchmark Regression

The redesign must preserve each tool's existing analysis content, capture
behavior, import/export flows, state transitions, and cross-tool navigation.

## Scope

The change covers workspace chrome and controls:

- home navigation
- primary and secondary toolbars
- buttons
- selectors
- compact text fields
- status and error messages
- background, spacing, dividers, borders, and corner radii

The change does not redesign charts, tables, timelines, result models, capture
protocols, storage, parsers, or controller APIs.

## Shared UI Architecture

Extend the existing shared desktop UI module that owns
`ProfilerHomeButton`. It will provide reusable composables for all seven
workspaces:

- `ProfilerMacOsToolbar`
- `ProfilerCompactButton`
- `ProfilerCompactSelector`
- `ProfilerCompactTextField`
- `ProfilerToolbarStatus`

The shared components use Material theme roles supplied by the unified desktop
application. They do not introduce a new color palette, dependency, theme, or
workspace DSL.

Each workspace remains responsible for its own state and callbacks. Shared
controls receive values, enabled state, labels, and callbacks without depending
on profiler-specific models.

## Visual Contract

### Primary toolbar

- Height: 32dp
- Horizontal padding: 6–8dp
- Control spacing: 4–6dp
- Background: `MaterialTheme.colorScheme.surface`
- Bottom separator: 1dp using the theme outline role
- Home button remains the first control
- Primary capture/import actions follow navigation controls
- Export actions remain grouped after primary actions
- Status and error summaries align toward the trailing edge when space permits

### Secondary configuration toolbar

Startup Profiler and Battery Profiler contain more configuration controls than
fit safely in one row. They use a second compact toolbar:

- Height: 28dp
- Background: `surfaceVariant`
- Separated from adjacent content by a 1dp divider
- Contains configuration selectors, checkboxes, and secondary status details

Other workspaces use a secondary row only when their existing controls cannot
fit without clipping at the desktop application's 1100px minimum width.

### Controls

- Button and selector height: 24dp
- Compact text-field height: 24dp
- Corner radius: 4dp
- Border: 1dp using theme outline roles
- Text size: 10–11sp
- Selected/primary state: `primaryContainer`
- Disabled content uses reduced alpha from `onSurfaceVariant`
- Controls must not inherit Material minimum touch sizes inside desktop
  toolbars

### Workspace body

- Background uses the application background role
- Existing profiler screen structure is preserved
- Workspace boundaries use 1dp dividers rather than elevated cards
- Existing charts, tables, timelines, and detail panes retain their current
  sizing and data behavior

## Workspace Mapping

### Memory Profiler

Use one 32dp toolbar containing home, device refresh, raw HPROF export,
converted HPROF export, and histogram CSV export. The existing
`MemoryProfilerScreen` remains unchanged.

### Frame Profiler

Use one 32dp toolbar containing home, device and process selectors, refresh,
capture, FrameStats import, and CSV/JSON exports. The operation message becomes
a compact trailing status. The existing frame analysis and Layout Inspector
handoff remain unchanged.

### Startup Profiler

Use a 32dp primary toolbar for home, device/application selection, refresh,
experiment control, and exports. Use a 28dp secondary toolbar for startup type,
compilation mode, warm-ups, measured runs, timeout, and related experiment
configuration.

### Battery Profiler

Use a 32dp primary toolbar for home, device/application selection, refresh, and
experiment control. Use a 28dp secondary configuration toolbar for capture
mode, duration, polling, run count, and automatic launch. Export and Battery
Historian actions use an additional compact action strip only when required to
avoid clipping.

### Network Profiler

Use one 32dp toolbar containing home, HAR import, compact device and package
fields, live capture, and JSON/HAR/CSV/raw-bundle exports. Capture messages and
errors use the shared compact status presentation.

### GPU Inspector

Use one 32dp toolbar containing home, refresh AGI, configure AGI, launch AGI,
and artifact import. Artifact verification and open behavior remain in the
existing result screen.

### Benchmark Regression

Use one 32dp toolbar containing home, current-result import, baseline import,
report export, and Perfetto trace navigation. The existing comparison screen
and regression threshold behavior remain unchanged.

## State and Error Handling

- Existing controller and local state remain the source of truth.
- Shared components do not launch coroutines or mutate domain state.
- Enabled and disabled rules remain identical to current behavior.
- Existing errors and operation messages are preserved and rendered through
  compact toolbar status text where applicable.
- File dialogs and confirmation flows remain unchanged.

## Testing

Add regression tests that verify:

- shared MacOS controls use the agreed dimensions and theme roles
- all seven workspaces use `ProfilerMacOsToolbar`
- raw Material `Button`, `OutlinedButton`, and large `OutlinedTextField`
  controls are removed from workspace toolbars
- Startup and Battery use secondary configuration toolbars
- business screen composables and callback wiring remain present

Verification includes:

- targeted shared UI and workspace layout tests
- affected module checks
- `:desktop-app:check`
- `git diff --check`

## Success Criteria

- All seven workspaces visibly share Layout Inspector's compact MacOS chrome.
- The primary toolbar is 32dp high.
- Controls remain readable and do not clip at the application's minimum width.
- Existing profiler workflows and navigation continue to work.
- No new dependencies or experimental APIs are introduced.
- All targeted and aggregate checks pass.
