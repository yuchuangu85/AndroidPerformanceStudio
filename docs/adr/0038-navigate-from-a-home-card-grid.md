# Navigate the shell from a home card grid instead of a sidebar

Status: Accepted (2026-09-12). Supersedes the sidebar parts of ADR-0037.

## Context

ADR-0037 gave the Electron shell macOS window chrome and spent the window's
most valuable strip on a source list: fourteen destinations, a settings row, and
the vibrancy material behind them. The product it is ported from
(`desktop-viewer/desktop-app`) has no sidebar at all. Its home page
(`AppHomePage`) is the entry point — a title, a tagline, and a four-column grid
of feature cards — while each feature page carries a back-to-home button in its
toolbar (`HeaderToolbar`) and settings live in their own window.

The sidebar also contradicted the home page. Home drew nine cards; the sidebar
listed fourteen destinations, so the four it did not share were reachable only
from the list, and HOME was not really the app's entry point.

## Decision

The shell has no sidebar. The home page is its only navigation surface.

- Every destination except `HOME` gets a card: thirteen features, four to a
  row, in the reference's order, with GPU Inspector, Benchmark Regression, and
  Method Recording appended. The reference wires callbacks for those three but
  renders no card for them, and nothing else in that product opens them either.
  An unreachable feature is not parity, so the grid cannot copy that omission.
- A card is the feature's glyph and name on the first row and its description
  underneath. The descriptions are the reference home page's own strings,
  verbatim in both languages
  (`desktop-app/src/main/composeResources/values{,-zh}/strings.xml`), so the two
  apps describe a feature in the same words; AI Analysis, which has no card
  there, gets a line written in the same voice. `DESTINATION_SUMMARY_KEYS` is
  typed over every feature destination, so a new card cannot ship without copy.
- The window opens filling the work area and stays there. The reference
  maximizes only when a feature page opens and never restores the windowed size
  on the way back, so the shell now starts maximized: a launcher that resized on
  the way home would read as a state change the user did not ask for.
- The toolbar owns the chrome the sidebar used to carry: a back-to-home icon
  button whenever the current destination is not home, and the settings button
  that opens `SettingsPage`. The device chip and Refresh stay where they were.
- The environment readings the home page already carried — ADB, trace processor,
  settings origin — stay under the card grid. They are diagnostics rather than
  features, and a trace processor that failed to resolve should not be visible
  only from a settings page.
- macOS keeps `titleBarStyle: 'hiddenInset'` and the vibrancy material, but the
  material now shows through the toolbar strip instead of a sidebar: the toolbar
  reserves the traffic-light inset and keeps the translucent tint that used to
  be `--sidebar-tint`, renamed `--chrome-tint` because the settings page's own
  navigation shares it.

## Consequences

- `HOME_DESTINATIONS` grows from nine cards to thirteen, and
  `destinations.test.ts` asserts the set is exactly `DESTINATIONS` minus
  `HOME`, so a new destination cannot be added without a card.
- The layout contract is asserted against the stylesheet in
  `shell-layout.test.ts` (four cards per row, no sidebar rules left behind),
  which is why the desktop package now runs Vitest with `css: true`.
- Partial supersession of ADR-0037: the visual language, the AppKit controls,
  the token set and the one-scrolling-region rule are unchanged, but the shell
  no longer has a source list, so the toolbar is the only surface that shows the
  window's material.
- Bringing a sidebar back would be a new decision rather than a CSS revert: the
  window is a single content column now, and navigation is the home grid.
