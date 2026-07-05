# AgentPerf Inspector Name Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `MainKt` and the old window/package display names with `AgentPerf Inspector` without changing the Kotlin entry point.

**Architecture:** A runtime constant owns the Compose window title. The Compose Desktop Gradle application configuration supplies the same text to the macOS AWT application-name property and native distribution package metadata, while `mainClass` remains unchanged.

**Tech Stack:** Kotlin/JVM 17, Compose Desktop 1.11, Gradle Kotlin DSL, JUnit 5.

---

## File Structure

- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationDisplayNameTest.kt`
  to lock the runtime and Gradle display names.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/Main.kt`
  to define and use the shared runtime display name.
- Modify `desktop-viewer/desktop-app/build.gradle.kts`
  to configure the Gradle-run macOS menu name and native package display name.

### Task 1: Unify the desktop application display name

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationDisplayNameTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/Main.kt`
- Modify: `desktop-viewer/desktop-app/build.gradle.kts`

- [ ] **Step 1: Write the failing display-name test**

```kotlin
package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationDisplayNameTest {
    @Test
    fun `runtime and distribution use AgentPerf Inspector`() {
        assertEquals("AgentPerf Inspector", APP_DISPLAY_NAME)

        val buildScript = Files.readString(Path.of("build.gradle.kts"))
        assertTrue(
            buildScript.contains(
                """jvmArgs("-Dapple.awt.application.name=AgentPerf Inspector")""",
            ),
        )
        assertTrue(
            buildScript.contains("""packageName = "AgentPerf Inspector""""),
        )
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ApplicationDisplayNameTest'
```

Expected: compilation fails because `APP_DISPLAY_NAME` does not exist.

- [ ] **Step 3: Define and use the runtime display name**

In `Main.kt`, add:

```kotlin
internal const val APP_DISPLAY_NAME = "AgentPerf Inspector"
```

Then change the window title:

```kotlin
Window(
    onCloseRequest = ::exitApplication,
    title = APP_DISPLAY_NAME,
)
```

- [ ] **Step 4: Configure Compose Desktop display names**

In `desktop-app/build.gradle.kts`, retain the existing `mainClass` and add the
macOS AWT name to the application JVM arguments:

```kotlin
compose.desktop {
    application {
        mainClass = "dev.agentperf.desktop.MainKt"
        jvmArgs("-Dapple.awt.application.name=AgentPerf Inspector")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AgentPerf Inspector"
            packageVersion = "1.0.0"
        }
    }
}
```

- [ ] **Step 5: Run the focused test and verify GREEN**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ApplicationDisplayNameTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run the complete test suite**

Run:

```bash
./gradlew test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` with no failed tasks.

- [ ] **Step 7: Launch and inspect the application**

Run:

```bash
./gradlew :desktop-app:run
```

Expected: the macOS application menu and window title both show
`AgentPerf Inspector`; neither shows `MainKt`.

- [ ] **Step 8: Commit**

```bash
git add \
  desktop-viewer/desktop-app/build.gradle.kts \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/Main.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationDisplayNameTest.kt
git commit
```

Use a Lore-protocol commit message that records the compatibility constraint,
focused test, full suite, and macOS smoke test.
