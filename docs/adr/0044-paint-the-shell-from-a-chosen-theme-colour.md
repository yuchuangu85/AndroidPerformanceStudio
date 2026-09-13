# Paint the shell from a chosen theme colour

Status: Accepted (2026-09-14).

## Context

The shell's accent — the primary button, switches, focus rings, the selected
hierarchy row, the search match tints — was the AppKit blue the macOS visual
language starts from (`#007AFF` in the light appearance, `#0A84FF` in the dark
one). Nothing in the application could change it, and the reference offers no
such setting either: its accent belongs to `ViewerColors` and is fixed.

## Decision

Settings → General offers a theme colour: the AppKit blue plus eight named
presets, stored and applied to the document root so the whole shell follows.

- `ACCENT_COLOR_PRESETS` in `@aps/settings` is the one description of the
  palette: key, colour, and the order the picker paints. `accentColor` joins
  `ApplicationUiSettings` and defaults to `default`, so an untouched install
  keeps the AppKit blue.
- The picker is a row of round swatches beside the row's label; the chosen one
  wears a ring in the accent it selects, so the row shows its own result.
- The renderer sets `data-accent` on `<html>` next to `data-theme`. The
  stylesheet declares one base colour per preset and derives the rest of the
  family — `--accent-strong`, `--accent-soft`, `--focus-ring`, and the
  hierarchy search trio — with `color-mix`, so a preset cannot drift from the
  tokens it feeds and `default` leaves the palette above byte for byte.
- The dark appearance lifts its derived values (72% towards white for the strong
  colour, 62% for the search highlight), the relationship the reference's
  `#0a84ff` / `#64b5ff` pair already has.

## Consequences

- Every surface that reads `--accent` follows: buttons, switches, focus rings,
  the selected hierarchy row, the search match tints and highlights, and the
  settings sidebar. The canvas' stored border colours are ARGB values of their
  own and are unaffected.
- Contrast is the preset's own problem: the palette is the one the request named,
  and text on the accent stays white (`--on-accent`).
- `packages/settings/src/model.test.ts` pins the palette, the fallback and the
  merge; `shell-layout.test.ts` pins the eight rules and the derived family;
  `GeneralSettings.test.ts` pins the nine swatches and the chosen ring.
