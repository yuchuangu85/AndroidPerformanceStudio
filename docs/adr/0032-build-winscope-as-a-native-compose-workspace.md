# Build Winscope as a native Compose workspace

Status: Superseded by [ADR 0035](0035-remove-the-winscope-workspace-and-upstream-viewer.md) (Winscope removed, 2026-09-10)

Winscope is implemented as a first-class Compose Desktop workspace instead of embedding the upstream web viewer through JCEF or WebView. This keeps one UI runtime, preserves the repository's native theme and packaging model, and avoids a heavyweight browser dependency; the trade-off is that upstream viewer behavior must be reproduced deliberately with repository-native timeline, hierarchy, properties, 2D, and specialized 3D stack components.
