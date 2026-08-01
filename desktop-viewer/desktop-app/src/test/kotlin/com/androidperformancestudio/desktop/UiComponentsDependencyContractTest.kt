package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiComponentsDependencyContractTest {
    @Test
    fun `every Compose application and presentation module uses the public UI library`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()

        UI_MODULE_BUILD_FILES.forEach { relativePath ->
            val buildFile = desktopViewer.resolve(relativePath)
            val buildScript = Files.readString(buildFile)
            assertTrue(
                buildScript.contains(UI_COMPONENTS_DEPENDENCY),
                "$relativePath must depend on the shared UI component library",
            )
        }
    }

    @Test
    fun `legacy CPU profiler scoped UI dependency is removed`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        UI_MODULE_BUILD_FILES.forEach { relativePath ->
            assertFalse(
                Files.readString(desktopViewer.resolve(relativePath)).contains("desktop-ui"),
                "$relativePath must not use the legacy CPU profiler scoped UI module",
            )
        }
        assertFalse(Files.exists(desktopViewer.resolve("simpleperf-viewer/desktop-ui/build.gradle.kts")))
    }

    @Test
    fun `shared drawable resources are public and consumed through the UI component Res class`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val uiComponentsBuild = Files.readString(desktopViewer.resolve("ui-components/build.gradle.kts"))
        val settingsDialog =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppSettingsDialog.kt"),
            )

        assertTrue(uiComponentsBuild.contains("publicResClass = true"))
        assertTrue(Files.exists(desktopViewer.resolve("ui-components/src/main/composeResources/drawable/icon_expand.svg")))
        assertTrue(Files.exists(desktopViewer.resolve("ui-components/src/main/composeResources/drawable/icon_collapse.svg")))
        assertTrue(settingsDialog.contains("UiComponentsRes.drawable.icon_expand"))
        assertTrue(settingsDialog.contains("UiComponentsRes.drawable.icon_collapse"))
        assertTrue(settingsDialog.contains("tint ="))
        assertTrue(settingsDialog.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
    }

    @Test
    fun `production UI APIs use the extensible language enum instead of a Chinese flag`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        Files.walk(desktopViewer).use { paths ->
            val legacyLanguageParameters =
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.toString().endsWith(".kt") }
                    .filter { it.toString().contains("/src/main/") }
                    .filter { Files.readString(it).contains(LEGACY_LANGUAGE_PARAMETER) }
                    .toList()

            assertTrue(
                legacyLanguageParameters.isEmpty(),
                "Production UI APIs must use UiLanguage: $legacyLanguageParameters",
            )
        }
    }

    @Test
    fun `production UI modules share UiLanguage instead of defining scoped language enums`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val uiModuleDirectories =
            UI_MODULE_BUILD_FILES.map { relativePath ->
                desktopViewer.resolve(relativePath).parent
            }
        Files.walk(desktopViewer).use { paths ->
            val scopedLanguageEnums =
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.toString().endsWith(".kt") }
                    .filter { it.toString().contains("/src/main/") }
                    .filter { path -> uiModuleDirectories.any(path::startsWith) }
                    .filter { SCOPED_LANGUAGE_ENUM.containsMatchIn(Files.readString(it)) }
                    .toList()

            assertTrue(
                scopedLanguageEnums.isEmpty(),
                "Production UI modules must share UiLanguage: $scopedLanguageEnums",
            )
        }
    }

    private companion object {
        const val UI_COMPONENTS_DEPENDENCY =
            "com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT"
        const val LEGACY_LANGUAGE_PARAMETER = "chinese: Boolean"
        val SCOPED_LANGUAGE_ENUM = Regex("""enum class \w+Language(?:\s|\()""")

        val UI_MODULE_BUILD_FILES =
            listOf(
                "desktop-app/build.gradle.kts",
                "layout-inspector/presentation/build.gradle.kts",
                "simpleperf-viewer/app-desktop/build.gradle.kts",
                "simpleperf-viewer/presentation/build.gradle.kts",
                "simpleperf-viewer/visualization/build.gradle.kts",
                "perfetto-viewer/perfetto-app/build.gradle.kts",
                "perfetto-viewer/perfetto-presentation/build.gradle.kts",
                "perfetto-viewer/presentation/build.gradle.kts",
                "memory-profiler/memory-app/build.gradle.kts",
                "memory-profiler/presentation/build.gradle.kts",
                "frame-profiler/frame-app/build.gradle.kts",
                "frame-profiler/presentation/build.gradle.kts",
                "startup-profiler/startup-app/build.gradle.kts",
                "startup-profiler/presentation/build.gradle.kts",
                "battery-profiler/battery-app/build.gradle.kts",
                "network-profiler/network-app/build.gradle.kts",
                "network-profiler/presentation/build.gradle.kts",
                "gpu-inspector-integration/gpu-integration-app/build.gradle.kts",
                "gpu-inspector-integration/presentation/build.gradle.kts",
                "benchmark-regression/benchmark-app/build.gradle.kts",
                "benchmark-regression/presentation/build.gradle.kts",
            )
    }
}
