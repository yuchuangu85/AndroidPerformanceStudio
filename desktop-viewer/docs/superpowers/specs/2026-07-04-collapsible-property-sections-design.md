# Collapsible Property Sections Design

## Goal

Make every property section header in the right pane the same height as the
`PROPERTIES` pane header and allow each section to collapse independently.

## Interaction

- All property sections start expanded.
- Clicking anywhere on a section header toggles only that section.
- A drawn chevron points down while expanded and right while collapsed.
- Expansion preferences persist while selecting different layout nodes.

## Layout

- Section headers use `PanelHeaderLayout.HEIGHT_DP` (`29dp`), matching the
  primary pane title exactly.
- Section titles use the same `11sp` bold typography as primary pane titles.
- Existing risk colors and row styling remain unchanged.

## Testing

- A pure state model verifies default expansion and independent toggling.
- Existing presenter and desktop application tests must continue to pass.

