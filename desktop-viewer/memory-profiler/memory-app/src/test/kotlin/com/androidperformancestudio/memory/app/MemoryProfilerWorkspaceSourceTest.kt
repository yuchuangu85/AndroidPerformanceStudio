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
                .substringBefore("MemoryWorkspaceActions(")
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
        assertTrue(workspace.contains("MemoryProfilerToolbar("))
        assertTrue(workspace.contains("TitleBarRefreshOrInline("))
        assertTrue(workspace.contains("onRefresh = { scope.launch { controller.refreshDevices() } }"))
        assertFalse(workspace.contains("ProfilerMacOsToolbar"))
        assertTrue(workspace.contains("ProfilerCompactButton"))
        assertFalse(workspace.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(workspace.contains("import androidx.compose.material3.Button"))
        assertTrue(workspace.contains("MemoryProfilerScreen("))
    }

    @Test
    fun `capture group preserves target selection refresh and capture callbacks`() {
        val capture = workspace.substringAfter("captureContent = {").substringBefore("analysisContent = {")
        assertTrue(workspace.contains("onNavigateHome = onBack"))
        val selectors = capture.indexOf("MemoryProfilerToolbarSelectors(")
        val refresh = capture.indexOf("TitleBarRefreshOrInline(")
        assertTrue(selectors >= 0)
        assertTrue(refresh > selectors)
        assertTrue(capture.contains("onSelectDevice = { serial ->"))
        assertTrue(capture.contains("onSelectProcess = controller::selectProcess"))
        assertTrue(capture.contains("onDumpHeap = {"))
        assertTrue(capture.contains("controller.changeViewMode(MemoryProfilerViewMode.ClassList)"))
        assertTrue(capture.contains("onDumpBitmaps = {"))
        assertTrue(capture.contains("controller.changeViewMode(MemoryProfilerViewMode.BitmapDump)"))
        assertTrue(capture.contains("onCaptureNativeHeap = {"))
        assertTrue(capture.contains("controller.changeViewMode(MemoryProfilerViewMode.NativeHeap)"))
        assertTrue(capture.contains("controller.captureSampledArtAllocations()"))
    }

    @Test
    fun `analysis group keeps the primary view selector and specialized views`() {
        val analysis = workspace.substringAfter("analysisContent = {").substringBefore("MemoryProfilerSnapshotTabs(")
        assertEquals(1, Regex("SegmentedSelector\\(").findAll(analysis).count())
        val primaryViewSelector =
            analysis.substringAfter("SegmentedSelector(").substringBefore("ProfilerCompactButton(")
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
        assertTrue(primaryViewModes.contains("MemoryProfilerViewMode.Diff"))
        assertTrue(analysis.contains("Res.string.native_heap_view"))
    }

    @Test
    fun `capture and analysis commands belong to separate toolbar groups`() {
        assertEquals(1, Regex("MemoryProfilerToolbar\\(").findAll(workspace).count())
        val capture = workspace.substringAfter("captureContent = {").substringBefore("analysisContent = {")
        val analysis = workspace.substringAfter("analysisContent = {").substringBefore("MemoryProfilerSnapshotTabs(")
        listOf(
            "SegmentedSelector(",
            "Res.string.bitmap_dump_view",
            "Res.string.native_heap_view",
            "Res.string.sampled_art_view",
            "Res.string.memory_leaks_view",
            "MemoryWorkspaceActions(",
        ).forEach { action ->
            assertTrue(analysis.contains(action), "$action belongs to Analysis")
            assertFalse(capture.contains(action), "$action must not appear in Capture")
        }
        listOf(
            "MemoryProfilerDumpHeapButton(",
            "MemoryProfilerCaptureNativeHeapButton(",
            "Res.string.record_sampled_art",
            "MemoryProfilerDumpBitmapsButton(",
            "Res.string.inject_leak_canary_agent",
        ).forEach { action ->
            assertTrue(capture.contains(action), "$action belongs to Capture")
            assertFalse(analysis.contains(action), "$action must not appear in Analysis")
        }
    }

    @Test
    fun `mapping is the final toolbar action without a duplicate Compare entry`() {
        val toolbar =
            workspace
                .substringAfter("analysisContent = {")
                .substringBefore("MemoryProfilerSnapshotTabs(")
        val actions = toolbar.indexOf("MemoryWorkspaceActions(")
        assertTrue(actions > toolbar.indexOf("Res.string.memory_leaks_view"))
        assertTrue(toolbar.substring(actions).contains("onMapping = { showMappingFileDialog = true }"))
        assertFalse(toolbar.substring(actions).contains("onCompare ="))
        assertEquals(1, Regex("MemoryWorkspaceActions\\(").findAll(workspace).count())
    }

    @Test
    fun `overview mode no longer has the overlapping leaks and bitmaps label`() {
        val resources = Path.of("src/main/composeResources")
        val english = Files.readString(resources.resolve("values/strings.xml"))
        val chinese = Files.readString(resources.resolve("values-zh/strings.xml"))
        assertTrue(english.contains("<string name=\"dashboard_view\">Overview</string>"))
        assertTrue(chinese.contains("<string name=\"dashboard_view\">概览</string>"))
        assertFalse(english.contains("Leaks &amp; Bitmaps"))
        assertFalse(chinese.contains("泄漏与位图"))
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
