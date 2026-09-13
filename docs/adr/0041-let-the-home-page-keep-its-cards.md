# Let the home page keep its cards

Status: Accepted (2026-09-13). Amends ADR-0039 for the home page only.

## Context

ADR-0039 ruled every page edge to edge: `.card` lost its border, radius, shadow
and margins, the metric cells became a table, and the home grid's feature cells
became hairlines. It named the home grid as part of the same decision.

The ruled home grid reads worse than the pages the decision was made for. Nine
to thirteen destinations are an index, not a screen of data: the eye needs the
cells to be separate objects before it needs them to be dense, and a hairline
grid reads as a table whose every cell is full of something. The heading band
also competes with the toolbar directly above it, which is the shell's only
other navigation.

## Decision

The home page is the one page that keeps the card form. Every other page stays
ruled exactly as ADR-0039 decided.

- `.content__body--home` carries the page gutter (`26px 32px 32px`) that
  `.content__body` no longer has, and `App.tsx` applies it through
  `contentBodyClass` — the same seam `.content__body--fill` uses, so a page
  that needs its own body keeps declaring it in one place.
- `.home` is held to a 1180px column again, and the heading and tagline are a
  centred column with margins instead of a band with a hairline under it.
- `.home__grid` returns to a 14px gap, and `.card--action` returns to a 1px
  `--separator` border, `--radius-tile` and `--tile-shadow`. Its hover and
  focus states go back to the accent border and the halo ring, because the card
  has a shape of its own to ring again.
- The tokens `--radius-tile` and `--tile-shadow` come back for these cards
  alone. `--radius-panel` stays gone: no page section is a panel again.

## Consequences

- `shell-layout.test.ts` pins both halves of the contract: page sections stay
  ruled (`.card`, `.content__body`, `.settings__section`), and the home grid
  is cards in a gapped grid on a padded body. Reverting either one fails the
  suite.
- The index and the work surface are now two deliberately different visual
  languages: what you navigate floats, what you work in is ruled.
- This discharges ADR-0039's own clause that going back to floating cards is a
  new decision rather than a CSS revert.
