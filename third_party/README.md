# Third-party sources

## Vendored AOSP inspection sources

Four upstream snapshots are vendored verbatim so APS can build the Compose inspection agent without
an AOSP Bazel workspace or an Android Studio installation. APS patches live outside the snapshots,
in `aosp-ui-inspector-patches/`.

| Path | Upstream | Pin |
| --- | --- | --- |
| `aosp-ui-inspector/` | `platform/tools/base`, `ui-inspector` | `b1261356012800c0a93d18f03b060024e8162c2f` |
| `androidx-inspection/` | `platform/frameworks/support`, `inspection/inspection` | `4da03982d44f5a668172df67c920114ceca6dc2b` |
| `aosp-transport-native/` | `platform/tools/base`, `transport/native` | `b1261356012800c0a93d18f03b060024e8162c2f` |
| `aosp-slicer/` | `platform/tools/dexter`, `slicer` | `d992a222ec28b56efa29f9104db060379298049c` |

Each directory carries a `PROVENANCE.md` with the archive URL, SHA-256 and licence. See
`desktop-viewer/layout-inspector/compose-agent-build/` for the build that consumes them.

Upstream refs were checked on 2026-10-28. The AndroidX Java API matches the latest `androidx-main`
snapshot byte-for-byte, and Slicer's existing pin remains the latest `main` revision.

UI Inspector and its paired transport pin remain on the compatible baseline. The latest
`platform/tools/base` revision, `42836d6bd318823a9b75bc8378add8de19531e35`, replaces the native agent
and Java service layout with `art-tooling/native` and `art-tooling/java`, changes the injection
contract, and does not accept the two APS patches. Updating it requires a coordinated host,
packaging, native build and session-token migration, not a source-only pin change. The 27 vendored
transport files already match the latest snapshot, so no transport source change is needed.

## Firefox Profiler

`firefox-profiler/` is a shallow Git Submodule of
<https://github.com/firefox-devtools/profiler> pinned to:

```text
de9b76abb5570010e6c9aa801f0a2ae020cd8d6f
```

The upstream project is licensed under MPL-2.0. Its source, license, dependency lockfile, and
notices remain inside the submodule checkout.

Initialize and verify the pinned source:

```bash
git submodule update --init --depth 1 --recursive
./scripts/firefox-profiler.sh verify
```

The pinned revision requires Node.js 24 and Yarn Classic 1.x. Install dependencies and create the
production web build independently from the Kotlin/Gradle application build:

```bash
./scripts/firefox-profiler.sh install
./scripts/firefox-profiler.sh build
```

The generated static assets are written to `third_party/firefox-profiler/dist/` and remain ignored
by the upstream submodule.

Android Performance Studio does not embed the React application. When the Firefox Profiler local
engine is selected, the desktop application serves this pinned build from `127.0.0.1`, serves the
generated `perf_data.json.gz` from the same local origin, and opens the local page in the system
browser. The Firefox Profiler engine without the local qualifier continues to open the official
website.
Native application distributions copy the already-built `dist/` directory into the application
resources; keep the Node/Yarn build as a separate prerequisite before packaging.

```bash
./scripts/firefox-profiler.sh build
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:createDistributable
```

To intentionally update the pinned upstream version, fetch and check out an explicit commit inside
the submodule. Stage the new Git link so the verification script checks the intended pin, validate
its build, then commit the update in this repository:

```bash
git -C third_party/firefox-profiler fetch origin
git -C third_party/firefox-profiler checkout <commit>
git add third_party/firefox-profiler
./scripts/firefox-profiler.sh install
./scripts/firefox-profiler.sh build
```

## Perfetto

`perfetto/` is a shallow Git submodule of <https://github.com/google/perfetto>, pinned to the
latest stable release checked on 2026-10-28:

```text
v59.1
1e9c61e9844affd7dccd3d42d9ea9f3a3d10cf6d
```

The upstream source is licensed under Apache-2.0. The local UI bundle and native Trace Processor
must use the same release as the source checkout. Refresh the ignored build outputs before running
or packaging APS:

```bash
./scripts/build-perfetto-ui.sh download
PERFETTO_TOOLS_DIR="$PWD/build/perfetto-tools" ./scripts/install-trace-processor.sh
```

The Trace Processor URLs and SHA-256 checksums for all supported desktop platforms are pinned in
`desktop-viewer/platform-perfetto/trace-processor-manifest.json`. The desktop runtime and packaging
checks reject binaries whose version or content does not match that manifest.
