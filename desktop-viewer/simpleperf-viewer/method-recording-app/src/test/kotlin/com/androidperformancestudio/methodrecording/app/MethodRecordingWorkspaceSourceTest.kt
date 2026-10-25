package com.androidperformancestudio.methodrecording.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MethodRecordingWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingMainPage.kt"),
        )

    @Test
    fun `workspace uses shared header toolbar and preserves navigation`() {
        assertTrue(source.contains("HeaderToolbar("))
        assertTrue(source.contains("onNavigateHome = onBack"))
        assertTrue(source.contains("DropdownSelector("))
        assertTrue(source.contains("ProfilerCompactButton("))
        assertFalse(source.contains("ProfilerMacOsToolbar"))
        assertFalse(source.contains("HomeButton("))
        assertTrue(source.contains("TitleBarRefreshOrInline("))
        assertTrue(source.contains("onRefresh = { scope.launch { controller.refreshDevices() } }"))
    }

    @Test
    fun `trace workspace exposes device connection state in the bottom status bar`() {
        val contentStart = source.indexOf("MethodRecordingScreen(")
        val footerStart = source.indexOf("MethodRecordingStatusBar(", contentStart)
        assertTrue(footerStart > contentStart)
        assertTrue(source.contains(".height(ViewerDimensions.footerHeight)"))
        assertTrue(source.contains("Res.string.device_status"))
        assertTrue(source.contains("Res.string.device_connected"))
        assertTrue(source.contains("Res.string.device_offline"))
        assertTrue(source.contains("Res.string.devices_connected"))
    }

    @Test
    fun `trace content omits the decorative workspace banner before all content states`() {
        val screen = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingScreen.kt"))
        assertFalse(screen.contains("StudioWorkspaceHeader("))
        assertTrue(screen.contains("StudioTokens.workspacePadding"))
        assertFalse(source.contains("StudioWorkspaceHeader("))
    }

    @Test
    fun `trace table uses shared panel without changing fields or flame graph`() {
        val screen = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingScreen.kt"))
        val table = screen.substringAfter("private fun MethodTopMethodsTable(").substringBefore("private fun TableHeader(")
        assertTrue(table.contains("StudioPanel("))
        assertTrue(table.contains("LazyColumn(Modifier.heightIn(max = 220.dp))"))
        listOf("symbolName", "selfMicros", "totalMicros", "callCount").forEach { field ->
            assertTrue(table.contains("method.$field"), field)
        }
        assertTrue(screen.contains("FlameGraphPanel("))
        assertTrue(screen.contains("snapshot = snapshot"))
        assertTrue(screen.contains("actions = noopReportActions()"))
    }
}
