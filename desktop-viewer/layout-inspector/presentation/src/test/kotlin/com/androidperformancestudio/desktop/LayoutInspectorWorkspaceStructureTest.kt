package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LayoutInspectorWorkspaceStructureTest {
    private val source =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))

    @Test
    fun `workspace exposes the complete inspector surface`() {
        listOf(
            "layout-inspector-toolbar",
            "layout-inspector-workspace",
            "layout-inspector-hierarchy-pane",
            "layout-inspector-canvas-pane",
            "layout-inspector-properties-pane",
            "layout-inspector-findings-pane",
        ).forEach { tag ->
            assertTrue(source.contains(tag), "Missing Layout Inspector surface tag: $tag")
        }
    }

    @Test
    fun `archive actions are available beside live capture controls`() {
        val toolbar = source.substringAfter("HeaderToolbar(").substringBefore("state.composeInspectionWarning?.let")

        assertTrue(toolbar.contains("Res.string.import_archive"))
        assertTrue(toolbar.contains("Res.string.export_archive"))
        assertTrue(toolbar.contains("onClick = importCaptureArchive"))
        assertTrue(toolbar.contains("onClick = exportCaptureArchive"))
    }

    @Test
    fun `canvas toolbar exposes a fit action that resets zoom and pan`() {
        val canvas = source.substringAfter("private fun PreviewPane(").substringBefore("private fun PreviewZoomControls(")

        assertTrue(canvas.contains("Res.string.fit_preview"))
        assertTrue(canvas.contains("previewZoom = PreviewZoomState.DEFAULT_SCALE"))
        assertTrue(canvas.contains("previewPan.value = Offset.Zero"))
    }
}
