# Layout Inspector

This directory owns all Layout Inspector implementation code in the desktop project.

## Modules

- `presentation/` — embeddable Compose Desktop Layout Inspector workspace.
- `application/` — inspector state and use cases.
- `adb-gateway/` — device discovery, snapshot transport, and screenshot fallback.
- `shared-kernel/` — protocol, deterministic analysis, Android Agent, and test fixtures.
- `samples/` — Android integration samples.

Gradle projects are namespaced below `:layout-inspector` so filesystem and build ownership
match. Kotlin package names remain unchanged to keep this move behavior-neutral.

## Commands

```bash
./gradlew :desktop-app:run
./gradlew :layout-inspector:layout-presentation:test
./gradlew :layout-inspector:samples:android-view-app:assembleDebug
```

Simpleperf implementation belongs to the sibling `../simpleperf-viewer/` directory.
The process entry, native window, icons, and packaging belong to the root `../desktop-app/`
module.
