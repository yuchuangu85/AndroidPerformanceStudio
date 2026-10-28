package com.androidperformancestudio.startup.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt"),
        )

    @Test
    fun `compact header shares file and refresh actions with the commercial workspace`() {
        assertTrue(source.contains("StartupProfilerFileMenuBar("))
        assertTrue(source.contains("HeaderToolbar"))
        val toolbar = toolbarSource()
        for (action in listOf("onRefresh", "onImport", "onExportCsv", "onExportJson")) {
            assertTrue(toolbar.contains("onClick = actions.$action"), "The header must use $action")
        }
        assertTrue(toolbar.contains("ProfilerToolbarStatus("))
        assertTrue(toolbar.contains("SelectionContainer"))
        assertTrue(toolbar.contains("StartupTargetSelectors("))
        assertTrue(toolbar.contains("TitleBarRefreshOrInline("))
        assertTrue(toolbar.contains("onRefresh = actions.onRefresh"))
        assertFalse(toolbar.contains("DropdownSelector"))
        assertFalse(toolbar.contains("Checkbox"))
        assertFalse(toolbar.contains("onStartStop"))
        assertFalse(source.contains("ProfilerCompactSelector"))
        assertTrue(source.contains("StartupProfilerStatusBar("))
        assertTrue(source.contains("onImport = actions.onImport"))
        assertTrue(source.contains("onExportCsv = actions.onExportCsv"))
        assertTrue(source.contains("onExportJson = actions.onExportJson"))
        assertFalse(source.contains("private fun Selector("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
        assertTrue(source.contains("StartupProfilerScreen("))
        assertTrue(source.contains("actions = actions"))
    }

    @Test
    fun `target selectors lead the header with the device before the application`() {
        val toolbar = toolbarSource()
        val selectors = toolbar.indexOf("StartupTargetSelectors(")
        val firstAction = toolbar.indexOf("ProfilerCompactButton(")
        assertTrue(selectors >= 0 && selectors < firstAction)

        val controls =
            Files.readString(
                Path.of(
                    "..",
                    "presentation/src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupWorkspaceControls.kt",
                ),
            )
        val selectorStart = controls.indexOf("public fun StartupTargetSelectors(")
        val selectorEnd = controls.indexOf("@Composable", selectorStart)
        val selectorsSource = controls.substring(selectorStart, selectorEnd)
        val device = selectorsSource.indexOf("testTag(\"startup-device\")")
        val target = selectorsSource.indexOf("testTag(\"startup-target\")")
        assertTrue(device >= 0, "The header must expose the device selector")
        assertTrue(target > device, "Device must come before the application selector")

        val scenario = controls.substringAfter("internal fun StartupScenarioControls(")
        assertFalse(scenario.contains("testTag(\"startup-device\")"))
        assertFalse(scenario.contains("testTag(\"startup-target\")"))
    }

    @Test
    fun `start button reuses the shared compact action style`() {
        val controls =
            Files.readString(
                Path.of(
                    "..",
                    "presentation/src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupWorkspaceControls.kt",
                ),
            )
        assertFalse(controls.contains("import androidx.compose.material3.Button"))
        assertTrue(controls.contains("ProfilerCompactButton("))
        assertTrue(controls.contains("Modifier.padding(start = 14.dp).testTag(\"startup-start-stop\")"))
        assertFalse(controls.contains("▶"))
        assertFalse(controls.contains("■"))
    }

    @Test
    fun `all experiment configuration actions are forwarded to the existing controller`() {
        val controllerActions =
            mapOf(
                "onSelectRun" to "selectRun",
                "onSelectTarget" to "selectTarget",
                "onSelectStartupType" to "selectStartupType",
                "onSelectCompilationMode" to "selectCompilationMode",
                "onSelectProfileSource" to "selectProfileSource",
                "onUpdateBaselineProfileArtifact" to "updateBaselineProfileArtifact",
                "onUpdateCounts" to "updateCounts",
                "onUpdateTimeout" to "updateTimeout",
                "onSetPerfettoTraceEnabled" to "setPerfettoTraceEnabled",
                "onUpdatePracticalThreshold" to "updatePracticalThreshold",
            )
        for ((action, method) in controllerActions) {
            assertTrue(source.contains("$action = controller::$method"), "Missing $action wiring")
        }
        assertTrue(source.contains("onSelectDevice = { serial -> scope.launch { controller.selectDevice(serial) } }"))
        assertTrue(source.contains("onRefresh = { scope.launch { controller.refreshDevices() } }"))
    }

    @Test
    fun `start stop and compilation confirmation preserve cancellation and duplicate launch safety`() {
        val startStopStart = source.indexOf("onStartStop =")
        val startStop = source.substring(source.blockRangeStartingAt(startStopStart))
        assertTrue(startStop.contains("if (state.isRunning)"))
        assertTrue(startStop.contains("experimentJob?.cancel()"))
        assertTrue(startStop.contains("experimentJob?.isActive != true"))
        assertTrue(startStop.contains("state.selectedComponentName != null"))
        assertTrue(source.contains("controller.runExperiment()"))
        assertTrue(source.contains("controller.runExperiment(compilationChangeConfirmed = true)"))
        assertTrue(source.contains("onDismissRequest = controller::dismissCompilationConfirmation"))
        val navigateStart = source.indexOf("onNavigateHome =")
        val navigate = source.substring(source.blockRangeStartingAt(navigateStart))
        assertTrue(navigate.indexOf("experimentJob?.cancel()") < navigate.indexOf("onBack()"))
    }

    @Test
    fun `startup launch and compilation confirmation do not require a matching running process`() {
        assertFalse(source.contains("packageReady("))
        assertTrue(source.contains("targetHost?.deviceReady(state.selectedDeviceSerial)"))
    }

    @Test
    fun `file dialogs keep asynchronous controller operations and guarded local trace navigation`() {
        assertTrue(source.contains("chooseOpenFile("))
        assertTrue(source.contains("acceptAllFiles = false"))
        assertEquals(2, Regex("chooseSaveFile\\(").findAll(source).count())
        assertTrue(source.contains("scope.launch { controller.importJson(file.toPath()) }"))
        assertTrue(source.contains("scope.launch { controller.exportCsv(file.toPath()) }"))
        assertTrue(source.contains("scope.launch { controller.exportJson(file.toPath()) }"))
        assertTrue(
            source.contains("onOpenTrace = onOpenTrace?.let { open -> { file -> openStartupTraceFile(file, open) } }"),
        )
        assertTrue(source.contains("Files.isRegularFile(path) && runCatching { onOpenTrace(path) }.isSuccess"))
    }

    @Test
    fun `operation status remains selectable in a Layout Inspector style footer`() {
        val contentStart = source.indexOf("StartupProfilerScreen(")
        val footerStart = source.indexOf("StartupProfilerStatusBar(", contentStart)
        assertTrue(footerStart > contentStart, "The footer must render below the workspace content")

        val footer = source.substring(source.indexOf("private fun StartupProfilerStatusBar("))
        assertTrue(footer.contains(".height(ViewerDimensions.footerHeight)"))
        assertTrue(footer.contains(".background(colors.toolbar)"))
        assertTrue(footer.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(footer.contains("errorMessage ?: operationMessage"))
    }

    @Test
    fun `toolbar controls remain reachable in narrow windows`() {
        val toolbar = toolbarSource()
        val scrollingControlRow =
            Regex(
                """Row\(\s*modifier\s*=\s*Modifier\s*\.fillMaxWidth\(\)\s*""" +
                    """\.horizontalScroll\(rememberScrollState\(\)\),\s*""" +
                    """verticalAlignment\s*=\s*Alignment\.CenterVertically,""",
            )

        assertTrue(scrollingControlRow.containsMatchIn(toolbar))
    }

    @Test
    fun `compact toolbar boundary and running progress precede workspace content`() {
        val toolbar = source.indexOf("HeaderToolbar(")
        val progressCondition = source.indexOf("if (state.isRunning && state.totalRuns > 0)")
        val progressBlockRange = source.blockRangeStartingAt(progressCondition)
        val progressBlock = source.substring(progressBlockRange)
        val screen = source.indexOf("StartupProfilerScreen(")
        val dividers = outlineDivider.findAll(source).map { it.range.first }.toList()

        assertEquals(1, dividers.size)
        assertTrue(toolbar < dividers[0])
        assertTrue(dividers[0] < progressCondition)
        assertTrue(progressBlock.contains("LinearProgressIndicator("))
        assertTrue(progressBlock.contains("state.completedRuns.toFloat() / state.totalRuns.toFloat()"))
        assertTrue(progressBlockRange.last < screen)
    }

    private fun toolbarSource(): String {
        val toolbarStart = source.indexOf("HeaderToolbar(")
        return source.substring(toolbarStart, source.indexOf("HorizontalDivider(", toolbarStart))
    }

    private fun String.blockRangeStartingAt(startIndex: Int): IntRange {
        val openingBrace = indexOf('{', startIndex)
        var depth = 0
        for (index in openingBrace..lastIndex) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return startIndex..index
                }
            }
        }
        error("Unclosed block at $startIndex")
    }

    private companion object {
        val outlineDivider =
            Regex(
                """HorizontalDivider\(\s*thickness = 1\.dp,\s*color = MaterialTheme\.colorScheme\.outline,\s*\)""",
            )
    }
}
