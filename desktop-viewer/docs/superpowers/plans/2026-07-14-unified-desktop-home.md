# Unified Desktop Home Implementation Plan

> **Directory-boundary update (2026-07-14):** The neutral executable shell now lives at root
> `desktop-app/`; Layout Inspector UI is `:layout-inspector:presentation`, and its remaining
> modules are namespaced below `:layout-inspector:*`. Root `desktop-app` references below
> remain valid, but unqualified Layout Inspector `application`, `adb-gateway`, and `shared-kernel`
> paths must be translated to their feature namespace before executing later tasks.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one Compose Desktop application that opens on a home page and navigates in-window to the existing Layout Inspector or Simpleperf CPU Profiler, with a fixed return-home action.

**Architecture:** Replace the Simpleperf composite build with namespaced projects in the root Gradle build while retaining its physical `simpleperf-viewer/` directory. Convert the former Simpleperf executable into an embeddable `SimpleperfWorkspace`, then make `desktop-app` the sole application shell and native package owner.

**Tech Stack:** Kotlin/JVM 2.3.21, Compose Multiplatform 1.11.1, Material 3, Gradle 9.4.1, JUnit 5, kotlinx.coroutines, protobuf, SQLite JDBC, ktlint, detekt.

## Global Constraints

- `desktop-app` is the only executable and native packaging module.
- The initial application route is `HOME`.
- Both features render in the same window and expose a fixed `返回主页` action.
- Feature workspaces retain their remembered state after returning home during the same process session.
- Simpleperf implementation stays under `simpleperf-viewer/` and uses `com.androidperformancestudio` packages.
- Layout Inspector and Simpleperf implementation modules must not depend on each other.
- The unified package includes both `java.net.http` and `java.sql` runtime modules.
- Do not redesign the existing Layout Inspector or Simpleperf workflows.

## File and responsibility map

- `settings.gradle.kts` — registers namespaced Simpleperf projects in the root build.
- `gradle/libs.versions.toml` — owns unified plugin and library versions.
- `build.gradle.kts` — configures Kotlin, lint, static analysis, and tests for Simpleperf projects.
- `simpleperf-viewer/*/build.gradle.kts` — declares namespaced feature dependencies.
- `simpleperf-viewer/app-desktop/.../SimpleperfWorkspace.kt` — embeds Simpleperf controllers and UI without owning a process or window.
- `desktop-app/.../AppDestination.kt` — application navigation state and visited-workspace tracking.
- `desktop-app/.../AppHomePage.kt` — two-card home page.
- `desktop-app/.../UnifiedDesktopApp.kt` — retained feature layers and shared return-home frame.
- `desktop-app/.../Main.kt` — sole process/window entry point.
- `desktop-app/build.gradle.kts` — sole native distribution and runtime module declaration.

---

### Task 1: Application navigation model

**Files:**
- Create: `desktop-app/src/main/kotlin/dev/agentperf/desktop/AppDestination.kt`
- Create: `desktop-app/src/test/kotlin/dev/agentperf/desktop/AppNavigatorTest.kt`

**Interfaces:**
- Consumes: Compose runtime state already available to `desktop-app`.
- Produces: `AppDestination`, `AppNavigator.destination`, `open`, `returnHome`, and `hasVisited`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNavigatorTest {
    @Test
    fun `application starts on the home page`() {
        assertEquals(AppDestination.HOME, AppNavigator().destination)
    }

    @Test
    fun `returning home preserves the visited feature`() {
        val navigator = AppNavigator()
        navigator.open(AppDestination.SIMPLEPERF)
        assertEquals(AppDestination.SIMPLEPERF, navigator.destination)
        assertTrue(navigator.hasVisited(AppDestination.SIMPLEPERF))
        navigator.returnHome()
        assertEquals(AppDestination.HOME, navigator.destination)
        assertTrue(navigator.hasVisited(AppDestination.SIMPLEPERF))
        assertFalse(navigator.hasVisited(AppDestination.LAYOUT_INSPECTOR))
    }

    @Test
    fun `home is not recorded as a feature workspace`() {
        val navigator = AppNavigator()
        navigator.open(AppDestination.HOME)
        assertFalse(navigator.hasVisited(AppDestination.HOME))
    }
}
```

- [ ] **Step 2: Verify RED**

Run `./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.AppNavigatorTest' --no-daemon`.
Expected: compilation fails because the navigation types do not exist.

- [ ] **Step 3: Add the minimal model**

```kotlin
package dev.agentperf.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppDestination(val featureTitle: String?) {
    HOME(null),
    LAYOUT_INSPECTOR("Layout Inspector"),
    SIMPLEPERF("Simpleperf CPU Profiler"),
}

class AppNavigator(initialDestination: AppDestination = AppDestination.HOME) {
    private val visited = linkedSetOf<AppDestination>()

    var destination by mutableStateOf(initialDestination)
        private set

    init {
        if (initialDestination != AppDestination.HOME) visited += initialDestination
    }

    fun open(destination: AppDestination) {
        this.destination = destination
        if (destination != AppDestination.HOME) visited += destination
    }

    fun returnHome() {
        destination = AppDestination.HOME
    }

    fun hasVisited(destination: AppDestination): Boolean = destination in visited
}
```

- [ ] **Step 4: Verify GREEN**

Repeat Step 2. Expected: three tests pass.

- [ ] **Step 5: Commit**

Use Lore intent `Establish explicit navigation for the unified desktop shell` and record the focused test in `Tested:`.

---

### Task 2: Move Simpleperf modules into the root Gradle build

**Files:**
- Create: `desktop-app/src/test/kotlin/dev/agentperf/desktop/UnifiedBuildStructureTest.kt`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`
- Modify: every `simpleperf-viewer/*/build.gradle.kts`
- Replace: `simpleperf-viewer/build.gradle.kts`
- Delete: `simpleperf-viewer/settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, and `gradle/wrapper/`

**Interfaces:**
- Consumes: root wrapper and version catalog.
- Produces: namespaced projects such as `:features:simpleperf-viewer:app-desktop` and `:features:simpleperf-viewer:checkAll`.

- [ ] **Step 1: Write failing build tests**

```kotlin
package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedBuildStructureTest {
    private val root = Path.of("..")

    @Test
    fun `Simpleperf modules belong to the root build`() {
        val settings = Files.readString(root.resolve("settings.gradle.kts"))
        assertFalse(settings.contains("includeBuild(\"simpleperf-viewer\")"))
        assertTrue(settings.contains("\":features:simpleperf-viewer:app-desktop\""))
        assertTrue(settings.contains("\":features:simpleperf-viewer:presentation\""))
        assertTrue(settings.contains("\":features:simpleperf-viewer:profile-model\""))
    }

    @Test
    fun `Simpleperf has no nested Gradle root`() {
        val feature = root.resolve("simpleperf-viewer")
        assertFalse(Files.exists(feature.resolve("settings.gradle.kts")))
        assertFalse(Files.exists(feature.resolve("gradlew")))
        assertFalse(Files.exists(feature.resolve("gradle/wrapper/gradle-wrapper.properties")))
    }

    @Test
    fun `Simpleperf project dependencies are namespaced`() {
        Files.walk(root.resolve("simpleperf-viewer")).use { paths ->
            val invalid = paths
                .filter { it.fileName.toString() == "build.gradle.kts" }
                .filter {
                    Regex("project\\(\\\":(?!features:simpleperf-viewer:)")
                        .containsMatchIn(Files.readString(it))
                }
                .toList()
            assertTrue(invalid.isEmpty(), "Unnamespaced project dependencies: $invalid")
        }
    }
}
```

- [ ] **Step 2: Verify RED**

Run `./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.UnifiedBuildStructureTest' --no-daemon`.
Expected: assertions fail for the composite include, nested wrapper, and old project paths.

- [ ] **Step 3: Register namespaced projects**

Replace the composite include in `settings.gradle.kts` with:

```kotlin
val simpleperfModules = listOf(
    "analysis-rules", "app-desktop", "application", "capture-simpleperf",
    "device-adb", "export-adapters", "parser-simpleperf-proto",
    "platform-toolchain", "presentation", "profile-model", "storage-sqlite",
    "test-fixtures", "visualization",
)
include(":features:simpleperf-viewer")
simpleperfModules.forEach { module ->
    val path = ":features:simpleperf-viewer:$module"
    include(path)
    project(path).projectDir = file("simpleperf-viewer/$module")
}
```

- [ ] **Step 4: Unify dependency versions**

Set `coroutines = "1.11.0"` and add `detekt = "1.23.8"`, `ktlint = "14.2.0"`, `protobuf = "4.35.1"`, `protobuf-plugin = "0.10.0"`, and `sqlite = "3.53.1.0"`. Add:

```toml
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
protobuf-java = { module = "com.google.protobuf:protobuf-java", version.ref = "protobuf" }
sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version.ref = "sqlite" }
compose-material3 = { module = "org.jetbrains.compose.material3:material3", version = "1.11.0-alpha07" }
compose-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "compose" }
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }
protobuf = { id = "com.google.protobuf", version.ref = "protobuf-plugin" }
```

- [ ] **Step 5: Configure feature projects from the root**

Add the three plugin aliases with `apply false`. Remove composite proxy tasks. Configure feature subprojects with:

```kotlin
configure(subprojects.filter { it.path.startsWith(":features:simpleperf-viewer:") }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("simpleperf-viewer/config/detekt/detekt.yml"))
    }
    dependencies.add("testImplementation", kotlin("test"))
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
```

Required imports are `DetektExtension`, `Test`, `JvmTarget`, and `KotlinJvmProjectExtension`.

- [ ] **Step 6: Namespace module dependencies**

Run this exact transformation, then replace literal third-party coordinates with Step 4 aliases:

```python
from pathlib import Path
root = Path("simpleperf-viewer")
modules = {p.name for p in root.iterdir() if p.is_dir() and (p / "build.gradle.kts").exists()}
for script in root.glob("*/build.gradle.kts"):
    text = script.read_text()
    for module in modules:
        text = text.replace(f'project(":{module}")', f'project(":features:simpleperf-viewer:{module}")')
    script.write_text(text)
```

- [ ] **Step 7: Make the feature root an aggregator**

```kotlin
tasks.register("checkAll") {
    group = "verification"
    description = "Runs every Simpleperf feature check from the unified root build."
    dependsOn(subprojects.map { project -> "${project.path}:check" })
}
```

- [ ] **Step 8: Delete nested root artifacts and verify GREEN**

Delete the files listed above, repeat Step 2, then run `./gradlew :features:simpleperf-viewer:checkAll --no-daemon`.
Expected: both commands succeed.

- [ ] **Step 9: Commit**

Use Lore intent `Compile both performance tools under one dependency boundary`, recording both verification commands.

---

### Task 3: Convert Simpleperf into an embeddable workspace

**Files:**
- Create: `simpleperf-viewer/app-desktop/src/main/kotlin/com/androidperformancestudio/desktop/SimpleperfWorkspace.kt`
- Create: `desktop-app/src/test/kotlin/dev/agentperf/desktop/SimpleperfEmbeddingTest.kt`
- Modify: `simpleperf-viewer/app-desktop/build.gradle.kts`
- Delete: `simpleperf-viewer/app-desktop/src/main/kotlin/com/androidperformancestudio/desktop/Main.kt`

**Interfaces:**
- Consumes: `ComposeWindow` and existing Simpleperf controllers.
- Produces: `@Composable fun SimpleperfWorkspace(window: ComposeWindow)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimpleperfEmbeddingTest {
    private val module = Path.of("../simpleperf-viewer/app-desktop")

    @Test
    fun `Simpleperf exposes a workspace instead of a second application`() {
        val workspace = module.resolve("src/main/kotlin/com/androidperformancestudio/desktop/SimpleperfWorkspace.kt")
        val oldMain = module.resolve("src/main/kotlin/com/androidperformancestudio/desktop/Main.kt")
        val buildScript = Files.readString(module.resolve("build.gradle.kts"))
        assertTrue(Files.exists(workspace))
        assertTrue(Files.readString(workspace).contains("fun SimpleperfWorkspace(window: ComposeWindow)"))
        assertFalse(Files.exists(oldMain))
        assertFalse(buildScript.contains("compose.desktop"))
        assertFalse(buildScript.contains("mainClass"))
    }
}
```

- [ ] **Step 2: Verify RED**

Run `./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.SimpleperfEmbeddingTest' --no-daemon`.
Expected: assertions fail because the workspace does not exist and a second application remains.

- [ ] **Step 3: Extract `SimpleperfWorkspace`**

Create the new file from the existing `Main.kt`. Remove `application` and `Window`; retain dependency helpers unchanged. The public composition is:

```kotlin
@Composable
@Suppress("FunctionName")
fun SimpleperfWorkspace(window: ComposeWindow) {
    val dependencies = remember { createWorkspaceDependencies() }
    val controller = remember(dependencies) {
        DeviceTargetController(dependencies.deviceGateway, dependencies.captureSession)
    }
    val reportController = remember { ReportController() }
    val sessionPackages = remember { SessionPackageService() }
    val reportExports = remember { ReportExportService() }
    val offlineImporter = remember { createOfflineImporter() }
    val state by controller.state.collectAsState()
    val captureState by controller.captureState.collectAsState()
    val reportState by reportController.state.collectAsState()
    val scope = rememberCoroutineScope()
    val reportActionFactory = remember(reportController, sessionPackages, reportExports, scope, window) {
        DesktopReportActionFactory(
            reportController, sessionPackages, reportExports,
            ::createOfflineImporter, scope, window,
        )
    }
    LaunchedEffect(controller) { controller.refreshDevices() }
    HomeScreen(
        state = state,
        captureState = captureState,
        reportState = reportState,
        actions = controller.deviceActions(scope, reportController, offlineImporter),
        reportActions = reportActionFactory.create(reportState),
    )
}
```

- [ ] **Step 4: Remove executable configuration**

Keep Compose plugins and namespaced dependencies in `app-desktop/build.gradle.kts`; delete the `TargetFormat` import and entire `compose.desktop { application { ... } }` block. Delete old `Main.kt`.

- [ ] **Step 5: Verify GREEN**

Run `./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.SimpleperfEmbeddingTest' :features:simpleperf-viewer:app-desktop:test --no-daemon`.
Expected: success.

- [ ] **Step 6: Commit**

Use Lore intent `Make CPU profiling composable inside the shared desktop process`.

---

### Task 4: Add the home page and retained shell

**Files:**
- Create: `desktop-app/src/main/kotlin/dev/agentperf/desktop/AppHomePage.kt`
- Create: `desktop-app/src/main/kotlin/dev/agentperf/desktop/UnifiedDesktopApp.kt`
- Create: `desktop-app/src/test/kotlin/dev/agentperf/desktop/UnifiedDesktopShellTest.kt`
- Modify: `desktop-app/src/main/kotlin/dev/agentperf/desktop/Main.kt`
- Modify: `desktop-app/build.gradle.kts`

**Interfaces:**
- Consumes: `AppNavigator`, `DesktopViewerApp`, `SimpleperfWorkspace`.
- Produces: `AppHomePage` and `FrameWindowScope.UnifiedDesktopApp`.

- [ ] **Step 1: Write failing wiring tests**

```kotlin
package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedDesktopShellTest {
    private val sourceRoot = Path.of("src/main/kotlin/dev/agentperf/desktop")

    @Test
    fun `main opens the unified shell`() {
        assertTrue(Files.readString(sourceRoot.resolve("Main.kt"))
            .contains("UnifiedDesktopApp(settingsRequest = settingsRequest)"))
    }

    @Test
    fun `shell wires both features and return home`() {
        val shell = Files.readString(sourceRoot.resolve("UnifiedDesktopApp.kt"))
        val home = Files.readString(sourceRoot.resolve("AppHomePage.kt"))
        assertTrue(shell.contains("DesktopViewerApp(settingsRequest = settingsRequest)"))
        assertTrue(shell.contains("SimpleperfWorkspace(window)"))
        assertTrue(shell.contains("navigator.returnHome()"))
        assertTrue(shell.contains("navigator.hasVisited(AppDestination.LAYOUT_INSPECTOR)"))
        assertTrue(shell.contains("navigator.hasVisited(AppDestination.SIMPLEPERF)"))
        assertTrue(home.contains("Layout Inspector"))
        assertTrue(home.contains("Simpleperf CPU Profiler"))
    }
}
```

- [ ] **Step 2: Verify RED**

Run `./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.UnifiedDesktopShellTest' --no-daemon`.
Expected: test execution fails because shell files do not exist.

- [ ] **Step 3: Link the Simpleperf workspace**

Add `implementation(project(":features:simpleperf-viewer:app-desktop"))` to `desktop-app`.

- [ ] **Step 4: Implement `AppHomePage`**

Expose:

```kotlin
@Composable
fun AppHomePage(
    onOpenLayoutInspector: () -> Unit,
    onOpenSimpleperf: () -> Unit,
)
```

Use a Material 3 `Surface` and centered `Column`. Add a title `Android Performance Studio`, subtitle `选择要使用的性能分析工具`, and a `Row` with two equal-width `Card`s. The first title/description/button are `Layout Inspector`, `检查 Android View 层级、截图、边界与属性。`, `进入布局检查`. The second are `Simpleperf CPU Profiler`, `采集或打开 Simpleperf 会话并分析 CPU 样本。`, `进入 CPU 分析`. Each button invokes the corresponding callback.

- [ ] **Step 5: Implement retained feature layers**

Expose:

```kotlin
@Composable
fun FrameWindowScope.UnifiedDesktopApp(settingsRequest: Long = 0L)
```

Remember one `AppNavigator`. When a positive settings request arrives, open `LAYOUT_INSPECTOR`. Use a full-size `Box`. After a feature has been visited, always emit its `RetainedFeatureLayer`; set layer `alpha/zIndex` to `1/1` when selected and `0/-1` otherwise. Show `AppHomePage` at z-index zero when destination is `HOME`.

`RetainedFeatureLayer` is a full-size `Column`. Its fixed top `Surface` shows `Android Performance Studio`, the feature title, and an `OutlinedButton` labeled `返回主页`. Its content fills the remaining space. Invoke `DesktopViewerApp(settingsRequest)` in the layout layer and `SimpleperfWorkspace(window)` in the CPU layer.

- [ ] **Step 6: Use the shell from the sole window**

Replace the `DesktopViewerApp(...)` call in `Main.kt` with `UnifiedDesktopApp(...)`; preserve icon, native menu, close behavior, and minimum size.

- [ ] **Step 7: Verify GREEN**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.AppNavigatorTest' --tests 'dev.agentperf.desktop.UnifiedDesktopShellTest' --no-daemon
./gradlew :desktop-app:test --no-daemon
```

Expected: both succeed.

- [ ] **Step 8: Commit**

Use Lore intent `Give both performance workflows one navigable home`.

---

### Task 5: Make packaging, CI, and docs single-application only

**Files:**
- Modify: `desktop-app/src/test/kotlin/dev/agentperf/desktop/PackagedRuntimeModulesTest.kt`
- Modify: `desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt`
- Modify: `desktop-app/build.gradle.kts`, `README.md`
- Modify: `../.github/workflows/release.yml`

**Interfaces:**
- Consumes: unified build and `desktop-app` distribution.
- Produces: one CI test graph and one runtime image.

- [ ] **Step 1: Extend tests first**

Add:

```kotlin
@Test
fun `packaged runtime includes HTTP and SQLite modules`() {
    val buildScript = Files.readString(Path.of("build.gradle.kts"))
    assertTrue(buildScript.contains("modules(\"java.net.http\", \"java.sql\")"))
}
```

Update `ReleaseWorkflowTest` to assert the test command equals `./desktop-viewer/gradlew -p desktop-viewer test --no-daemon` and that it does not contain `simpleperfCheck`.

- [ ] **Step 2: Verify RED**

Run `./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.PackagedRuntimeModulesTest' --tests 'dev.agentperf.desktop.ReleaseWorkflowTest' --no-daemon`.
Expected: assertions fail for `java.sql` and the obsolete proxy task.

- [ ] **Step 3: Update packaging and CI**

Change native modules to `modules("java.net.http", "java.sql")`. Change the workflow test command to `./desktop-viewer/gradlew -p desktop-viewer test --no-daemon`.

- [ ] **Step 4: Update docs**

`README.md` must describe one application, the two-entry home, `./gradlew :desktop-app:run`, one `createDistributable` command, the root shell boundary, and both direct feature directories. Remove all three `simpleperf*` commands.

- [ ] **Step 5: Verify GREEN**

Repeat Step 2. Expected: success.

- [ ] **Step 6: Commit**

Use Lore intent `Ship the unified workspace as one native application`.

---

### Task 6: Full verification and smoke

**Files:**
- Modify only files required by reproduced failures; add a failing regression test before every correction.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: verified unified application.

- [ ] **Step 1: Run all tests and checks**

Run `./gradlew test check --no-daemon`.
Expected: success with Layout Inspector and every Simpleperf test in one root graph.

- [ ] **Step 2: Build the sole image**

Run `./gradlew :desktop-app:createDistributable --no-daemon`.
Expected: only `desktop-app/build/compose/binaries/main/app/AndroidPerfermanceStudio.app` is produced.

- [ ] **Step 3: Smoke startup**

```bash
log="$(mktemp)"
desktop-app/build/compose/binaries/main/app/AndroidPerfermanceStudio.app/Contents/MacOS/AndroidPerfermanceStudio >"$log" 2>&1 &
pid=$!
sleep 5
kill -0 "$pid"
kill "$pid"
wait "$pid" 2>/dev/null || true
test ! -s "$log"
rm -f "$log"
```

Expected: process stays alive and the log is empty.

- [ ] **Step 4: Verify boundaries**

```bash
git diff --check
test ! -e simpleperf-viewer/settings.gradle.kts
test ! -e simpleperf-viewer/gradlew
! grep -R --line-number --exclude-dir=build 'dev\.agentperf' simpleperf-viewer/*/src
! grep -R --line-number --exclude-dir=build 'com\.androidperformancestudio' application adb-gateway shared-kernel
git status --short
```

Expected: no whitespace errors, nested build root, or cross-feature package references.

- [ ] **Step 5: Navigation smoke**

Run `./gradlew :desktop-app:run`. Verify home appears; open Layout Inspector, return home, open Simpleperf, and return home. Confirm one native window remains responsive.

- [ ] **Step 6: Commit only proven corrections**

If verification required changes, use a Lore commit with exact tests. Do not create an empty commit.

## Completion evidence

- Navigation tests prove default, entry, return, and visited-workspace behavior.
- Build tests prove one namespaced Gradle build.
- Embedding tests prove Simpleperf no longer owns a process or package.
- Shell tests prove both entries and return-home wiring.
- Packaging tests prove HTTP and JDBC runtime modules.
- Full tests, checks, packaging, startup smoke, and navigation smoke pass.
