# Android Performance Studio - Electron

Electron rewrite of the Android Performance Studio desktop workstation.

- Plan: `../docs/design/2026-09-10-electron-rewrite-plan.md`
- Decision: `../docs/adr/0036-rewrite-the-desktop-app-on-electron-without-jvm.md`
- Tracking issue: [#21](https://github.com/yuchuangu85/AndroidPerformanceStudio/issues/21)
- Migration state: `../docs/records/electron-feature-parity.md`

## Layout

```text
apps/desktop/        Electron main + preload + React renderer, packaged by electron-builder
packages/contracts/  Versioned JSON contracts shared by main and renderer
e2e/                 Frame-rate harness that drives the built application
```

## Commands

```bash
pnpm install
pnpm typecheck
pnpm test
pnpm build
pnpm perf:hierarchy   # 10,000-node load gate
pnpm perf:ui          # frame-rate gate; needs a display or xvfb
pnpm package:smoke    # electron-builder --dir, no publish/signing
```

Packaging a real installer also needs the bundled assets to exist first:
`./scripts/build-perfetto-ui.sh download` and
`PERFETTO_TOOLS_DIR="$PWD/build/perfetto-tools" ./scripts/install-trace-processor.sh`
run from the repository root. `apps/desktop/scripts/verify-package.mjs` checks that
the unpacked application actually carries them.

The target product contains no JVM. Native tools (`adb`, `trace_processor_shell`)
are reused; performance-critical modules may fall back to Rust behind the same
interface only if they miss their performance gate.

Kotlin `Long` contract fields use `bigint` end to end so 64-bit timestamps stay
exact; see `packages/contracts/README.md`.

## Status

The shell, the platform layer, and every profiler destination have TypeScript
implementations. What is measured, what is only implemented, and what remains
unmigrated is tracked in `../docs/records/electron-feature-parity.md` rather than
restated here, so the two cannot drift apart.

No releases are produced during the rewrite.
