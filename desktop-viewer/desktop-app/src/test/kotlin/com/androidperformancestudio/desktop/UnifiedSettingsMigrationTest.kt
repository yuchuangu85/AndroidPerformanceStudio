package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class UnifiedSettingsMigrationTest {
    @Test
    fun `unified settings is a routed page instead of a dialog window`() {
        val page = desktopSource("DesktopAppSettingsPage.kt")
        val shell = desktopSource("DesktopAppMainPage.kt")

        assertFalse(page.contains("DialogWindow("))
        assertFalse(page.contains("rememberDialogState("))
        assertTrue(page.contains("Modifier.fillMaxSize()"))
        assertTrue(page.contains("UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 176"))
        assertTrue(shell.contains("AppDestination.SETTINGS ->"))
        assertTrue(shell.contains("DesktopAppSettingsPage("))
        assertTrue(shell.contains("navigator.open(AppDestination.SETTINGS)"))
        assertFalse(shell.contains("showSettings"))
    }

    @Test
    fun `layout inspector complete settings are embedded`() {
        val content = layoutInspectorSource("LayoutInspectorSettingsContent.kt")

        listOf(
            "hideInvisibleHierarchyViews",
            "hideInvisibleFindings",
            "hideHierarchyIndices",
            "showHierarchyIds",
            "showHierarchyLayerVisibilityButtons",
            "showVisibleViewBounds",
            "canvasHitTestOrder",
            "snapshotSizeMultiplier",
            "canvasColorPresets",
            "CanvasBorderColors().normal",
            "CanvasBorderColors().hovered",
            "CanvasBorderColors().selected",
        ).forEach { setting ->
            assertTrue(content.contains(setting), "Missing Layout Inspector setting: $setting")
        }
    }

    @Test
    fun `simpleperf complete settings and live capture context are embedded`() {
        val dialog = desktopSource("DesktopAppSettingsPage.kt")
        val shell = desktopSource("DesktopAppMainPage.kt")
        val settings = simpleperfPresentationSource("CaptureSettingsSection.kt")
        val workspace = simpleperfDesktopSource("SimpleperfMainPage.kt")

        listOf(
            "SAMPLING_TEMPLATE",
            "CAPTURE_CONFIGURATION",
            "ADVANCED_PARAMETERS",
            "FLAME_GRAPH",
            "SIMPLEPERF_ENGINE",
            "USER_GUIDE",
        ).forEach { section -> assertTrue(settings.contains(section), "Missing Simpleperf section: $section") }
        assertTrue(settings.contains("fun SimpleperfSettingsSectionContent("))
        // The Simpleperf sections are merged into one page: each category is a card, so the settings
        // sidebar no longer nests them.
        assertTrue(dialog.contains("SimpleperfSettingsSectionContent("))
        assertTrue(dialog.contains("items(CaptureSettingsSection.entries"))
        assertTrue(dialog.contains("embedded = true"))
        assertTrue(dialog.contains("StudioPanel("))
        assertFalse(dialog.contains("simpleperfExpanded"))
        assertFalse(dialog.contains("onSimpleperfSectionSelected"))
        assertTrue(dialog.contains("width(UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP.dp)"))
        assertTrue(dialog.contains("UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 176"))
        assertFalse(
            dialog.contains("import com.androidperformancestudio.presentation.SimpleperfSettingsContent"),
        )
        assertTrue(dialog.contains("context?.onSelectTemplate"))
        assertTrue(dialog.contains("context?.onUpdateSamplingParameters"))
        assertTrue(workspace.contains("onCaptureSettingsContextChanged"))
        assertTrue(workspace.contains("captureSettingsManagedExternally = onOpenPreferences != null"))
        assertTrue(shell.contains("simpleperfCaptureSettingsContext = simpleperfCaptureSettingsContext"))
        assertTrue(shell.contains("simpleperfSettingsSection = section"))
    }

    @Test
    fun `hprof engine configuration reuses the shared choice control and localized options`() {
        val configuration =
            desktopSource("DesktopAppSettingsPage.kt")
                .substringAfter("private fun ConfigurationSettingsContent(")
                .substringBefore("private fun ThemeColorSettingsContent(")

        assertTrue(configuration.contains("SettingsChoice("))
        assertTrue(configuration.contains("Res.string.hprof_analysis_engine"))
        assertTrue(configuration.contains("current = settings.hprofEngine"))
        assertTrue(configuration.contains("options = HprofEngine.entries"))
        assertTrue(configuration.contains("HprofEngine.LEGACY -> Res.string.hprof_engine_legacy"))
        assertTrue(configuration.contains("HprofEngine.RUST -> Res.string.hprof_engine_rust"))
        assertTrue(configuration.contains("onSettingsChanged(settings.copy(hprofEngine = it))"))

        val english = desktopStrings("values")
        val chinese = desktopStrings("values-zh")
        assertEquals("HPROF analysis engine", english["hprof_analysis_engine"])
        assertEquals("HPROF \u5206\u6790\u5f15\u64ce", chinese["hprof_analysis_engine"])
        assertEquals("Legacy", english["hprof_engine_legacy"])
        assertEquals("Legacy\uFF08\u65E7\u7248\uFF09", chinese["hprof_engine_legacy"])
        assertEquals("Rust", english["hprof_engine_rust"])
        assertEquals("Rust", chinese["hprof_engine_rust"])
    }

    @Test
    fun `hprof engine changes reach the remembered controller without restarting initial imports`() {
        val shell = desktopSource("DesktopAppMainPage.kt")
        val memoryPage =
            Files.readString(
                Path.of(
                    "../memory-profiler/memory-app/src/main/kotlin/" +
                        "com/androidperformancestudio/memory/app/MemoryProfilerMainPage.kt",
                ),
            )
        val controllerCreation = memoryPage.substringAfter("val controller =").substringBefore("DisposableEffect")

        assertTrue(shell.contains("hprofEngine = applicationSettings.hprofEngine"))
        assertTrue(memoryPage.contains("hprofEngine: HprofEngine = HprofEngine.DEFAULT"))
        assertTrue(Regex("remember\\s*\\{").containsMatchIn(controllerCreation))
        assertFalse(Regex("remember\\s*\\([^)]*hprofEngine").containsMatchIn(controllerCreation))
        assertTrue(
            controllerCreation.contains("DesktopMemoryProfilerBackend(language = language, hprofEngine = hprofEngine)"),
        )
        assertTrue(
            memoryPage.contains(
                "LaunchedEffect(controller, hprofEngine) { controller.changeHprofEngine(hprofEngine) }",
            ),
        )
        val initialImportEffect = "LaunchedEffect(initialImportFile, initialImportIsJavaHeap, initialImportRequestId)"
        assertTrue(memoryPage.contains(initialImportEffect))
        assertTrue(memoryPage.indexOf("controller.changeHprofEngine(hprofEngine)") < memoryPage.indexOf(initialImportEffect))
    }

    private fun desktopStrings(localeDirectory: String): Map<String, String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(Path.of("src/main/composeResources/$localeDirectory/strings.xml").toFile())
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val element = strings.item(index)
            element.attributes.getNamedItem("name").nodeValue to element.textContent
        }
    }

    private fun desktopSource(fileName: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/$fileName"))

    private fun layoutInspectorSource(fileName: String): String =
        Files.readString(
            Path.of("../layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/$fileName"),
        )

    private fun simpleperfPresentationSource(fileName: String): String =
        Files.readString(
            Path.of(
                "../simpleperf-viewer/presentation/src/main/kotlin/" +
                    "com/androidperformancestudio/presentation/$fileName",
            ),
        )

    private fun simpleperfDesktopSource(fileName: String): String =
        Files.readString(
            Path.of(
                "../simpleperf-viewer/app-desktop/src/main/kotlin/" +
                    "com/androidperformancestudio/desktop/$fileName",
            ),
        )
}
