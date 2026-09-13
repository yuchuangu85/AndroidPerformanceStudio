# Move the environment readings into a Settings page

Status: Accepted (2026-09-13). Supersedes the "environment readings stay under
the card grid" bullet of ADR-0038.

## Context

ADR-0038 kept the ADB, trace-processor and settings-origin readings in cards
under the home grid, so that a trace processor which failed to resolve would
not be visible only from a settings page. In practice those cards were rendered
under *every* destination page, not only home: they pushed the content the user
opened the page for upward on every visit, and nothing on a destination page
acts on them. The shell toolbar already reports ADB state as a chip, with the
Refresh button next to it.

## Decision

The two cards become their own settings page, `ENVIRONMENT` ("环境" /
"Environment"), reached from the settings navigation between General and Layout
Inspector.

- The page reports the ADB that was found and its source, the attached devices,
  the bundled Trace Processor's version and path, and where the settings came
  from. It carries its own Refresh button, because the shell's toolbar — and
  with it the shell's Refresh — is hidden while Settings is open.
- The shell body keeps no environment cards. The toolbar chip and Refresh
  button stay, so ADB state is still visible on every page.
- `SettingsPage` takes the live `ShellSnapshot` instead of separate `settings`
  and `appInfo` copies, plus an `onRefreshDevices` callback; the readings come
  from the same snapshot the panels use.

## Consequences

- The readings are one click deeper (settings → 环境), which is what a
  diagnostics page is for; the toolbar chip still answers "is adb up?" without
  opening anything.
- Every destination page ends with its own content instead of two cards that
  belong to another screen.
- `e2e/ui-perf.mjs` walks six top-level settings pages now, and finds the
  Simpleperf row by name rather than by index so a page inserted above it
  cannot turn the walk into a timeout.
- `EnvironmentSettings.test.ts` renders the page and asserts what it must keep
  reporting: the adb path, the devices, the Trace Processor and the settings
  origin.
- The reference implementation has no such page, so
  `docs/records/electron-feature-parity.md` records it as an Electron-side
  addition.
