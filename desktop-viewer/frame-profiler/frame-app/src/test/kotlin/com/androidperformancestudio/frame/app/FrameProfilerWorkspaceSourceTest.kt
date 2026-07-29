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
        assertTrue(source.contains("ProfilerCompactSelector"))
        assertTrue(source.contains("ProfilerToolbarStatus"))
        assertFalse(source.contains("private fun TargetSelector("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
        assertTrue(source.contains("FrameProfilerScreen("))
    }

    @Test
    fun `toolbar preserves capture import export and navigation wiring`() {
        val homeBlock =
            source.substring(
                source.indexOf("ProfilerHomeButton("),
                source.indexOf("ProfilerCompactSelector("),
            )
        assertTrue(homeBlock.contains("if (state.isCapturing)"))
        assertTrue(homeBlock.contains("controller.stopOnlineCapture()"))
        assertTrue(homeBlock.contains("onBack()"))

        assertBlockContains(
            "localizedStringResource(Res.string.refresh, chinese)",
            "enabled = !state.isCapturing && !state.isRefreshingDevices",
            "controller.refreshDevices()",
        )
        assertBlockContains(
            "localizedStringResource(Res.string.start_capture, chinese)",
            "enabled = state.selectedProcessId != null",
            "if (state.isCapturing) controller.stopOnlineCapture() else controller.startOnlineCapture()",
        )
        assertBlockContains(
            "localizedStringResource(Res.string.import_framestats, chinese)",
            "enabled = !state.isCapturing",
            "showImportDialog = true",
        )
        assertBlockContains(
            "localizedStringResource(Res.string.export_csv, chinese)",
            "enabled = state.analysis != null",
            "controller.exportCsv(output.toPath())",
        )
        assertBlockContains(
            "localizedStringResource(Res.string.export_json, chinese)",
            "enabled = state.analysis != null",
            "controller.exportJson(output.toPath())",
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
    fun `selectors preserve controller callbacks and capture enabled predicates`() {
        assertBlockContains(
            "localizedStringResource(Res.string.device, chinese)",
            "enabled = !state.isCapturing",
            "controller.selectDevice(serial)",
        )
        assertBlockContains(
            "localizedStringResource(Res.string.process, chinese)",
            "enabled = !state.isCapturing && state.selectedDeviceSerial != null",
            "controller::selectProcess",
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
                source.lastIndexOf("ProfilerCompactSelector(", anchorIndex),
            )
        val nextButton = source.indexOf("ProfilerCompactButton(", anchorIndex + anchor.length)
        val nextSelector = source.indexOf("ProfilerCompactSelector(", anchorIndex + anchor.length)
        val blockEnd = listOf(nextButton, nextSelector).filter { it >= 0 }.minOrNull() ?: source.length
        val block = source.substring(blockStart, blockEnd)

        invariants.forEach { invariant ->
            assertTrue(block.contains(invariant), "Missing `$invariant` near `$anchor`")
        }
    }
}
