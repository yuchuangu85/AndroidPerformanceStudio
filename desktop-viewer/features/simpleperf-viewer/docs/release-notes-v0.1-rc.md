# Android Performance Studio V0.1 Release Candidate

## Highlights

- Cross-platform Compose Desktop client for Android Simpleperf capture and offline analysis.
- USB ADB target discovery, capability/event probing, App/process/thread selection, templates and advanced sampling parameters.
- Distinct **Stop and analyze** and **Cancel** capture controls with reproducible local evidence and remote cleanup logs.
- Automatic online `perf.data` conversion/indexing plus offline session package, `perf.data` and protobuf import with optional mapping/symbol inputs.
- Overview, Perfetto-style Timeline/FlameGraph navigation, Top Functions, forward/reverse CallTree and evidence-linked diagnostics.
- Session package, JSON, CSV, PNG, raw protobuf, `simpleperf report` and `report_html.py` export/validation paths.
- Million-record performance baseline, API 29–36/ABI compatibility fixtures and deterministic sample session.

## Local verification

- macOS arm64/JDK 21: full Gradle `check`, million-record PoC, deterministic sample generation, portable app image, DMG packaging and packaged-runtime startup smoke.
- GitHub Actions [run 29274136077](https://github.com/yuchuangu85/AndroidPerfermaceStudio/actions/runs/29274136077): macOS 15, Windows 2025 and Ubuntu 24.04 clean runners passed checks, portable distributions and native installer builds.
- Generated sample session SHA-256: `490c0d31b676316235326dc13ac2f392498e00467437c49119550541d9def98e`.
- Locally validated macOS DMG SHA-256: `67ae139b6e32f300d0f77e150f7a516f1868105a8db689e07cb7f6bb4d93658f`.
- Platform-specific artifact SHA-256 values are published with the corresponding GitHub Release artifacts.

## Known release gates

- A connected Android device was unavailable in the development environment, so real profileable-device Start/Stop/Cancel smoke tests remain required.
- Windows and Linux installers are configured in CI but must pass clean-runner installation/startup verification before the release candidate is promoted.
- GitHub Actions artifact storage is currently at quota; clean-runner build/package results remain blocking, while uploads warn non-blockingly and must be restored before promotion.
- Distribution signing/notarization or repository-signing policy is not yet complete.
- Linux screenshots depend on the active desktop/Wayland capture policy.
- Host Simpleperf is not bundled in this source snapshot; online conversion requires a compatible `simpleperf` on `PATH`, while offline `perf.data` import prompts for its executable.

See [`release-checklist.md`](release-checklist.md) for the authoritative promotion gates.
