# Third-party sources

## Firefox Profiler

`firefox-profiler/` is a shallow Git Submodule of
<https://github.com/firefox-devtools/profiler> pinned to:

```text
faaf1a14affd3c6d8b7342188371079b999abf5b
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

To intentionally update the pinned upstream version, fetch and check out an explicit commit inside
the submodule, validate its build, then commit the updated Git link in this repository:

```bash
git -C third_party/firefox-profiler fetch origin
git -C third_party/firefox-profiler checkout <commit>
./scripts/firefox-profiler.sh install
./scripts/firefox-profiler.sh build
git add third_party/firefox-profiler
```
