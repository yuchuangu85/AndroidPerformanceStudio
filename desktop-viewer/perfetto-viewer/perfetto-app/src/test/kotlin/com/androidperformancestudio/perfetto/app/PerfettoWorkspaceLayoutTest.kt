package com.androidperformancestudio.perfetto.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoWorkspaceLayoutTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoMainPage.kt"),
        )

    @Test
    fun `shared device selection restores the matching serial and guards capture`() {
        assertTrue(source.contains("host.chooseDevice(device.serial, device.displayName)"))
        assertTrue(source.contains("selectedDeviceSerial = sharedSerial"))
        assertTrue(source.contains("titleBarTargets?.deviceReady(deviceSerial) != false"))
        assertTrue(source.contains("displayTextOverride = if (inTitleBar) sharedDeviceLabel else null"))
    }

    @Test
    fun `workspace keeps shared shell chrome around the commercial trace analyzer`() {
        assertTrue(source.contains("HeaderToolbar("))
        assertFalse(source.contains("private fun PerfettoToolbar("))
        assertFalse(source.contains("HomeButton("))
        assertTrue(source.contains("DropdownSelector("))
        assertTrue(source.contains("onNavigateHome = onNavigateHome"))
        assertTrue(source.contains("TraceAnalyzerHeaderActions("))
        assertTrue(source.contains("TraceAnalyzerWorkspace("))
        assertTrue(source.contains("state = workspaceState"))
        assertTrue(source.contains("actions = workspaceActions"))
    }

    @Test
    fun `trace open failures return before replacing active evidence or recent files`() {
        val openTrace = source.substringAfter("val openTrace:").substringBefore("val exportRawTrace:")
        val prepared = openTrace.indexOf("prepareTraceArtifact(traceFile, artifact)")
        val failure = openTrace.indexOf("is StudioResult.Failure ->")
        val exit = openTrace.indexOf("return@withLock", failure)
        val publish = openTrace.indexOf("activeTraceFile = traceFile")
        assertTrue(prepared >= 0 && failure > prepared && exit > failure && publish > exit)
        assertTrue(openTrace.substring(failure, exit).contains("diagnosticError = prepared.error.message"))
        assertTrue(openTrace.indexOf("activeArtifact = importedArtifact") > publish)
        assertTrue(openTrace.indexOf("recentFiles =") > publish)
        assertTrue(openTrace.indexOf("launchTraceInUi(") > publish)
    }

    @Test
    fun `workspace maps capture sessions artifacts and structured diagnostics`() {
        assertTrue(source.contains("TraceAnalyzerWorkspaceState("))
        assertTrue(source.contains("TraceAnalyzerWorkspaceActions("))
        assertTrue(source.contains("TraceWorkspaceSession("))
        assertTrue(source.contains("TraceWorkspaceArtifact("))
        assertTrue(source.contains("TraceInsightEvidence("))
        assertTrue(source.contains("DiagnosticOutcome("))
        assertTrue(source.contains("DiagnosticResult("))
        assertTrue(source.contains("diagnostic_result_rows"))
        assertTrue(source.contains("diagnostic_data_source_limitation"))
        assertTrue(source.contains("PerfettoCapturePage("))
        assertTrue(source.contains("captureContent = {"))
        assertTrue(source.contains("onRunDiagnostic = { queryId ->"))
        assertTrue(source.contains("onDeleteSession = { sessionId ->"))
        assertTrue(source.contains("sessionStore.delete(sessionId)"))
        assertTrue(source.contains("onOpenInTimeline = { insight ->"))
        assertTrue(source.contains("insight.timestampNanos"))
        assertTrue(source.contains("onExport = { activeTraceFile?.let(exportRawTrace) }"))
        assertFalse(source.contains("private fun RecentSessionsPanel("))
        assertFalse(source.contains("private fun TraceDiagnosticsWorkspacePanel("))
    }

    @Test
    fun `device connection status is rendered in the bottom footer`() {
        val toolbarStart = source.indexOf("HeaderToolbar(")
        val toolbarEnd = source.indexOf("HorizontalDivider(color = MaterialTheme.colorScheme.outline)", toolbarStart)
        val toolbar = source.substring(toolbarStart, toolbarEnd)
        assertFalse(toolbar.contains("PerfettoStatusDot("))
        val footerStart = source.indexOf("PerfettoStatusBar(")
        assertTrue(footerStart > toolbarEnd)
        val footer = source.substring(footerStart)
        assertTrue(footer.contains("ViewerDimensions.footerHeight"))
        assertTrue(footer.contains("Res.string.device_status"))
        assertTrue(footer.contains("Res.string.device_connected"))
        assertTrue(footer.contains("Res.string.no_online_device"))
    }

    @Test
    fun `device selector and refresh move to title bar while retaining inline fallback`() {
        val toolbar = source.substringAfter("private fun RowScope.PerfettoToolbarContent(")
            .substringBefore("private fun DeviceSelector(")
        assertTrue(toolbar.contains("TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE)"))
        assertTrue(toolbar.contains("DeviceSelector("))
        assertTrue(toolbar.contains("PerfettoCompactTextField("))
        assertTrue(toolbar.contains("TitleBarRefreshOrInline("))
        assertTrue(toolbar.contains("onRefresh = onRefreshDevices"))
        assertTrue(toolbar.contains("onClick = onRefreshDevices"))
    }

    @Test
    fun `workspace avoids large material page components`() {
        assertTrue(source.contains("private fun InitialTraceNotice("))
        assertFalse(source.contains("Card("))
        assertFalse(source.contains("OutlinedButton("))
    }

    @Test
    fun `device refresh exposes progress and discovery failures instead of silently clearing the list`() {
        assertFalse(source.contains("getOrDefault(emptyList())"))
        assertTrue(source.contains("deviceRefreshInProgress"))
        assertTrue(source.contains("deviceRefreshError"))
        assertTrue(source.contains("onRefreshDevices = { coroutineScope.launch { refreshDevices() }"))
    }

    @Test
    fun `diagnostic failures remain artifact scoped and preserve the active viewer`() {
        val start = source.indexOf("val executeDiagnostic: suspend (DiagnosticQuery) -> Boolean")
        val end = source.indexOf("PerfettoFileMenuBar(", start)
        val diagnosticBlock = source.substring(start, end)

        assertTrue(start > source.indexOf("val acquireAnalysisContext:"))
        assertTrue(diagnosticBlock.contains("recordFailure"))
        assertTrue(diagnosticBlock.contains("activeArtifact?.id == artifact.id"))
        assertTrue(diagnosticBlock.contains("DiagnosticOutcome(error = message)"))
        assertTrue(diagnosticBlock.contains("val runDiagnostic: (DiagnosticQuery) -> Unit"))
        // Presets run each query through the same isolated runner and count failures instead of aborting.
        assertTrue(diagnosticBlock.contains("queries.count { query -> executeDiagnostic(query) }"))
        assertFalse(diagnosticBlock.contains("activeTraceFile = null"))
        assertFalse(diagnosticBlock.contains("activeArtifact = null"))
    }

    @Test
    fun `repeated trace opens keep blocking work off the UI thread`() {
        assertEquals(1, Regex("artifactFactory\\.imported").findAll(source).count())
        assertTrue(source.contains("traceOpenJob?.cancel()"))
        assertTrue(source.contains("traceOpenMutex.withLock"))
        assertTrue(source.contains("withContext(Dispatchers.IO) { contextToClose?.close() }"))
    }
}
