# Expandable Simpleperf Settings Design

## Goal

Make Simpleperf settings easier to navigate from the unified settings window by
expanding Simpleperf into a tree of setting sections in the primary sidebar and
showing only the selected section's content in the right pane.

## Scope

- Replace the current Simpleperf sidebar entry with an expandable parent item.
- Show these child items beneath Simpleperf:
  - Sampling template
  - Capture configuration
  - Advanced parameters
  - Flame graph
  - Simpleperf engine
  - User guide
- Select the requested Simpleperf section when settings are opened from CPU
  Profiler.
- Remove the redundant Simpleperf-specific navigation column from the right
  content area.
- Preserve current setting values, persistence, device context, callbacks, user
  guide behavior, and General/Layout Inspector settings.

## Interaction Design

The primary settings sidebar remains the only navigation surface. General and
Layout Inspector stay as top-level items. Simpleperf becomes a disclosure row:

- Clicking its disclosure control expands or collapses its child sections.
- Opening settings on a Simpleperf section expands the parent automatically.
- Clicking a child selects Simpleperf and makes that section active.
- Collapsing the parent keeps the current child selection so expanding it again
  restores the same location.
- The selected child receives the existing selected-row treatment.

The right pane displays the title, subtitle, and controls for only the active
Simpleperf section. It remains vertically scrollable. When CPU Profiler device
context is unavailable, the existing informational message remains visible
above the selected section.

## Architecture

`UnifiedSettingsDialog` owns the expanded state and active
`CaptureSettingsSection`. The sidebar receives both values and emits page,
expansion, and section-selection events.

The Simpleperf presentation module exposes a content-only settings surface that
renders `SettingsPanel` without `SettingsNavigation`. The existing complete
surface and capture dialog keep using the shared panel implementation, avoiding
duplicated controls or persistence paths.

No setting data model or storage format changes are required.

## Error Handling

Persistence errors continue to appear at the top of the right pane. Missing
capture context continues to disable device-dependent controls through the
existing `enabled` and nullable setup inputs; non-device preferences remain
editable.

## Verification

- Add source/layout regression coverage for the expandable Simpleperf parent,
  child section list, automatic expansion, and content-only panel use.
- Verify that selecting each child maps to the corresponding
  `CaptureSettingsSection`.
- Run Simpleperf presentation tests and desktop application checks.
- Run formatting/diff validation and confirm no unrelated files change.

## Non-Goals

- Redesigning General or Layout Inspector settings.
- Changing Simpleperf setting values, defaults, persistence keys, or capture
  behavior.
- Adding dependencies or introducing a new navigation framework.
