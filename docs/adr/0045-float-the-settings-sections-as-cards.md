# Float the settings sections as cards

Status: Accepted (2026-09-14).

## Context

ADR-0039 ruled the application's pages: one surface per page, edge to edge, a
hairline between sections, and no card shape anywhere but the home grid. That
suits a page of data — the Layout Inspector's panes, the profiler reports — but
the settings pages are a stack of unrelated groups (General, Android SDK, Layout
Inspector, Simpleperf …), and as one continuous ruled surface the boundary
between two groups reads like a change of subject rather than a change of
section. The reference's settings dialog groups the same way, in boxes.

## Decision

Settings is the second exception to ADR-0039 — the home page is the first, see
ADR-0041 — and every `SettingsSection` floats as a card.

- `.settings__section` takes the home card's recipe: `1px solid
  var(--separator)`, `var(--radius-tile)` and `var(--tile-shadow)`, plus an 8px
  margin on all four sides. A card therefore keeps the same gutter from its
  neighbours and from the pane's edge, and no two sections share a hairline.
- The sidebar keeps its own surface and its right hairline: it is navigation,
  not a section.

## Consequences

- Every settings page follows at once — General, Environment, Layout Inspector,
  Simpleperf (its six nested sections included), AI and About — because they all
  render through `SettingsSection`.
- The panes stay one scroll container, with the cards' margins inside it.
- `shell-layout.test.ts` pins the card recipe and the 8px gutter, and goes on
  pinning the ruled look for every other page's `.card`.
