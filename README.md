# Android Performance Studio

[简体中文](README.zh-CN.md)

Android Performance Studio is a Compose Desktop workstation for collecting, inspecting, and correlating Android performance evidence. Layout, CPU, system traces, memory, frames, startup, battery, network, GPU tooling, and benchmark regression share one desktop shell, while each analyzer keeps its own data model and implementation.

> Some executable and installer names retain the historical `AndroidPerfermanceStudio` spelling for compatibility.

## Highlights

- Connected-device and process selection, recent artifacts, and quick actions in one workspace.
- English and Simplified Chinese, light and dark themes, system theme selection, and configurable display scale.
- Local capture through ADB and offline analysis of imported artifacts, with explicit capability and evidence warnings.
- Read-only Local, GitHub, and AOSP source workspaces, source resolution, and an integrated source viewer.
- Optional, explicitly triggered AI-assisted layout analysis using selected evidence and authorized source context.
- Native installer workflows for macOS, Windows, and Linux, including Apple Silicon and Linux arm64.

## Install and start

Download an installer matching your operating system and CPU architecture from [GitHub Releases](https://github.com/yuchuangu85/AndroidPerformanceStudio/releases).

| Platform | Architecture | Installer formats |
| --- | --- | --- |
| macOS | arm64 / x64 | DMG, PKG |
| Windows | x64 | MSI, EXE |
| Linux | x64 / arm64 | DEB, RPM |

Installers include a Java runtime and prepared profiler resources. Git, a separate JDK, Node.js, Yarn, Python, and the Android SDK/NDK build toolchain are not required to use an installer.

Device workflows require Android SDK Platform Tools (`adb`), USB debugging, and device authorization. Artifact-only analysis does not require a connected device. Open **Settings** to configure the Android SDK location, language, theme, and display scale; source and AI settings are optional.

See the [English user guide](docs-user/README.md) or [Simplified Chinese user guide](docs-user-zh/README.md) for individual workflows.

## Screenshots

These screenshots show captured application states. Available fields and capture capabilities depend on the device, application, and evidence source.

### Home

Connected devices, quick actions, analysis modules, and recent artifacts.

![Home with connected devices, analysis modules, and recent sessions](media/AndroidPerformanceStudio00.png)

### Layout Inspector

View hierarchy, bounds overlay, node properties, timeline differences, and findings.

![Layout Inspector with hierarchy, screenshot canvas, node properties, and findings](media/AndroidPerformanceStudio01.png)

### CPU Profiler

Thread tracks, linked call-tree selection, function details, and sample-quality indicators.

![CPU Profiler showing thread tracks, call-tree analysis, and function details](media/AndroidPerformanceStudio02.png)

### Memory Profiler

Heap classes, instance lists, fields, references, and evidence availability.

![Memory Profiler with class statistics, a flat instance list, and instance details](media/AndroidPerformanceStudio03.png)

### Frame Profiler

Frame durations, deadline-miss clusters, and per-frame evidence.

![Frame Profiler with frame timeline, deadline-miss clusters, and frame details](media/AndroidPerformanceStudio04.png)

### Startup Profiler

Cold, warm, and hot experiments, per-run startup metrics, and compilation/profile evidence.

![Startup Profiler with startup modes, TTID and TTFD availability, and measured runs](media/AndroidPerformanceStudio05.png)

### Battery Profiler

Saved experiments, battery snapshots, and UID-attributed resource evidence.

![Battery Profiler with saved sessions, battery snapshots, and resource details](media/AndroidPerformanceStudio06.png)

### GPU Inspector

Artifact indexing, integrity verification, and external-tool capability checks.

![GPU Inspector showing verified artifacts, graphics context, and AGI capability status](media/AndroidPerformanceStudio07.png)

## Features

| Tool | Current capabilities |
| --- | --- |
| **Layout Inspector** | Inspect View and supported runtime Compose hierarchies, screenshots, bounds, node properties, findings, and timeline differences. Search or isolate nodes and import/export capture archives. Full Compose inspection and compatible fallback have different capabilities. |
| **CPU Profiler** | Capture/import simpleperf profiles; explore thread timelines, top functions, forward/reverse call trees, flame graphs, stack charts, and marker views with time, thread, event, and stack filtering. |
| **Trace Analyzer** | Capture/import Perfetto traces, inspect scheduling, Binder/IPC, graphics, and other available diagnostics, retain recent sessions, export raw traces, and open the prepared Perfetto UI. |
| **Memory Profiler** | Capture/import HPROF; inspect classes, instances, fields, references, dominators, retained sizes, and snapshot differences. Investigate leak candidates, Bitmap dumps, native heaps, and sampled ART allocations where supported. |
| **Frame Profiler** | Capture/import frame statistics and supported Perfetto FrameTimeline evidence; inspect frame-duration percentiles, deadline-miss clusters, and available platform jank signals. Open related layout or trace evidence for correlation. |
| **Startup Profiler** | Run repeated cold, warm, and hot startup experiments. Inspect requested/observed launch modes, TTID, available TTFD and phase evidence, compilation state, and Baseline Profile status; import/export results. |
| **Battery Profiler** | Review saved and repeated batterystats experiments, battery samples, wakelocks, alarms, jobs, sensors, network counters, and available energy evidence. Export results and legacy Battery Historian input. |
| **Network Profiler** | Inspect configured OkHttp-agent captures or imported HAR files through request activity, timings, failures, domains, connection/TLS evidence, and redacted request details. |
| **GPU Inspector** | Discover/configure Android GPU Inspector, index/import artifacts, verify file integrity, and route supported artifacts to AGI or Trace Analyzer. This is external-tool integration, not a complete built-in GPU profiler. |
| **Benchmark Regression** | Import AndroidX Benchmark JSON, select saved runs as baseline/current, compare compatible metrics with statistical safeguards, and export reports. Optional Macrobenchmark orchestration uses an existing benchmark project and device. |
| **Source Workspaces** | Register read-only Local, GitHub, and AOSP sources; manage immutable snapshots, indexes, and build evidence; resolve supported performance evidence to inspectable source candidates. |

## Capability and privacy boundaries

- Full runtime Compose inspection requires a verified inspection runtime, a debuggable target on Android API 29+, and an exact matching Compose inspector. View or Semantics fallback is not equivalent to a full Composable tree.
- Live network capture observes only OkHttp clients configured with `NetworkProfiler.eventListenerFactory` and the corresponding agent setup. It does not capture all device traffic, WebView, Cronet, URLConnection, or native sockets. [Network details](docs/features/network-profiler.md).
- TTFD requires a Fully Drawn event, and startup phases require actual milestone or trace evidence. Deadline misses and platform jank are different signals. Leak suspects require review; they are not proof of a leak.
- UID-attributed resources, system-modeled energy, and device-level power measurements have different scopes. Missing evidence stays unavailable rather than being replaced with an invented value.
- Local-first analysis does not mean optional AI requests remain on the machine. AI analysis sends the selected evidence to the configured endpoint after an explicit action; source snippets require workspace authorization. Registering a source workspace does not upload the project.
- Tool handoffs and time overlap aid correlation; they do not establish causality. Screenshots are not a device/API compatibility matrix.
- Large, deep, fragmented Stack Chart data has a documented [performance optimization plan](desktop-viewer/simpleperf-viewer/docs/design/2026-10-29-stack-chart-performance-optimization-plan.md); the plan remains **unimplemented**.

## Build from source

### Prerequisites

- JDK 21 and Git.
- Node.js **24** and Yarn Classic **1.x** for the pinned Firefox Profiler assets.
- Python 3, `curl`, and `unzip` for asset preparation; release CI uses Python 3.13.
- Android SDK command-line tools, SDK Platform Tools, NDK/CMake, and `patch` on the inspection-runtime build host.
- A Bash-compatible shell for the scripts below. Windows uses `gradlew.bat` for Gradle tasks.

Pinned tool versions are declared in the [Gradle wrapper](desktop-viewer/gradle/wrapper/gradle-wrapper.properties), [version catalog](desktop-viewer/gradle/libs.versions.toml), and [release workflow](.github/workflows/release.yml).

### 1. Clone and prepare profiler assets

```bash
git clone --recurse-submodules https://github.com/yuchuangu85/AndroidPerformanceStudio.git
cd AndroidPerformanceStudio

npm install --global yarn@1
./scripts/firefox-profiler.sh all
./scripts/build-perfetto-ui.sh download
PERFETTO_TOOLS_DIR="$PWD/build/perfetto-tools" ./scripts/install-trace-processor.sh
```

For an existing checkout without submodules, run `git submodule update --init --depth 1 --recursive` first. The Perfetto `download` mode prepares the complete pinned UI distribution, including its WASM assets.

### 2. Prepare the verified Compose inspection runtime

The desktop resource preparation checks this bundle before launch or packaging. On a configured Unix build host, set `ANDROID_HOME` to the intended SDK and install the tools used by release CI:

```bash
sdkmanager --sdk_root="${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}" --install \
  "platforms;android-35" "build-tools;35.0.0" \
  "ndk;27.1.12297006" "cmake;3.22.1"

./desktop-viewer/gradlew -p desktop-viewer \
  :layout-inspector:compose-agent-build:verifyComposeAgentBundle \
  --no-configuration-cache --no-daemon
```

These SDK pins build the host-independent inspection runtime, not the optional Android sample app. Release CI builds the bundle once on Linux x64 and supplies it to all packaging jobs. A packaging host can consume an already built bundle at `desktop-viewer/layout-inspector/compose-agent-build/build/compose-agent-bundle/`; its manifest, dexed JARs, and device ABIs are checked before use. See [runtime build details](desktop-viewer/layout-inspector/compose-inspection-host/AGENT_BUNDLE.md).

### 3. Run

```bash
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:run
```

## Test and package

Run the desktop application tests; feature modules are separate included builds and have their own test tasks:

```bash
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:test --no-daemon

# Examples of feature-specific validation
./desktop-viewer/gradlew -p desktop-viewer \
  :layout-inspector:presentation:test \
  :simpleperf-viewer:profile-analysis:test --no-daemon
```

After preparing the resources above, create an application image for the current host:

```bash
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:createDistributable --no-daemon
```

An application image is not an installer. Build installers on a matching operating system with the appropriate JDK architecture; Linux installer builds also need `fakeroot` and `rpm`:

```bash
# macOS Apple Silicon / Intel: choose arm64 or x64 on the matching host
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:packageDmg :desktop-app:packagePkg -Ptarget.arch=arm64 --no-daemon

# Windows x64
./desktop-viewer/gradlew.bat -p desktop-viewer :desktop-app:packageMsi :desktop-app:packageExe -Ptarget.arch=x64 --no-daemon

# Linux x64 / arm64: choose the matching architecture
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:packageDeb :desktop-app:packageRpm -Ptarget.arch=x64 --no-daemon
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:packageDeb :desktop-app:packageRpm -Ptarget.arch=arm64 --no-daemon
```

The application version is centralized in [desktop-viewer/build.gradle.kts](desktop-viewer/build.gradle.kts) and can be overridden with `-PappVersion=0.7.1`. A custom packaging JDK can be selected with `-Ptarget.javaHome=/absolute/path/to/jdk`.

The release workflow accepts version tags or a manual version input. It runs tests, prepares the inspection runtime, builds the ten installers in the platform matrix, and publishes only after checking the uploaded assets. Signing and notarization are separate release requirements; they are not configured by this workflow.

## Project layout

```text
desktop-viewer/          Compose Desktop application and composite feature builds
  desktop-app/           Shell, navigation, settings, and native packaging
  layout-inspector/      View/Compose inspection, archives, and findings
  simpleperf-viewer/     CPU sampling, queries, and report visualizations
  perfetto-viewer/       System trace capture, diagnostics, and viewer integration
  memory-profiler/       HPROF, instance/reference, native heap, and Bitmap analysis
  frame-profiler/        Frame timing, deadline misses, and jank evidence
  startup-profiler/      Startup experiments and compilation/profile evidence
  battery-profiler/      Battery experiments and UID resource evidence
  network-profiler/      Configured network-agent capture and HAR analysis
  gpu-inspector-integration/  AGI discovery and verified artifact routing
  benchmark-regression/  Benchmark comparison, orchestration, and reports
  source-workspace/      Source providers, snapshots, indexes, and resolution
  ai-core/               Shared AI transport and evidence-bound contracts
  platform-core/         Shared profiler, ADB, and host-toolchain contracts
  platform-perfetto/     Pinned Trace Processor and shared Perfetto infrastructure
  ui-components/         Shared Compose controls and theme
media/                   Application screenshots used in this README
third_party/             Pinned upstream code and profiler resources
scripts/                 Resource preparation and build helpers
docs-user/               English user guide
docs-user-zh/            Simplified Chinese user guide
docs/                    Architecture, requirements, and feature documentation
```

## More documentation

- [Feature documentation](docs/features/README.md)
- [Desktop development guide](desktop-viewer/docs/architecture/DEVELOPMENT.md)
- [Layout Inspector protocol](desktop-viewer/docs/architecture/PROTOCOL.md)
- [Source Workspace](desktop-viewer/source-workspace/README.md)
- [AI Core](desktop-viewer/ai-core/README.md)
- [AI documentation](docs/ai/README.md)
- [Third-party resources and provenance](third_party/README.md)
- [Documentation index](docs/README.md)
