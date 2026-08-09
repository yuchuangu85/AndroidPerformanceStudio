package com.androidperformancestudio.frame.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome without changing frame screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("FrameProfilerFileMenuBar("))
        assertTrue(source.contains("DropdownSelector"))
        assertFalse(source.contains("ProfilerCompactSelector"))
        assertTrue(source.contains("ProfilerToolbarStatus"))
        assertFalse(source.contains("private fun TargetSelector("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
        assertTrue(source.contains("FrameProfilerScreen("))
    }

    @Test
    fun `toolbar preserves capture and navigation wiring`() {
        val homeBlock =
            source.substring(
                source.indexOf("HomeButton("),
                source.indexOf("DropdownSelector("),
            )
        assertTrue(homeBlock.contains("if (state.isCapturing)"))
        assertTrue(homeBlock.contains("controller.stopOnlineCapture()"))
        assertTrue(homeBlock.contains("onBack()"))

        assertBlockContains(
            "localizedStringResource(Res.string.refresh, language)",
            "enabled = !state.isCapturing && !state.isLoading && !state.isRefreshingDevices",
            "controller.refreshDevices()",
        )
        assertBlockContains(
            "localizedStringResource(Res.string.capture_frametimeline, language)",
            "enabled = state.selectedProcessId != null && !state.isCapturing && !state.isLoading",
            "controller.captureFrameTimeline()",
        )
        assertBlockContains(
            "localizedStringResource(Res.string.start_capture, language)",
            "enabled = state.selectedProcessId != null",
            "if (state.isCapturing) controller.stopOnlineCapture() else controller.startOnlineCapture()",
        )
        assertTrue(
            source.contains(
                "selected?.let { file -> scope.launch { controller.importFrameStats(file.toPath()) } }",
            ),
        )
        assertTrue(source.contains("if (controller.state.value.isCapturing) controller.stopOnlineCapture()"))
        assertTrue(source.contains("onOpenLayoutInspector("))
    }

    @Test
    fun `file actions are wired through the menu and removed from the toolbar`() {
        val toolbar =
            source.substring(
                source.indexOf("ProfilerMacOsToolbar {"),
                source.indexOf("HorizontalDivider(", source.indexOf("ProfilerMacOsToolbar {")),
            )

        assertFalse(toolbar.contains("Res.string.import_framestats"))
        assertFalse(toolbar.contains("Res.string.export_csv"))
        assertFalse(toolbar.contains("Res.string.export_json"))
        assertTrue(source.contains("onImportFrameStats = { showImportDialog = true }"))
        assertTrue(source.contains("controller.exportCsv(output.toPath())"))
        assertTrue(source.contains("controller.exportJson(output.toPath())"))
    }

    @Test
    fun `selectors preserve controller callbacks and capture enabled predicates`() {
        assertBlockContains(
            "localizedStringResource(Res.string.device, language)",
            "enabled = !state.isCapturing",
            "controller.selectDevice(device.serial)",
        )
        assertBlockContains(
            "localizedStringResource(Res.string.process, language)",
            "enabled = !state.isCapturing && !state.isLoading && state.selectedDeviceSerial != null",
            "controller.selectProcess(it.pid)",
        )
    }

    private fun assertBlockContains(
        anchor: String,
        vararg invariants: String,
    ) {
        val anchorIndex = source.indexOf(anchor)
        assertTrue(anchorIndex >= 0, "Missing anchor: $anchor")
        val blockStart =
            maxOf(
                source.lastIndexOf("ProfilerCompactButton(", anchorIndex),
                source.lastIndexOf("DropdownSelector(", anchorIndex),
            )
        val nextButton = source.indexOf("ProfilerCompactButton(", anchorIndex + anchor.length)
        val nextSelector = source.indexOf("DropdownSelector(", anchorIndex + anchor.length)
        val blockEnd = listOf(nextButton, nextSelector).filter { it >= 0 }.minOrNull() ?: source.length
        val block = source.substring(blockStart, blockEnd)

        invariants.forEach { invariant ->
            assertTrue(block.contains(invariant), "Missing `$invariant` near `$anchor`")
        }
    }
}
