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
* **Read-only Frame MCP adapter.** `aps-frame-mcp` consumes the shared Frame
  report reader and evidence projections from `aps-analysis`. It owns its
  selected-input stdio executable; it does not depend on the application shell
  or Memory. The desktop owns capture/export and records report/trace identities
  separately in its data directory; the adapter checks a locally trusted receipt
  and both selected files. It never derives file paths or capture authority from
  report contents. Only desktop tests depend on the MCP adapter, to exercise both ends
  of this file contract. The existing Memory MCP executable stays compatible.

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

## Headless host

`aps-cli` is a second composition root, not a dependency of the desktop or
the profiler libraries. It selects existing analyzers and emits citeable,
bounded sections. The CLI owns retrieval, scenario/target confirmation and
explicit output destinations. Its stdio adapter binds one host-selected
source and cannot execute those operations. The two hosts share the APS AI
gateway and its curl transport; the CLI has separate, explicit provider
configuration and cannot consume a desktop approval.

### Where CLI and MCP code lives

* `aps-cli/src/cli/` is the terminal host: command dispatch, CPU capture,
  benchmark scenarios, model confirmation and no-clobber output. The two
  executable targets point to `cli/main.rs` and `mcp/main.rs`.
* `aps-cli/src/mcp/` owns only the source-bound stdio server. Startup binds
  the host-selected input; `stdio.rs` frames messages, `protocol.rs` dispatches
  JSON-RPC, `tools.rs` describes and handles the read-only tools, and
  `source.rs` guards access to the frozen result. `follow_up.rs` validates
  fixed, analyzer-specific selectors. The binding owns the per-process query
  budget and revalidates its source; projections never get the selected path.
  The external client owns the investigation loop. Both MCP entry points
  invoke this same implementation.
* Put a new offline analyzer adapter in `aps-cli/src/analysis/`, not in either
  frontend. Input acquisition and capability discovery are shared host modules;
  the neutral evidence contract itself belongs in `aps-analysis`.
  `aps-cli/src/lib.rs` keeps existing library import paths via re-exports.
* Dedicated servers stay with their domain:
  `aps-memory/src/mcp/`, `aps-cpu/src/cpu_mcp/`, or the existing
  `aps-frame-mcp` crate. `aps-ai/src/mcp/` is a **client**, not another
  implementation of the Memory server. The Benchmark command is in
  `aps-benchmark/src/cli/`; the HAR and Simpleperf engine commands likewise
  live under their own `src/cli/`. Their reusable parsers, validation and
  the benchmark runner are not CLI code.

### Shared analysis and AI task contracts

* `aps-analysis` accepts data, not paths or commands. Its Frame reader hashes
  the exact bytes being parsed. Both MCP hosts and the CLI use this reader;
  the CLI does not depend on an MCP server crate. The desktop retains its
  rich import model and passes its analysis, verified file digest and
  `FrameScope` to the same bounded evidence projector. Plain files cannot
  attest trace-probe overlaps. Live desktop reviews keep their session binding.
  Other profilers still use their existing projections; this is not a rewrite
  of every GUI page. The common v1 evidence envelope retains the original
  unscoped section IDs for scripts.
* `aps-ai::task` defines run states, failure classification and report outcomes.
  A validated analysis can coexist with a failed report write. Both hosts use
  the same bounded renderer and receipt for the committed bytes. Only the
  desktop archives by hash; only the CLI chooses a no-clobber output path.
  A retained answer can retry a failed local write without calling a model;
  there is no cross-process resume or shared approval token.
