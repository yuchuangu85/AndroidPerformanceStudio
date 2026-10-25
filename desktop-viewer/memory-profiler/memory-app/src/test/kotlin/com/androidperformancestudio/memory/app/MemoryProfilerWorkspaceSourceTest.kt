package com.androidperformancestudio.memory.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryProfilerWorkspaceSourceTest {
    private val workspace =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerMainPage.kt"),
        )
    private val backend =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/memory/app/DesktopMemoryProfilerBackend.kt"),
        )
    private val desktopFileDialogs =
        Files.readString(
            Path.of("../../ui-components/src/main/kotlin/com/androidperformancestudio/ui/DesktopFileDialogs.kt"),
        )

    @Test
    fun `leak view is accessible offline and monitoring requires its own explicit action`() {
        val leakButton =
            workspace
                .substringAfter("text = localizedStringResource(Res.string.memory_leaks_view, language)")
                .substringBefore("text = localizedStringResource(Res.string.inject_leak_canary_agent, language)")
        assertTrue(leakButton.contains("state.snapshotSummary != null ||"))
        assertTrue(leakButton.contains("onClick = { controller.changeViewMode(MemoryProfilerViewMode.LeakCanary) }"))
        assertFalse(leakButton.contains("monitorLiveLeakCanary"))
        assertTrue(workspace.contains("onAnalyzeLeaks = { scope.launch { controller.analyzeLeaks() } }"))
        assertTrue(workspace.contains("onStartLiveLeakMonitoring = {"))
        assertTrue(workspace.contains("if (sharedTargetsReady) scope.launch { controller.monitorLiveLeakCanary() }"))
        assertTrue(workspace.contains("onStopLiveLeakMonitoring = controller::stopLiveLeakCanary"))
    }

    @Test
    fun `import uses a composition owned native dialog and backend owns io dispatch`() {
        val requestDialog = workspace.indexOf("showHprofFileDialog = true")
        val nativeDialog = workspace.indexOf("DesktopOpenFileDialog(")
        val launchParser = workspace.indexOf("controller.importHprof(selectedFile.toPath())")

        assertTrue(requestDialog >= 0)
        assertTrue(nativeDialog > requestDialog)
        assertTrue(launchParser > requestDialog)
        assertFalse(workspace.contains("Dispatchers.IO"))
        assertFalse(workspace.contains("chooseHprofFile"))
        assertTrue(desktopFileDialogs.contains("AwtWindow("))
        assertTrue(desktopFileDialogs.contains("onCloseRequest(files.firstOrNull())"))
        assertTrue(backend.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun `large heap selection callbacks run through asynchronous controller queries`() {
        assertTrue(workspace.contains("controller.selectClassAsync(className)"))
        assertTrue(workspace.contains("controller.selectClassifierAsync(row)"))
        assertTrue(workspace.contains("controller.selectInstanceAsync(objectId)"))
        assertTrue(workspace.contains("controller.loadArrayRangeAsync(objectId, start)"))
        assertTrue(workspace.contains("controller.navigateInstanceBackAsync()"))
        assertTrue(workspace.contains("controller.navigateInstanceForwardAsync()"))
        assertFalse(workspace.contains("onSelectClass = controller::selectClass"))
        assertFalse(workspace.contains("onSelectInstance = controller::selectInstance"))
    }

    @Test
    fun `sampled ART control is separately named and never labels the raw trace Full`() {
        assertTrue(workspace.contains("Res.string.record_sampled_art"))
        assertTrue(workspace.contains("controller.captureSampledArtAllocations()"))
        assertTrue(workspace.contains("MemoryProfilerViewMode.SampledArtAllocations"))
        assertTrue(backend.contains("SampledArtAllocationCaptureSession(adb).capture("))
        val page =
            Files.readString(
                Path.of(
                    "../presentation/src/main/kotlin/" +
                        "com/androidperformancestudio/memory/presentation/MemorySampledArtAllocationPage.kt",
                ),
            )
        assertTrue(page.contains("art_sampled_evidence_boundary"))
        assertFalse(page.contains("Full tracking available"))
        assertTrue(workspace.contains("controller.importSampledArtAllocations(selectedFile.toPath())"))
        assertTrue(workspace.contains("controller::exportSampledArtAllocations"))
    }

    @Test
    fun `workspace toolbar uses shared compact chrome`() {
        assertTrue(workspace.contains("HeaderToolbar("))
        assertTrue(workspace.contains("TitleBarRefreshOrInline("))
        assertTrue(workspace.contains("onRefresh = { scope.launch { controller.refreshDevices() } }"))
        assertFalse(workspace.contains("ProfilerMacOsToolbar"))
        assertTrue(workspace.contains("ProfilerCompactButton"))
        assertFalse(workspace.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(workspace.contains("import androidx.compose.material3.Button"))
        assertTrue(workspace.contains("MemoryProfilerScreen("))
    }

    @Test
    fun `device and process selectors share the top toolbar row with home`() {
        val toolbar =
            workspace
                .substringAfter("HeaderToolbar(")
                .substringBefore("        }\n        HorizontalDivider")

        val home = toolbar.indexOf("onNavigateHome = onBack")
        val selectors = toolbar.indexOf("MemoryProfilerToolbarSelectors(")
        val refresh = toolbar.indexOf("ProfilerCompactButton(")
        val navigationScroll = toolbar.indexOf(".horizontalScroll(rememberScrollState())")
        val dumpHeap = toolbar.indexOf("MemoryProfilerDumpHeapButton(")

        assertTrue(home >= 0)
        assertTrue(selectors > home)
        assertTrue(refresh > selectors)
        assertTrue(navigationScroll > refresh)
        assertTrue(dumpHeap > navigationScroll)
        assertTrue(toolbar.contains("onSelectDevice = { serial ->"))
        assertTrue(toolbar.contains("onSelectProcess = controller::selectProcess"))
        assertEquals(1, Regex("SegmentedSelector\\(").findAll(toolbar).count())
        val primaryViewSelector = toolbar.substringAfter("SegmentedSelector(").substringBefore("ProfilerCompactButton(")
        val primaryViewModes =
            workspace
                .substringAfter("private val MEMORY_PROFILER_PRIMARY_VIEW_MODES =")
                .substringBefore("private fun MemoryProfilerViewMode.primaryViewLabel")
        assertTrue(primaryViewModes.contains("MemoryProfilerViewMode.Dashboard"))
        assertTrue(primaryViewModes.contains("MemoryProfilerViewMode.ClassList"))
        assertTrue(primaryViewModes.contains("MemoryProfilerViewMode.Dominators"))
        assertTrue(primaryViewSelector.contains("items = MEMORY_PROFILER_PRIMARY_VIEW_MODES"))
        assertTrue(primaryViewSelector.contains("selectedItem = state.viewMode.takeIf"))
        assertTrue(primaryViewSelector.contains("style = SegmentedSelectorStyle.COMPACT_OUTLINED"))
        assertTrue(toolbar.contains("onDumpHeap = {"))
        assertTrue(toolbar.contains("controller.changeViewMode(MemoryProfilerViewMode.ClassList)"))
        assertTrue(toolbar.contains("onDumpBitmaps = {"))
        assertTrue(toolbar.contains("controller.changeViewMode(MemoryProfilerViewMode.BitmapDump)"))
        assertTrue(toolbar.contains("onCaptureNativeHeap = {"))
        assertTrue(toolbar.contains("controller.changeViewMode(MemoryProfilerViewMode.NativeHeap)"))
        assertTrue(toolbar.contains("Res.string.native_heap_view"))
    }

    @Test
    fun `all toolbar actions after refresh belong to the horizontal scroll region`() {
        val toolbar =
            workspace
                .substringAfter("HeaderToolbar(")
                .substringBefore("        }\n        HorizontalDivider")
        val refresh = toolbar.indexOf("Res.string.refresh_devices")
        val scroll = toolbar.indexOf(".horizontalScroll(rememberScrollState())")
        val scrollingActions =
            toolbar
                .substringAfter(".horizontalScroll(rememberScrollState()),")
                .substringBefore("\n            }\n")

        assertTrue(refresh >= 0 && scroll > refresh)
        assertEquals(1, Regex("horizontalScroll\\(").findAll(toolbar).count())
        listOf(
            "SegmentedSelector(",
            "Res.string.bitmap_dump_view",
            "Res.string.native_heap_view",
            "Res.string.sampled_art_view",
            "Res.string.memory_leaks_view",
            "Res.string.inject_leak_canary_agent",
            "MemoryProfilerDumpHeapButton(",
            "MemoryProfilerCaptureNativeHeapButton(",
            "Res.string.record_sampled_art",
            "MemoryProfilerDumpBitmapsButton(",
            "MemoryWorkspaceActions(",
        ).forEach { action -> assertTrue(scrollingActions.contains(action), "$action must scroll with the toolbar") }
    }

    @Test
    fun `mapping and compare are the final actions in the top toolbar`() {
        val toolbar =
            workspace
                .substringAfter("HeaderToolbar(")
                .substringBefore("        }\n        HorizontalDivider")
        val actions = toolbar.indexOf("MemoryWorkspaceActions(")
        assertTrue(actions > toolbar.indexOf("MemoryProfilerDumpBitmapsButton("))
        assertTrue(toolbar.substring(actions).contains("onMapping = { showMappingFileDialog = true }"))
        val compareAction = "onCompare = { controller.changeViewMode(MemoryProfilerViewMode.Diff) }"
        assertTrue(toolbar.substring(actions).contains(compareAction))
        assertEquals(1, Regex("MemoryWorkspaceActions\\(").findAll(workspace).count())
    }

    @Test
    fun `workspace moves import and export actions into the file menu`() {
        assertTrue(workspace.contains("MemoryProfilerFileMenuBar("))
        assertTrue(workspace.contains("onImportHprof = { showHprofFileDialog = true }"))
        assertTrue(workspace.contains("onExportRawHprof ="))
        assertTrue(workspace.contains("onExportStandardHprof ="))
        assertTrue(workspace.contains("onExportCsv ="))
        assertFalse(workspace.contains("text = localizedStringResource(Res.string.export_raw_hprof"))
        assertFalse(workspace.contains("text = localizedStringResource(Res.string.export_standard_hprof"))
        assertFalse(workspace.contains("text = localizedStringResource(Res.string.export_csv"))
        assertTrue(workspace.contains("MemoryWorkspaceActions("))
        assertTrue(workspace.contains("onCompareSnapshots = controller::compareSnapshots"))
        assertFalse(workspace.contains("onAddSnapshot ="))
        val workspaceActions = workspace.substringAfter("MemoryWorkspaceActions(").substringBefore("HorizontalDivider(")
        assertFalse(workspaceActions.contains("onImport ="))
        assertFalse(workspaceActions.contains("onExport ="))
    }
}
