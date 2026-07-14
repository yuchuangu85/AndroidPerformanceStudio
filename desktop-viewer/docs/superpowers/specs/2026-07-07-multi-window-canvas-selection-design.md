# Multi-Window Inspection and Canvas Selection Design

## Goal

Extend AndroidPerf Inspector so users can choose among every inspectable window
owned by the current application, identify views from the canvas, and keep the
hierarchy, details, findings, and canvas selection synchronized.

The same change also:

- moves **File** before **Actions** in the native menu bar;
- shows a simplified Android resource ID before each hierarchy view type;
- adds configurable colors for normal, hovered, and selected canvas bounds;
- persists the new color settings.

## Confirmed Product Decisions

- Only windows belonging to the current application process are listed.
- A view resource such as `com.example:id/title` is rendered as
  `id/title TextView`.
- Views without a resource ID render only their view type.
- Canvas hit testing prefers the topmost, most-specific visible descendant.
- Repeated clicks at the same point walk from that view toward its ancestors.
- Hover uses the same topmost-descendant rule and disappears when the pointer
  leaves the canvas.
- The implementation extends the capture protocol rather than maintaining a
  second desktop-only hierarchy source.

## Menu Layout

The native menu order becomes:

1. File
2. Actions
3. View

All existing menu items, groups, shortcuts, enabled states, and behavior remain
unchanged.

## Capture Protocol

`LayoutSnapshot` gains a backward-compatible window collection. Each captured
window contains:

- a stable window ID;
- a user-facing title;
- a best-effort window type;
- screen-coordinate bounds;
- an independent `UiNode` root.

The existing `root` remains the default window root so older archives and older
single-window code paths continue to decode. New node IDs are namespaced by
window ID so roots such as `root/0` cannot collide between windows.

New protocol fields use serialization defaults. A snapshot with no window
collection is projected as one legacy window whose root is the existing
`LayoutSnapshot.root`.

## Android Agent Collection

On API 29 and newer, the agent uses
[`WindowInspector.getGlobalWindowViews()`](https://developer.android.com/reference/android/view/inspector/WindowInspector)
on the main thread. Android documents this API as returning all window views
attached to the current process, which matches the confirmed application-only
scope.

For each attached root, the agent collects:

- a stable identity derived from process-local window/view identity;
- a title from available window layout parameters, with a root-class fallback;
- screen coordinates;
- the existing comprehensive view properties and child hierarchy.

On API 28 and older, the resumed Activity decor view remains the supported
single-window fallback. No hidden platform API or reflective WindowManager
access is introduced.

The desktop session uses a full-device screenshot for the canvas so separate
dialogs and popup windows are visible in the same coordinate space as their
roots. Captured display dimensions are normalized to the screenshot dimensions.

## ADB Fallback Collection

`dump-visible-window-views` parsing changes from selecting the largest matching
entry to returning every successfully decoded entry belonging to the current
package.

Malformed matching entries are skipped when at least one application window can
be decoded. If none can be decoded, the existing UI Automator fallback remains
available as a single legacy window.

Window archive entry names provide the fallback title and stable identity.
System windows and other applications are excluded.

## Inspector State

Inspector state tracks:

- all captured windows;
- the selected window ID;
- the last selected node ID for each window;
- the currently hovered node ID.

The active window determines:

- the hierarchy rows;
- selected node details;
- layout analysis and findings;
- app-only canvas crop;
- normal, hover, and selected bounds.

Refreshing attempts to retain the selected window and its selected node. If the
node disappears, the window root is selected. If the window disappears, the
default window is selected. Switching back to a retained window restores that
window's last valid selection.

Imported legacy archives load as one window. New archives preserve the complete
multi-window snapshot and continue to use the shared full-device screenshot.

## Header Window Selector

The package/status header becomes:

```text
com.example.app  [ MainActivity ▾ ]  |  LIVE
```

- Only current-application windows appear.
- A single window displays its title but disables expansion.
- With no snapshot, the selector displays the localized equivalent of
  `No available windows` and is disabled.
- A refresh retains the current selection whenever its window ID still exists.

## Hierarchy Labels

`TreeRowModel` exposes a separate optional resource label. The resource label is
reduced to the segment after the last `/` and prefixed with `id/`.

Examples:

```text
0-0  id/rootLayout  DecorView
1-0  id/title       TextView
```

When hierarchy indices are hidden:

```text
id/rootLayout  DecorView
id/title       TextView
```

Rows remain single-line and horizontally scrollable. Views without a resource
ID render only the class label.

## Canvas Hover and Selection

Pointer coordinates are inverse-mapped from the rendered preview into screenshot
coordinates using the current crop rectangle.

Hit testing:

1. excludes invisible, zero-alpha, and zero-area nodes;
2. intersects node bounds with ancestor clipping, `clipChildren`, and
   `clipBounds`;
3. considers every view, not only clickable views;
4. orders overlapping candidates by effective Z/elevation, drawing order, and
   depth;
5. chooses the topmost, most-specific descendant.

Hover updates only the hover border. It does not modify the selected node or
move the hierarchy.

Clicking selects the current hit and synchronizes hierarchy, details, and
findings. Repeated clicks at effectively the same pointer location walk upward
through that hit's ancestor chain. The click cycle resets after meaningful
pointer movement, a refresh, or a window change.

Bounds draw in this order:

1. normal visible-view bounds;
2. hovered-view bounds;
3. selected-view bounds.

The later layers always remain visually dominant.

## Hierarchy Reveal and Scroll

Canvas selection first expands every collapsed ancestor of the selected node.
After the row list is updated:

- if the selected row is already represented by a visible list item, the list
  does not move;
- otherwise the list scrolls only enough to reveal the row rather than forcing
  it to the top.

This preserves the user's current hierarchy position whenever possible.

## Configurable Canvas Colors

Settings gains three color controls:

1. Default view bounds
2. Hovered view bounds
3. Selected view bounds

Each control provides:

- a color preview;
- a selectable palette;
- hexadecimal color entry;
- a reset-to-default action.

Recommended defaults are light blue, amber, and red respectively. Colors are
stored as validated ARGB values in viewer preferences and are independent of the
light/dark theme. Invalid text input is not persisted and leaves the last valid
value active.

## Localization

All new user-facing strings are added to every existing locale, including:

- window selector labels and empty state;
- the three color setting labels;
- color picker, invalid value, and reset labels where required.

## Error Handling

- Failure to decode one application window does not discard other valid
  windows.
- An absent selected window falls back to the default window.
- An absent selected node falls back to the active window root.
- A click outside every effective node clears only the click cycle; it does not
  discard the current selection.
- Leaving the canvas clears hover immediately.
- Invalid color values never overwrite persisted valid values.

## Verification

Automated coverage will include:

- old and new protocol serialization;
- legacy archive import and multi-window archive round trips;
- API 29+ window-provider collection and pre-29 fallback;
- ADB parsing of multiple matching and non-matching windows;
- stable window selection and per-window node restoration;
- per-window analysis and details refresh;
- `id/resourceName ViewType` hierarchy projection;
- inverse coordinate mapping and effective clipping;
- overlapping Z/elevation/drawing-order hit testing;
- hover enter, movement, and exit behavior;
- repeated-click ancestor cycling;
- collapsed-ancestor reveal and no-scroll-when-visible policy;
- color validation, persistence, defaults, and reset behavior;
- native menu ordering.

Completion requires the focused tests plus the full project test suite, static
checks, and desktop packaging task to pass.
