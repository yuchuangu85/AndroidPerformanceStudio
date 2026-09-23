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
}
