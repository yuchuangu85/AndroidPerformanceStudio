# Header and Findings Resize Design

## Goal

Reduce vertical chrome so the live device canvas receives more space, while allowing users to resize the FINDINGS panel vertically when they need to inspect more or fewer issues.

## Header

- Reduce the internal `AgentPerf Desktop Viewer` header row from `58dp` to `29dp`.
- Keep the existing text, status, package, metrics, colors, and horizontal spacing unchanged.
- The native operating-system title bar is outside this change.

## Findings Panel

- Reduce the default FINDINGS height from `178dp` to `89dp`.
- Add a horizontal resize separator immediately above FINDINGS.
- Dragging the separator upward increases the FINDINGS height; dragging downward decreases it.
- Use a `7dp` interaction target with the existing `1dp` border color centered inside it.
- Show the vertical resize cursor while hovering over the separator.
- Clamp the FINDINGS height to:
  - minimum: `56dp`
  - maximum: `50%` of the content height available below the header
- Keep the resized height for the current application session only. Restarting the Desktop viewer restores `89dp`.
- Do not add a collapse button or allow complete collapse.

## Layout State

Add a focused `FindingsLayout` helper that owns the default, minimum, splitter, maximum-ratio, fit, and drag rules. `LayoutInspectorMainPage` remembers only the requested FINDINGS height and normalizes it whenever the window height changes.

The content below the header becomes one height-aware column:

1. the existing three-pane HIERARCHY/CANVAS/PROPERTIES row consumes remaining height;
2. the horizontal resize separator;
3. FINDINGS uses the normalized fixed height.

This preserves the existing horizontal pane resizing behavior and guarantees the three-pane area retains at least half of the available content height.

## Findings Content

- Keep the FINDINGS summary row fixed at the top of the panel.
- Render finding rows in a vertically scrollable list so entries remain reachable at the smaller default and minimum heights.
- Preserve the existing Chinese titles, node numbers, messages, badges, typography, and spacing.
- Keep the existing empty-state message.

## Testing

- Unit-test the `89dp` default and `56dp` minimum.
- Verify upward and downward drag direction.
- Verify height clamps at half of the available content height.
- Verify remembered height is normalized after the window shrinks.
- Run focused Desktop tests, all project tests, and the Desktop build.
- Launch the Desktop viewer and visually confirm the shorter header, smaller default FINDINGS panel, visible resize separator, and unchanged three-column layout.

## Non-goals

- Persisting the FINDINGS height across application restarts.
- Changing horizontal pane widths or their drag behavior.
- Changing FINDINGS analysis, text, numbering, filters, or severity counts.
- Changing the native window title bar.
