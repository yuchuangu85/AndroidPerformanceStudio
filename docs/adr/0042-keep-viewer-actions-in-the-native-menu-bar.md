# Keep the viewer actions in the native menu bar

Status: Accepted (2026-09-13).

## Context

The reference's Layout Inspector owns a set of viewer actions: auto scan, node
stepping, collapse/expand, the three panel toggles, the layout-ID toggle, and
the five display options behind them. They live in its native menu bar
(`NativeViewerMenuBar`, built from `ViewerActionMenu`), and
`HeaderMenuPlacementTest` keeps them out of the page header on purpose.

The Electron rewrite ported the page but not the menu. The application ran on
Electron's default menu, so every one of those actions was missing — including
the keyboard routes the reference documents (⌘R, ⌘1/⌘2/⌘3, ⌘,).

## Decision

The actions live in the application menu bar, as they do in the reference.

- `src/shared/viewer-menu.ts` is the single description of the menu: the nine
  actions in the reference's order and groups, their accelerators, their checked
  and enabled rules, and the five View options. Main and the tests both read it.
- `src/main/menu.ts` builds the Electron menu from it and installs it. Settings
  is the ninth action and stays inside Actions on every platform, which is what
  the reference does: `ViewerActionMenu` keeps `OPEN_SETTINGS` in its one list
  with ⌘, beside it, and `NativeViewerMenuBar` renders that list whole
  (`NativeViewerMenuBarTest` pins the nine actions with `isMacOs = true`). The
  macOS application menu carries a Settings item of its own as well, the way
  AWT's `PreferencesHandler` gives the reference one.
- The renderer owns the state the menu reflects and answers the commands.
  `menu:updateState` carries the state up whenever any of it changes;
  `menu:command` carries a click back down. Electron menus are immutable, so a
  state change rebuilds the menu.
- The shell handles the commands that are not about the page (Settings); the
  rest reach the panel, which owns the panes, the selection and auto scan. While
  another destination is on screen the panel is not mounted, so the shell reports
  the actions as dormant rather than offering commands that would reach nothing.
- The Edit and Window roles stay: the pages are full of text fields.

## Consequences

- Every item is backed by state that already existed: auto scan runs the
  reference's one-capture-per-second loop (`CAPTURE_INTERVAL_MILLIS`), node
  stepping walks the tree's own visible order, and the View menu drives the
  stored display options the settings page already owns.
- The header row carries the same actions where the reference puts them as
  controls rather than menu items: the Auto device and Target selectors, the
  Refresh button (hidden while auto scan runs, as the reference hides it), the
  auto-scan switch, the metrics line, and the three panel-toggle glyphs. The menu
  and the row read and write one state, so ⌘2 and the bottom toggle cannot
  disagree.
- The reference's File menu — capture-archive import and export, plus its recent
  list — is not ported. The Electron capture store has no archive format to open
  or save, so those items would be dead entries; they wait for that feature.
- `viewer-menu.test.ts` pins the order, groups, accelerators, labels,
  checkmarks and enablement against the reference's own lists.
