# Finding Severity Colors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Color every FINDINGS row according to its INFO, WARNING, or ERROR severity.

**Architecture:** Map analysis severity to a display-only `FindingTone` in the presenter, then map that tone to the existing badge colors in Compose.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, JUnit 5

---

### Task 1: Presenter Mapping

- [ ] Add a failing presenter test covering all three severity levels.
- [ ] Add `FindingTone` and expose it on `FindingRowModel`.
- [ ] Map `Severity.INFO`, `WARNING`, and `ERROR`.
- [ ] Run the targeted presenter test.

### Task 2: UI and Verification

- [ ] Map each finding tone to the matching badge color.
- [ ] Run `git diff --check`, desktop tests, and the full test suite.
- [ ] Restart the desktop application and confirm clean runtime output.
