# Firefox Flame Graph Visual Parity Design

**Status:** Approved design  
**Date:** 2026-07-17  
**Scope:** `desktop-viewer/simpleperf-viewer` Flame Graph tab only

## Goal

Make the native Compose Flame Graph tab visually match Firefox Profiler while preserving the existing Android profile model, analysis semantics, performance characteristics, keyboard support, and accessibility surface.

The result must look and behave like the Firefox Profiler flame graph in both light and dark themes. Other report tabs remain on the existing Material 3 presentation.

## Reference baselines

- Visual baseline: Firefox Profiler commit [`faaf1a14affd3c6d8b7342188371079b999abf5b`](https://github.com/firefox-devtools/profiler/tree/faaf1a14affd3c6d8b7342188371079b999abf5b).
- Functional compatibility baseline: Firefox Profiler commit `9dd90d380ee711f209c4dcd89beec244eb6d3654`.
- Authoritative visual sources:
  - [`src/components/flame-graph/Canvas.tsx`](https://github.com/firefox-devtools/profiler/blob/faaf1a14affd3c6d8b7342188371079b999abf5b/src/components/flame-graph/Canvas.tsx)
  - [`src/components/flame-graph/FlameGraph.tsx`](https://github.com/firefox-devtools/profiler/blob/faaf1a14affd3c6d8b7342188371079b999abf5b/src/components/flame-graph/FlameGraph.tsx)
  - [`src/components/flame-graph/FlameGraph.css`](https://github.com/firefox-devtools/profiler/blob/faaf1a14affd3c6d8b7342188371079b999abf5b/src/components/flame-graph/FlameGraph.css)
  - [`src/components/flame-graph/Canvas.css`](https://github.com/firefox-devtools/profiler/blob/faaf1a14affd3c6d8b7342188371079b999abf5b/src/components/flame-graph/Canvas.css)
  - [`src/utils/colors.ts`](https://github.com/firefox-devtools/profiler/blob/faaf1a14affd3c6d8b7342188371079b999abf5b/src/utils/colors.ts)

The visual baseline is pinned independently so later Firefox changes do not silently alter acceptance criteria.

## Scope

### Included

- Flame Graph toolbar and transform navigator.
- Flame graph viewport, background, borders, scrolling, and direction behavior.
- Node geometry, Photon colors, text, selection, hover, and context states.
- Tooltip, context menu, empty states, and frame-details bottom box.
- Light and dark theme parity.
- Golden screenshots and rule-level visual tests.

### Excluded

- Restyling Overview, Timeline, Top Functions, Call Tree, Diagnostics, or the report-level tab strip.
- Embedding Firefox Profiler, React, a browser engine, or a WebView.
- Replacing Android terminology with Gecko-specific terminology.
- Changing call-stack projection, filtering, transformations, stable node identity, or details resolution.
- Adding runtime dependencies.

## Chosen approach

Implement a Compose-native visual port. The existing Kotlin analysis and application state remain authoritative; the presentation and visualization modules reproduce Firefox layout and drawing rules.

Rejected alternatives:

1. Embedding the Firefox web UI would provide direct rendering parity but introduce a browser runtime, JavaScript bridge, packaging cost, and duplicated interaction state.
2. Replacing only node colors would leave the Material toolbar, tooltip, menus, spacing, and details presentation visibly inconsistent with Firefox.

## Architecture

### Visual contract

Introduce a `FirefoxFlameGraphStyle` contract in the visualization boundary. It owns immutable, resolved values for:

- light and dark Photon colors;
- canvas background and foreground;
- category selected and unselected fills;
- selected text colors;
- row height, text offsets, font size, and inter-node gap;
- viewport border and overlay colors;
- tooltip, menu, and bottom-box surface tokens.

Compose theme access happens once in the presentation layer. The Canvas receives a fully resolved style and never reads `MaterialTheme` during node drawing.

### Components

`FlameGraphPanel` becomes an assembler of focused components:

1. `FirefoxFlameGraphToolbar`
   - Compact direction and implementation controls.
   - Android labels: `All`, `Native`, `Managed`, `Kernel`, and `Unknown`.
   - Right-aligned stack search field.
2. `FirefoxTransformNavigator`
   - Thin breadcrumb-like transform row.
   - Undo and clear affordances without Material chips.
3. `FirefoxFlameGraphViewport`
   - Bordered, theme-aware surface that fills remaining tab space.
   - Owns vertical scroll state and supplies an explicit non-zero Canvas size.
4. `FlameGraphCanvas`
   - Retains one batched Canvas and viewport virtualization.
   - Implements the pinned Firefox pixel geometry and drawing rules.
5. `FirefoxFlameGraphTooltip`
   - Dense function, resource, category, weight, percentage, and sample details.
6. `FirefoxFlameGraphContextMenu`
   - Dense menu rows, aligned shortcuts, subtle border, small radius, and shadow.
7. `FirefoxFrameDetailsBottomBox`
   - Restyles source, disassembly, and symbol fallback content as the graph bottom box.
8. `FirefoxFlameGraphEmptyState`
   - Keeps existing reason-specific recovery actions in a compact Firefox surface.

No component owns a second copy of query or selection state.

## Visual specification

### Page structure

- The toolbar uses compact desktop controls rather than Material `FilterChip` components.
- The transform navigator sits directly below the control row.
- A one-pixel border separates the viewport from controls and adjacent content.
- The viewport fills remaining available height, with a minimum height of 220 dp.
- Forward graphs start at the bottom; inverted graphs start at the top.
- Only vertical graph scrolling is supported.

### Canvas and node geometry

- Logical row height: 16 CSS-equivalent pixels.
- Label font: 10 px sans-serif.
- Label start offset: 3 px.
- Label baseline/top rule follows the pinned Firefox `TEXT_OFFSET_TOP = 11` behavior.
- Node left and right edges snap to multiples of two device pixels.
- The right edge is shifted by 0.8 device pixels, producing the Firefox translucent gap.
- The row bottom reserves one device pixel so adjacent rows remain visually distinct.
- Nodes that snap to a non-positive width are not drawn.
- Labels render only when fitted text can be produced inside the available width.
- Canvas drawing operates in device-pixel-aware coordinates so high-density displays preserve the same logical geometry.

### Color system

- Canvas background: Firefox foreground surface (`#ffffff` light, `#18181a` dark).
- Default label foreground: black in light theme and Photon `GREY_20` in dark theme.
- Category colors follow the pinned Firefox Photon map.
- Ordinary nodes use the category's unselected translucent fill.
- Selected, hovered, and context nodes use the category's selected opaque fill.
- Highlighted text uses the category's pinned selected-text color.
- Android categories map deterministically to Firefox color names:
  - system/runtime -> blue;
  - kernel -> purple;
  - native -> yellow;
  - managed -> lightblue;
  - graphics -> green;
  - I/O -> orange;
  - network -> magenta;
  - unknown/other -> gray.
- Focus and context state retain a non-color cue through outline or semantics.

### Toolbar and navigator

- Controls use square/low-radius desktop shapes, thin borders, and compact heights.
- Direction is presented as `Forward` and `Inverted`.
- Implementation filters preserve Android-specific categories.
- Search uses Firefox-style placeholder text and a compact clear action.
- Applied transforms appear as navigator segments, not Material pills.
- Undo and clear remain available from both the toolbar and keyboard shortcuts.

### Tooltip, context menu, and details

- Tooltip uses a dense rectangular surface with a small radius, one-pixel border, and restrained shadow.
- Tooltip content groups identity, category, inclusive weight, percentage, sample count, and thread count.
- Context menu uses dense rows with shortcuts aligned to the trailing edge.
- The details view is attached below the viewport as a bottom box, not presented as a Material card.
- Source, disassembly, and symbol fallback content retain their existing semantics and recovery messages.

### Empty and failure states

- Existing `FlameGraphEmptyReason` values and recovery actions remain authoritative.
- Empty content uses the Firefox viewport background, compact typography, and a small bordered action.
- Projection failure retains Retry.
- Query and transform failures retain their specific clear, undo, or reset recovery.
- Empty states must be readable in both themes and never rely on color alone.

## State and data flow

1. `ReportController` publishes `ReportState` and `FlameGraphSnapshot`.
2. `FlameGraphPanel` resolves a light or dark `FirefoxFlameGraphStyle`.
3. The toolbar dispatches existing `ReportActions`; it does not mutate local query state beyond the existing debounced search draft behavior.
4. `FlameGraphLayout` materializes only visible rows and nodes from the snapshot and viewport.
5. `FlameGraphCanvas` draws those nodes using resolved Firefox geometry and style.
6. Hover, selection, context, navigation, and details intents continue through the existing presenter and controller actions.
7. Accessibility semantics expose visible nodes and the selected offscreen node without changing painted layout.

## Performance constraints

- Preserve a single Canvas draw pass rather than composing one visual component per node.
- Preserve row virtualization and overscan.
- Cache resolved category styles and fitted labels by stable inputs.
- Avoid allocating colors, text styles, or category mappings inside the inner node draw loop.
- Limit semantics composition to visible nodes plus the selected offscreen node.
- Keep million-sample layout, hover, and selection performance within the existing P0 evidence thresholds.
- Theme switching may invalidate the style cache but must not rebuild call-stack projection data.

## Accessibility

- Keyboard navigation, Enter details, copy, context actions, and transform shortcuts remain supported.
- Virtual semantic nodes remain synchronized with visible rectangles.
- Selected, hovered, and context states have semantic descriptions and non-color visual cues.
- Light and dark text contrast is checked at the final rendered fill, including translucent fills over the canvas background.

## Testing and verification

### Unit tests

- Photon token values and light/dark resolution.
- Android category-to-Firefox-color mapping.
- Selected versus unselected alpha and foreground behavior.
- Device-pixel snapping, 0.8-pixel gap, one-pixel row separation, and narrow-node omission.
- Label fitting thresholds and fitted-text behavior.

### Compose UI tests

- Compact toolbar controls dispatch the existing actions.
- Viewport receives a non-zero size and fills remaining page space.
- Forward and inverted graphs attach to the correct edge.
- Tooltip, context menu, empty state, and bottom box expose expected content and geometry.
- Accessibility actions remain operable without pointer input.

### Visual regression

- Capture deterministic light and dark golden screenshots from the compatibility fixture.
- Review each iteration against the pinned Firefox reference for:
  - overall hierarchy and spacing;
  - toolbar density;
  - viewport border/background;
  - node colors, opacity, gaps, and text placement;
  - tooltip, menu, and bottom-box surfaces.
- Persist the accepted golden images and reference metadata in the repository.

### Project verification

- Run focused visualization and presentation tests during implementation.
- Run ktlint and detekt for every changed module.
- Run `./gradlew checkAll --rerun-tasks` before completion.
- Run `./gradlew :simpleperf-test-fixtures:runFlameGraphPerformancePoc` and compare with the checked P0 baseline.
- Run `git diff --check`.

## Acceptance criteria

1. The Flame Graph tab is recognizably Firefox Profiler rather than Material 3 in both light and dark themes.
2. The toolbar, transform navigator, viewport, Canvas, tooltip, menu, empty states, and details bottom box share one Firefox visual contract.
3. Node geometry matches the pinned Firefox constants and pixel-snapping behavior.
4. Category colors and highlighted/unhighlighted opacity match the pinned Photon mapping after Android category translation.
5. Existing query, transformation, navigation, details, keyboard, and accessibility behavior remains functional.
6. The graph fills available tab space without regressing to a zero-sized Canvas.
7. Golden screenshots, full tests, static checks, and the million-sample performance POC pass.
8. No runtime dependency or browser surface is added.

## Known platform validation gap

The implementation can be developed and visually reviewed on macOS. Windows and Linux golden rendering must either use platform-tolerant assertions or be validated on their clean runners before declaring cross-platform pixel parity.
