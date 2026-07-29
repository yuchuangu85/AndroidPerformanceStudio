# Visible View Bounds Overlay Design

## Goal

Let users display the bounds of every effectively visible view over the live
canvas while keeping the currently selected view unmistakably prominent.

## View Menu

Add one persisted checkbox item to the native **View / 视图** menu:

- **Show all visible view bounds / 显示全部可见视图边缘**

The option forms a new menu group below the existing hierarchy and findings
display options. A native separator appears before it. The option defaults to
disabled and is stored independently through the existing
`ViewDisplayOptionsStore`.

## Visible View Definition

A node contributes a general outline only when:

- its effective visibility is true;
- its effective alpha is greater than zero;
- its width and height are both positive;
- its bounds intersect the current canvas source rectangle.

Effective visibility and alpha include the node's ancestors. An invisible or
fully transparent ancestor prevents descendants from contributing outlines.
Invalid parent dimensions do not prune descendants because a child may still
render outside a non-clipping parent.

The traversal always starts from the complete captured `snapshot.root`.
Hierarchy filtering, hierarchy expansion, findings filtering, and hierarchy
index display do not affect canvas outlines.

## Overlay Geometry

Add a focused `ViewBoundsOverlay` helper in the Desktop module. It traverses
the protocol `UiNode` tree, applies effective visibility and alpha, excludes
the selected node from the general outline set, and delegates source clipping
and coordinate conversion to the existing `CanvasGeometry.mapBounds`.

The helper returns mapped `FloatRect` values only. It does not know Compose
colors, stroke widths, menu state, or drawing order.

Changing between app-only and full-device canvas modes recalculates the mapped
outlines against the new source rectangle. Nodes outside that rectangle are
omitted; partially intersecting nodes use the existing clipped rectangle.

## Rendering

The canvas drawing order is:

1. canvas background;
2. captured screenshot;
3. general visible-view outlines when the option is enabled;
4. selected-view outline.

General outlines use the approved visual treatment:

- `1dp` stroke;
- light cyan `#7DD3FC`;
- `62%` alpha.

The selected outline uses the existing theme error red with a strengthened
`3dp` stroke. It is drawn last and remains visible whether the general-outline
option is enabled or disabled.

The selected node is excluded from the general outline list so a cyan line
cannot soften or halo the red selection boundary. Selection retains its
existing behavior: if its bounds intersect the current canvas source, its red
outline is drawn even when the selected node is not effectively visible.

## State and Data Flow

Extend `ViewDisplayOption` with `SHOW_VISIBLE_VIEW_BOUNDS` and
`ViewDisplayOptions` with `showVisibleViewBounds`.

`LayoutInspectorMainPage` continues to own the loaded display options and persist the
complete value after each menu toggle. It passes the current option value into
`PreviewPane`. `PreviewPane` derives mapped general outlines from the current
snapshot, source rectangle, selected node ID, and option state, then renders
them before the existing selected overlay.

No Android Agent, capture protocol, application store, analysis engine, or
snapshot mutation is required.

## Localization

Add menu labels through the existing `ViewerStrings` mechanism:

- English: `Show all visible view bounds`
- Simplified Chinese: `显示全部可见视图边缘`

Changing the application language updates the native menu label through the
existing menu-model reconstruction.

## Empty and Invalid States

- No snapshot or no screenshot: preserve the existing waiting state and draw
  no outlines.
- Option disabled: do not traverse or map general outlines.
- No qualifying nodes: draw only the screenshot and any selected outline.
- Invalid or off-canvas node bounds: omit that general outline without
  affecting the rest of the tree.

## Testing

Automated tests cover:

- the new option defaults to disabled;
- each view option still toggles independently;
- the new option round-trips through persistence without changing other keys;
- the native View menu places the new item in a separate group;
- English and Simplified Chinese labels;
- visible nodes with positive alpha and dimensions produce mapped outlines;
- invisible, fully transparent, zero-area, and off-canvas nodes are omitted;
- invisible or transparent ancestors suppress descendant outlines;
- invalid-size parents do not suppress otherwise visible descendants;
- partially intersecting bounds are clipped;
- the selected node is excluded from general outlines;
- the selected outline remains independent of the new switch.

Verification runs focused Desktop tests, the complete Gradle test suite, and a
Desktop smoke test with both app-only and full-device canvas modes.

## Non-goals

- Editing node bounds.
- Selecting a node by clicking its outline.
- Assigning different colors by depth or view type.
- Adding outline labels, IDs, or dimensions to the canvas.
- Changing capture frequency or extending the Android Agent protocol.
