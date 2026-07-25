# Desktop Entry Extraction Plan

## Goal

Move process startup, window creation, application icon assets, and native packaging from
`layout-inspector/desktop-app/` to the root `desktop-app/` module. Rename the remaining
feature UI module to `layout-inspector/presentation/` so it is clearly an embeddable Layout
Inspector workspace rather than a second application.

## Behavior Lock

1. Extend the directory-boundary regression test and observe it fail before the extraction.
2. Preserve the existing window title, icon, native Preferences handler, minimum size, versioning,
   runtime modules, target formats, and Layout Inspector startup behavior.
3. Keep Layout Inspector packages and functional tests unchanged unless a symbol must cross the new
   module boundary.

## Steps

1. Register a root `:desktop-app` module that depends on
   `:layout-inspector:presentation`.
2. Move `Main.kt`, the application-level Preferences menu adapter, icons, packaging configuration,
   and shell-specific tests to the root module.
3. Leave `DesktopViewerApp` and the settings-request interpretation inside the Layout Inspector
   feature.
4. Remove application/native-distribution ownership from the feature build script.
5. Update run, package, release, and documentation paths.
6. Run focused structure/startup tests, all tests, Simpleperf checks, Android lint/sample assembly,
   and the root native distributable build.

## Stop Condition

- `desktop-viewer/desktop-app` is the only process entry and native package owner.
- `layout-inspector/presentation` contains no `main`, window creation, application icons, or
  `compose.desktop.application` configuration.
- Existing Layout Inspector behavior and packaging checks pass.
