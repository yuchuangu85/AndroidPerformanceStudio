# Remove the Winscope workspace and the upstream viewer submodule

Status: Accepted (2026-09-10)

Supersedes: [ADR 0031](0031-parse-winscope-through-pinned-trace-processor-sql.md),
[ADR 0032](0032-build-winscope-as-a-native-compose-workspace.md),
[ADR 0033](0033-package-upstream-winscope-as-an-optional-browser-viewer.md),
[ADR 0034](0034-use-aosp-winscope-as-the-upstream-viewer-submodule.md)

## Context

Android Performance Studio carried two Winscope delivery paths: a native
Compose workspace in `desktop-viewer/winscope` (ADR 0032) parsed through the
pinned Trace Processor (ADR 0031), and an optional system-browser viewer that
served a packaged build of upstream AOSP-WinScope from the
`third_party/aosp-winscope` submodule (ADR 0033, ADR 0034).

The browser path made the upstream submodule a build input: Gradle had to
resolve submodule sources, run its pnpm/Go dependency and production build, and
copy the resulting `dist/prod` closure into the packaged `winscope-ui`
resources, then verify it in CI. That checkout is about 4.3 GiB on disk and
requires a Node/npm, Python, and Go toolchain to build — the heaviest and most
brittle dependency in the repository. The native workspace is the primary
product surface, so maintaining the second path had not paid for itself.

## Decision

Remove Winscope from the product and from the build:

- Drop the `third_party/aosp-winscope` submodule entry, its checkout, and the
  cached module under `.git/modules`, and stop consuming `dist/prod`
  altogether.
- Remove the upstream-viewer machinery: the `prepareWinscopeDependencies`,
  `prepareWinscopeUi`, and `verifyPackagedWinscopeUi` Gradle tasks, the
  packaged `winscope-ui` resource closure, the loopback server and launcher
  (`UpstreamWinscopeServer`, `UpstreamWinscopeLauncher`), and the engine
  preference that selected the browser path.
- Remove the `desktop-viewer/winscope` composite build and its
  `winscope-app`, `winscope-core`, and `winscope-test-fixtures` modules,
  including the `includeBuild` registration and the `desktop-app` module
  dependency.
- Remove the in-app Winscope surface: `AppDestination.WINSCOPE` and its label
  mapping, the home feature entry, the `SettingsPage.WINSCOPE` page and its
  content, `WinscopeUiSettings`/`WinscopePreferencesStore` (the
  `winscope.engine` key), and the `winscope*` strings in both locales.
- Remove Winscope tests, the pinned-submodule contract test, and the CI check
  step that built the upstream viewer.

## Consequences

- The build no longer requires Node/npm, Python, or Go for Winscope, and no
  longer performs a multi-minute upstream web build; `desktop-app` packaging
  shrinks by the removed `winscope-ui` closure.
- Previously captured Winscope traces and evidence packages remain on disk but
  are no longer inspectable in the app; the destination, settings page, and
  preference key are gone, and a stale `winscope.engine` entry in an existing
  settings file is ignored.
- Layout snapshots, Layout Inspector, Perfetto, and the remaining profiler
  workspaces are unaffected; other submodules (Perfetto, Firefox Profiler)
  stay pinned.
- Restoring Winscope means re-adding the submodule and reverting this removal;
  if the browser viewer returns, prefer a smaller delivery path over a full
  source build in Gradle.
