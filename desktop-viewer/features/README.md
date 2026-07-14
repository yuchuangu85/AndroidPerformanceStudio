# Feature boundaries

The repository contains two desktop products that share a repository but not implementation modules.

## Layout Inspector

Owned by the root Gradle build:

- `desktop-app/` — Compose Desktop entry point and layout inspection UI.
- `application/` — layout inspection state and use cases.
- `adb-gateway/` — layout snapshot and screenshot transport.
- `shared-kernel/` — protocol, analysis, Android Agent, and fixtures.
- `samples/` — Android integration samples.

Run with `./gradlew :desktop-app:run`.

## Simpleperf CPU Profiler

Owned by the isolated composite build at `features/simpleperf-viewer/`:

- `app-desktop/` and `presentation/` — CPU profiler entry point and UI.
- `capture-simpleperf/` and `device-adb/` — Simpleperf capture and device discovery.
- `parser-simpleperf-proto/` — Simpleperf protobuf conversion and parsing.
- `storage-sqlite/`, `analysis-rules/`, and `visualization/` — report storage and analysis.
- `profile-model/`, `platform-toolchain/`, `export-adapters/`, and `test-fixtures/` — supporting contracts and adapters.

Run with `./gradlew simpleperfRun`.

## Isolation contract

- Neither product declares project dependencies on the other product's modules.
- The Simpleperf feature retains its own settings, plugin versions, dependency graph, quality rules, tests, docs, and native package identity.
- Root proxy tasks call the composite build but do not add its classes to the Layout Inspector runtime or package.
- A change may cross the boundary only through an explicitly documented adapter; no such adapter exists today.
