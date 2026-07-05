# AgentPerf Inspector Naming Design

## Goal

Replace the development-facing `MainKt` application name with
`AgentPerf Inspector`, a concise name that reflects the product's Android
layout hierarchy, rendering-risk, overdraw, and performance inspection tools.

## User-visible surfaces

- macOS application menu name
- Desktop window title
- Native distribution package display name

All three surfaces use the exact text `AgentPerf Inspector`.

## Compatibility boundaries

- Keep the Kotlin entry point `dev.agentperf.desktop.MainKt`.
- Keep existing Kotlin and protocol package names unchanged.
- Keep stored preferences and application behavior unchanged.
- Do not rename source files, modules, or remote artifacts.

## Implementation

Define one application display-name constant used by runtime window creation.
Configure Compose Desktop's native distribution with the matching display
name, while retaining the current `mainClass`.

## Verification

- Unit test locks the shared display name.
- Desktop tests and the full Gradle test suite pass.
- Launching from Gradle shows `AgentPerf Inspector` instead of `MainKt` in the
  macOS menu bar and window title.
