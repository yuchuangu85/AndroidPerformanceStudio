# Android Performance Studio - Electron

Electron rewrite of the Android Performance Studio desktop workstation.

- Plan: `../docs/design/2026-09-10-electron-rewrite-plan.md`
- Decision: `../docs/adr/0036-rewrite-the-desktop-app-on-electron-without-jvm.md`
- Tracking issue: [#21](https://github.com/yuchuangu85/AndroidPerformanceStudio/issues/21)

## Layout

```text
apps/desktop/        Electron main + preload + React renderer, packaged by electron-builder
packages/contracts/  Versioned JSON contracts shared by main and renderer
```

## Commands

```bash
pnpm install
pnpm typecheck
pnpm test
pnpm build
pnpm package:smoke   # electron-builder --dir, no publish/signing
```

The target product contains no JVM. Native tools (`adb`, `trace_processor_shell`)
are reused; performance-critical modules may fall back to Rust behind the same
interface only if they miss their performance gate.

Kotlin `Long` contract fields use `bigint` end to end so 64-bit timestamps stay
exact; see `packages/contracts/README.md`.

## Status

Phase 0 (foundation). No releases are produced during the rewrite.
