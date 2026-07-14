# Feature Directory Cleanup Plan

## Goal

Make feature ownership visible in the filesystem by placing all Layout Inspector code under
`layout-inspector/` and keeping all Simpleperf code under
`simpleperf-viewer/`, without changing runtime behavior or package names.

## Scope

1. Add a regression test for the two feature directory boundaries.
2. Move the Layout Inspector modules into `layout-inspector/`:
   - `adb-gateway/`
   - `application/`
   - `desktop-app/`
   - `shared-kernel/`
   - `samples/`
3. Namespace the Layout Inspector Gradle projects below `:layout-inspector` and update
   internal project dependencies, build commands, release paths, and current documentation.
4. Leave the existing Simpleperf source tree under `simpleperf-viewer/` and preserve its
   isolated composite-build behavior.
5. Remove only generated local build output that would otherwise leave misleading empty legacy
   directories; do not change production packages or add dependencies.

## Behavior Lock

- Run the existing root and Simpleperf test suites before the move.
- Add a directory-boundary test and observe it fail before migration.
- After migration, run focused structure/release tests, all root tests, Simpleperf checks, and the
  Android sample assembly.

## Stop Condition

- No tracked Layout Inspector code remains in the former root module directories.
- Both features have one obvious top-level owner directory.
- Gradle configuration, tests, sample assembly, and release-path assertions pass.
