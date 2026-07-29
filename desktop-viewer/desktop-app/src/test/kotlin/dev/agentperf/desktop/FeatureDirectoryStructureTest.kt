package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureDirectoryStructureTest {
    private val root = findGradleRoot()

    @Test
    fun `Layout Inspector code has one feature owner directory`() {
        val feature = root.resolve("layout-inspector")

        listOf("adb-gateway", "application", "shared-kernel", "samples").forEach {
            assertTrue(Files.isDirectory(feature.resolve(it)), "Missing Layout Inspector directory: $it")
            assertFalse(Files.exists(root.resolve(it)), "Legacy Layout Inspector directory still exists: $it")
        }
        assertTrue(Files.isDirectory(feature.resolve("presentation")))
    }

    @Test
    fun `root desktop app exclusively owns process entry and native packaging`() {
        val shell = root.resolve("desktop-app")
        val layoutUi = root.resolve("layout-inspector/presentation")
        val mainSource = "src/main/kotlin/dev/agentperf/desktop/Main.kt"

        assertTrue(Files.isRegularFile(shell.resolve(mainSource)))
        assertTrue(Files.isDirectory(shell.resolve("src/main/package")))
        assertFalse(Files.exists(layoutUi.resolve(mainSource)))
        assertFalse(Files.exists(layoutUi.resolve("src/main/package")))

        val shellBuild = Files.readString(shell.resolve("build.gradle.kts"))
        val layoutBuild = Files.readString(layoutUi.resolve("build.gradle.kts"))
        assertTrue(shellBuild.contains("compose.desktop {"))
        assertTrue(shellBuild.contains("nativeDistributions"))
        assertTrue(
            shellBuild.contains(
                "project(\":layout-inspector:presentation\")",
            ),
        )
        assertFalse(layoutBuild.contains("compose.desktop {"))
        assertFalse(layoutBuild.contains("nativeDistributions"))
    }

    @Test
    fun `root desktop app owns the unified home shell`() {
        val shellSource = root.resolve("desktop-app/src/main/kotlin/dev/agentperf/desktop")

        assertTrue(Files.isRegularFile(shellSource.resolve("AppHomePage.kt")))
        assertTrue(Files.isRegularFile(shellSource.resolve("ApplicationUiSettings.kt")))
        assertTrue(Files.isRegularFile(shellSource.resolve("ApplicationSettingsDialog.kt")))
        assertFalse(Files.exists(shellSource.resolve("GlobalSettingsBar.kt")))
        assertTrue(Files.isRegularFile(shellSource.resolve("DesktopAppMainPage.kt")))
        assertFalse(Files.exists(root.resolve("layout-inspector/presentation/src/main/kotlin/dev/agentperf/desktop/AppHomePage.kt")))
        assertFalse(Files.exists(root.resolve("simpleperf-viewer/app-desktop/src/main/kotlin/dev/agentperf/desktop/AppHomePage.kt")))
    }

    @Test
    fun `Simpleperf code has one feature owner directory`() {
        val feature = root.resolve("simpleperf-viewer")

        assertTrue(Files.isDirectory(feature.resolve("app-desktop")))
        assertTrue(Files.isDirectory(feature.resolve("capture-simpleperf")))
        assertTrue(Files.isDirectory(feature.resolve("parser-simpleperf-proto")))
        assertFalse(Files.exists(root.resolve("features")))
    }

    @Test
    fun `Memory Profiler code has one composite feature directory`() {
        val feature = root.resolve("memory-profiler")

        listOf(
            "memory-model",
            "capture-memory",
            "parser-hprof",
            "analysis-memory",
            "storage-sqlite",
            "export-adapters",
            "presentation",
            "memory-app",
        ).forEach { module ->
            assertTrue(Files.isDirectory(feature.resolve(module)), "Missing Memory Profiler module: $module")
        }
        val settings = Files.readString(root.resolve("settings.gradle.kts"))
        val shellBuild = Files.readString(root.resolve("desktop-app/build.gradle.kts"))
        assertTrue(settings.contains("includeBuild(\"memory-profiler\")"))
        assertTrue(shellBuild.contains("com.androidperformancestudio.memory:memory-app"))
    }

    private fun findGradleRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
}
