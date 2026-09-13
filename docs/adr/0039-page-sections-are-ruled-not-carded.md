# Rule page sections instead of floating them as rounded cards

Status: Accepted (2026-09-13). Supersedes the card-surface parts of ADR-0037.

## Context

ADR-0037 gave the shell AppKit's grouped-table look: every panel section is a
10px-radius group box with a hairline border and a shadow, floating on a page
with 16/20/28px of padding, and the home grid's feature cells are 12px tiles
with a shadow of their own.

That chrome is expensive on the screens that carry the most product meaning.
The Layout Inspector's three panes pay a 12px gap twice, every profiler page
starts 30–40px inside the window, and the box edges compete with the data
inside them. The reference product's dense screens, and the macOS inspectors
the visual language is copied from, put sections edge to edge and separate them
with the same translucent hairline the rest of the shell already uses.

## Decision

A page is a stack of sections, ruled and edge to edge. Nothing floats.

- `.content__body` loses its padding. `.card` loses its border, radius,
  shadow and margins and keeps only a 1px `--separator` hairline under itself;
  the text inside keeps its gutter through the section's own padding.
- Side-by-side sections use the same hairline turned vertical. The Layout
  Inspector's workspace is the one place that is not a fixed row: its three
  panes are a flex row whose side panes carry widths the separators drag
  (`PaneLayout`, ported with its test), and each separator draws the hairline
  down the middle of its 7px grab area. That page also stops growing with its
  content — `.content__body--fill` gives it the window's height, the workspace
  takes what the capture bar leaves, and the findings pane keeps the bottom
  edge, the way the reference's `weight(1f)` column does.
- The home grid is ruled the same way: `gap: 0`, each `.card--action` draws its
  right and bottom hairline, and every fourth cell closes its row. The heading
  becomes a band with a hairline under it instead of a centred column with
  margins.
- Metric cells become a ruled table: `.metrics` closes the last column and the
  last row, every `.metric` draws its own top and left hairline.
- Settings follow the pages: `.settings__panes` loses its padding and gap and
  `.settings__section` keeps only the bottom hairline. The navigation column
  already draws the vertical hairline between itself and the panes.
- Only things that genuinely float keep chrome: the canvas zoom control and the
  flame-graph tooltip keep `--panel-shadow`. Data views that are embedded in a
  section rather than being one — `.tree`, `.flame`, `.source-view`,
  `.timeline`, `.canvas__viewport` — keep their inset borders and radii because
  they are widgets, not page structure. Controls are untouched.
- A bare status line a panel renders as its own child (`.content__body > p`)
  carries the side gutter the sections carry.

## Consequences

- The tokens `--radius-panel`, `--radius-tile` and `--tile-shadow` are gone.
  `--panel-shadow` now draws floating overlays only.
- `shell-layout.test.ts` pins the contract against the stylesheet: the body has
  no padding, `.card`, `.settings__section` and `.card--action` carry no radius
  or shadow, `.card` keeps its `--separator` bottom hairline, and the home grid
  has no gap.
- Nesting is visible where a panel nests sections (memory deep analysis): the
  inner hairlines are inset by the outer section's padding. No panel depends on
  the old floating look for that.
- Going back to floating cards is a new decision rather than a CSS revert: the
  layout contract is asserted, and the shadows and radii no longer exist.
