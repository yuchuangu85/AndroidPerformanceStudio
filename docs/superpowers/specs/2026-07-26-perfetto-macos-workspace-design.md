# Perfetto MacOS Inspector Workspace Design

## Goal

Restyle the complete Perfetto Trace Analyzer workspace to match the compact MacOS desktop language used by Layout Inspector, without changing capture, device discovery, trace opening, diagnostics, or recent-session behavior.

## Scope

The redesign covers:

- The top navigation and device toolbar.
- Trace-template selection.
- Capture configuration.
- Capture actions and status.
- Initial-trace notices.
- Trace diagnostics.
- Recent sessions.

The `Perfetto Trace Analyzer` page title is removed. The home button and ADB path share the first toolbar row.

## Layout

### Top toolbar

Use a 29dp-high toolbar matching Layout Inspector:

- Compact home icon.
- Separator.
- `ADB` label and compact path field.
- Online-device selector.
- Refresh action.
- Connection-status dot and text.

The toolbar uses the active Material surface color, a bottom outline, 18dp horizontal padding, 11–12sp text, and compact controls.

### Main workspace

The area below the toolbar uses the active application background and an 8dp gutter. Its primary row contains three bordered panels:

1. **Trace Template** — fixed-width left panel with compact selectable rows.
2. **Configuration** — flexible center panel containing capture fields, actions, and status.
3. **Recent Sessions** — fixed-width right panel with compact session rows and Open/Delete actions.

When a trace is active, **Trace Diagnostics** appears as a bordered lower panel spanning the workspace width.

If the application is opened with an initial trace notice, the notice appears as a compact inline strip immediately below the toolbar rather than as a Material card.

## Visual language

- Reuse the unified application Material color roles already derived from the Layout Inspector palette.
- Use surface panels over the application background.
- Use 1dp outline borders and 4dp control/panel radii.
- Use 11–12sp desktop typography.
- Use 22–24dp compact buttons and fields.
- Replace large Material cards, radio controls, and headline typography with compact panel headers, selectable rows, and desktop controls.
- Use the primary-container role for selected template rows.
- Use semantic error, primary, surface, outline, and text roles so light and dark modes remain supported.

## Component boundaries

### `PerfettoWorkspace`

Owns:

- The toolbar.
- Workspace panel composition.
- Device discovery and selection.
- Recent sessions.
- Diagnostics orchestration.

It passes only capture configuration state and actions into the presentation layer.

### `PerfettoCapturePage`

Owns:

- Template selection.
- Capture configuration inputs.
- Start/stop actions.
- Capture progress and status.

ADB and device controls are removed from this component because they belong to the workspace toolbar.

### Compact UI primitives

Perfetto-local composables provide:

- Bordered panel and panel header.
- Compact text field.
- Compact action button.
- Compact template row.
- Status dot and status strip.

These remain local to Perfetto for this change; extracting a cross-profiler design system is outside scope.

## State and data flow

- `PerfettoWorkspace` continues to own `adbPath`, devices, and `selectedDeviceSerial`.
- The selected serial remains the capture target passed to `PerfettoCaptureSession`.
- `PerfettoCapturePage` continues to build `PerfettoCaptureConfig`.
- Capture state continues to flow from `PerfettoCaptureSession.state`.
- Completed traces continue to update recent files, persistent sessions, and diagnostics.
- No persistence format, command construction, or capture lifecycle behavior changes.

## Error handling

- Device-discovery and capture failures remain visible in the workspace.
- Failed capture status uses the error color and preserves the complete current error message.
- Empty device lists display a compact error state in the toolbar.
- Disabled capture actions continue to prevent capture without an online selected device or a valid custom config.

## Verification

- Add source-structure regression tests for:
  - Removal of the large page title.
  - ADB path placement in the same toolbar as the home icon.
  - Removal of ADB/device controls from `PerfettoCapturePage`.
  - Presence of the three primary panels and lower diagnostics panel.
  - Compact toolbar, control, border, and typography dimensions.
- Run Perfetto presentation and app tests.
- Run Perfetto `checkAll` for compilation, tests, ktlint, and detekt.
- Run root desktop-app checks.
- Perform a screenshot or manual desktop smoke check in light and dark modes when the application can be launched interactively.

## Non-goals

- Changing Perfetto capture commands or trace formats.
- Changing diagnostics queries.
- Redesigning other profiler pages.
- Introducing new dependencies or experimental Compose APIs.
- Extracting a global desktop component library.
