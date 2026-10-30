# Layout Inspector

This directory owns all Layout Inspector implementation code in the desktop project.

## Modules

- `presentation/` — embeddable Compose Desktop Layout Inspector workspace.
- `application/` — inspector state and use cases.
- `adb-gateway/` — device discovery, snapshot transport, and screenshot fallback.
- `shared-kernel/` — protocol, deterministic analysis, Android Agent, and test fixtures.
- `samples/` — Android integration samples.

Layout Inspector is an included build named `layout-inspector`; its JVM projects use names
such as `:application` and `:presentation`. The parent wrapper addresses them as
`:layout-inspector:application` and `:layout-inspector:presentation`. Android Agent and sample
projects are separately enabled in the parent build with `-PincludeLayoutInspectorAgents=true`.

## Commands

Run from `desktop-viewer/`:

```bash
./gradlew :desktop-app:run
./gradlew :layout-inspector:presentation:test
./gradlew -PincludeLayoutInspectorAgents=true :layout-inspector-sample-app:assembleDebug
```

Simpleperf implementation belongs to the sibling `../simpleperf-viewer/` directory.
The process entry, native window, icons, and packaging belong to the root `../desktop-app/`
module.
