# Remove Features Directory Layer

## Goal

Remove the redundant `features/` directory so the two feature owners live directly under the
desktop Gradle root:

- `layout-inspector/`
- `simpleperf-viewer/`

Keep `desktop-app/` as the neutral root executable shell.

## Behavior Lock

1. Update the directory-boundary regression test and observe it fail before moving files.
2. Preserve Kotlin packages, runtime behavior, Simpleperf composite-build isolation, native
   packaging, and release commands.

## Steps

1. Move both feature directories to the desktop root and remove `features/README.md` after merging
   its current boundary notes into the root README.
2. Rename Layout Inspector Gradle paths from `:layout-inspector:*` to
   `:layout-inspector:*`.
3. Point the Simpleperf included build and proxy tasks at `simpleperf-viewer/`.
4. Update source-path references, commands, architecture docs, and active implementation notes.
5. Run focused structure tests, all tests, Simpleperf static checks, sample assembly, native
   packaging, and a packaged startup smoke test.

## Stop Condition

- `desktop-viewer/features/` does not exist.
- Layout Inspector and Simpleperf each have one direct root directory.
- All build and packaging verification passes.
