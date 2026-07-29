# Resizable Desktop Panes Design

## Goal

Allow users to resize the HIERARCHY, CANVAS, and PROPERTIES columns by dragging the two vertical separators. Preserve the current default layout and all existing capture, selection, and rendering behavior.

## Interaction

- The left separator changes the HIERARCHY width.
- The right separator changes the PROPERTIES width.
- CANVAS always consumes the remaining horizontal space.
- Each separator keeps its existing 1 dp visual line but exposes a 7 dp drag target.
- Hovering a separator shows the horizontal resize cursor.
- Width changes last only for the current application session.
- Restarting the Desktop application restores the current defaults: 300 dp for HIERARCHY and 300 dp for PROPERTIES.

## Constraints

- HIERARCHY cannot be narrower than 180 dp.
- PROPERTIES cannot be narrower than 240 dp.
- CANVAS keeps at least 320 dp while enough window width is available.
- Dragging stops at the nearest valid boundary instead of collapsing a pane.
- No persistence, collapse buttons, keyboard resizing, or new dependencies are included.

## Architecture

`LayoutInspectorMainPage` owns two remembered width values: the left pane width and the right pane width. The main content row measures its available width and delegates drag calculations to a small pure layout helper.

The helper accepts the current side widths, drag delta, available row width, splitter width, and pane minimums. It returns clamped widths that preserve both side-pane minimums and reserve the CANVAS minimum whenever the current window can satisfy all minimums.

The Compose separator remains responsible only for pointer input, hover cursor, and drawing the line. It reports horizontal drag deltas upward; it does not own layout state.

## Data Flow

1. `LayoutInspectorMainPage` initializes remembered widths to 300 dp each.
2. The content row reports its current available width.
3. Dragging the left separator adds the horizontal delta to the HIERARCHY width.
4. Dragging the right separator subtracts the horizontal delta from the PROPERTIES width.
5. The pure layout helper clamps the requested width against side-pane and CANVAS limits.
6. Compose recomposes the row; CANVAS receives the remaining width through `Modifier.weight(1f)`.

## Narrow Windows

If the window becomes narrower than the combined pane minimums, the side-pane minimums take priority and CANVAS receives the remaining non-negative space. Dragging cannot make the constrained state worse. Increasing the window width restores draggable space without resetting the user's session widths.

## Testing

Unit tests cover:

- left-separator drag direction;
- right-separator drag direction;
- HIERARCHY and PROPERTIES minimum widths;
- CANVAS minimum-width reservation;
- stable widths when a drag exceeds a boundary.

Desktop verification covers:

- both separators respond across their enlarged hit areas;
- the resize cursor appears on hover;
- the hierarchy, screenshot, selection overlay, and properties content remain functional;
- restarting restores the default widths.
