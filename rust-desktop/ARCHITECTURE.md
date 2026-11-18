# Rust module boundaries

Prefer an explicit dependency on a small public contract over reaching into
a peer's implementation. The application shell composes features. Its fan-out
is intentional; feature code depending on the shell, other features' private
state or a profiler-specific engine for a generic primitive is not.

## What CI enforces

From the repository root, with Python 3.9+ and Cargo supporting workspace
resolver 3 available offline:

```sh
python3 -B -m unittest discover -s scripts/tests -p test_rust_architecture.py
python3 -B scripts/check_rust_architecture.py
```

* **Internal dependencies.** The checker reads Cargo's own
  `metadata --locked --offline --no-deps` package declarations. Each workspace
  member and each permitted **direct** production edge has a named owner in
  `scripts/check_rust_architecture.py`. The normal and build allowlists are
  distinct. Aliasing, workspace inheritance, optional features and target-specific
  dependencies do not bypass them. Dev-only fixture/test dependencies are not
  production edges. External third-party dependencies are not governed here.
* **Source ownership.** Cargo targets must reside in their own crate. The
  checker scans member `.rs` files (including tests and build scripts) for
  `#[path]`, `#[cfg_attr(..., path=...)]` and `include!`. `include_str!`
  and `include_bytes!` cannot import another crate's `.rs` source either.
  Local helper/test sources and non-Rust assets are allowed. Literal paths and
  `concat!(env!("CARGO_MANIFEST_DIR"), "/literal")` are checked after path
  resolution, including symlinks. Generated files under `OUT_DIR` are allowed
  with a literal suffix that does not traverse up. Unrecognized dynamic include
  expressions fail review rather than being silently accepted.
* **Known legitimate collaborations.** GPU may consume Perfetto analysis and
  Startup capture may use its public recording adapter (not the Trace UI). CPU
  may consume the Simpleperf engine, and storage may implement source repository
  contracts. These directed edges do not entitle the reverse direction. Shared
  hash/encoding primitives belong to `aps-primitives`, not an analyzer.

To add a module or edge, review the *reason and direction*, classify it and
adjust the named rule only if an existing contract cannot satisfy it. Never
whitelist a copied or path-included private implementation to get CI green.

## Page boundaries in `aps-app`

* Each import destination has its own workspace instance. Battery alone owns
  `BatteryState`; change its device-work cancellation, report view reset and
  retained history in `src/import/battery/` and `src/import/battery.rs`. The
  common importer still owns its task generation, published session and page.
  Other import routes have not all been migrated to this pattern.
* Frame and Memory emit AI preflight intents, not provider requests or evidence.
  The shell checks the active route before reading the originating page. It
  also owns Frame-to-Inspector navigation. Keep these page actions independent
  of the concrete `Studio` entity.
* Home displays CPU snapshot references owned by CPU; CPU revalidates the
  stored binding and bytes on explicit open. Showing recent items is not an
  instruction to parse a profile. Frame and Trace reuse the local Perfetto
  viewer implementation, but keep separate server instances and ports.

## What this check does not prove

The source scanner is a static check of literal include syntax, not rustc
expansion or a machine security boundary. It does not track arbitrary
build-script file reads, procedural macro output, runtime files, dynamic IPC
schemas or semantic coupling between modules inside one crate. In particular,
a green workspace graph does **not** prove independent UI page state. Use
module visibility, typed action/transfer contracts and focused behavior tests
there. A CI pass also does not replace building the product, running its tests
or validating the application with a real device.
