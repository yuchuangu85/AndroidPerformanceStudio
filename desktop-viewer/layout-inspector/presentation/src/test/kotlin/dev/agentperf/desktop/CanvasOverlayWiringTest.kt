package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanvasOverlayWiringTest {

    @Test
    fun `preview pane clips zoomed preview and supports dragging inside viewport`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/LayoutInspectorMainPage.kt"),
        )
        val preview = source
            .substringAfter("private fun PreviewPane(")
            .substringBefore("@Composable\nprivate fun PreviewZoomControls(")

        assertTrue(preview.contains("val previewPan = remember { mutableStateOf(Offset.Zero) }"))
        assertFalse(
            preview.contains("val clampedPreviewPan ="),
            "Pan must not be read during composition because every drag frame would recompose the canvas",
        )
        assertTrue(preview.contains(".fillMaxSize()"))
        assertTrue(preview.contains(".clipToBounds()"))
        assertTrue(preview.contains("PreviewPanState.clamp("))
        assertTrue(preview.contains(".offset {"))
        assertTrue(preview.contains("val pan = previewPan.value"))
        assertTrue(preview.contains("detectDragGestures"))
        assertTrue(preview.contains("previewPan.value + dragAmount"))
        assertTrue(preview.contains("change.consume()"))
        assertTrue(preview.contains("scrollDelta"))
        assertTrue(
            preview.contains(
                "val panDelta = fallbackWheelDelta.takeUnless { it == Offset.Zero } ?: scrollDelta",
            ),
            "Desktop wheel rotation must use the pixel-scaled delta instead of moving one pixel per tick",
        )
        assertTrue(preview.contains("PreviewPanState.scroll("))
    }
    @Test
    fun `preview pane keeps zoom controls anchored to the lower right`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/LayoutInspectorMainPage.kt"),
        )
        val preview = source
            .substringAfter("private fun PreviewPane(")
            .substringBefore("@Composable\nprivate fun PreviewZoomControls(")
        val zoomControls = extractDelimited(
            source = preview,
            startIndex = preview.indexOf("PreviewZoomControls("),
            opening = '(',
            closing = ')',
        )

        assertTrue(preview.contains("var previewZoom by remember { mutableStateOf(PreviewZoomState.DEFAULT_SCALE) }"))
        assertTrue(zoomControls.text.contains("PreviewZoomControls("))
        assertTrue(zoomControls.text.contains("modifier = Modifier.align(Alignment.BottomEnd)"))
        assertTrue(zoomControls.text.contains("PreviewZoomState.zoomOut(previewZoom)"))
        assertTrue(zoomControls.text.contains("PreviewZoomState.zoomIn(previewZoom)"))
    }

    @Test
    fun `general bounds are optional and hover focus is drawn last`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/LayoutInspectorMainPage.kt"),
        )
        val preview = source
            .substringAfter("private fun PreviewPane(")
            .substringBefore("internal fun canvasCornerRadiusDp")
        val previewCall = extractPreviewPaneCall(source)
        val generalBounds = extractBlock(preview, "if (showVisibleViewBounds)")
        val hoveredBounds = extractBlock(preview, "hoveredBounds?.let")
        val selectedBounds = extractBlock(preview, "selectedBounds?.let")

        val visibleBoundsArguments = previewCall.text
            .lines()
            .map(String::trim)
            .filter { it.startsWith("showVisibleViewBounds =") }
        assertEquals(
            listOf("showVisibleViewBounds = viewDisplayOptions.showVisibleViewBounds,"),
            visibleBoundsArguments,
        )

        assertTrue(generalBounds.text.startsWith("if (showVisibleViewBounds) {"))
        assertTrue(generalBounds.text.contains("state.activeRoot?.let"))
        assertTrue(generalBounds.text.contains("ViewBoundsOverlay.mappedVisibleBounds"))
        assertTrue(generalBounds.text.contains("borderColors.normal.toComposeColor().copy(alpha = 0.62f)"))
        assertTrue(generalBounds.text.contains("Stroke(width = 1.dp.toPx())"))
        assertFalse(generalBounds.text.contains("selectedBounds?.let"))
        assertFalse(generalBounds.text.contains("borderColors.selected"))
        assertFalse(generalBounds.text.contains("Stroke(width = 3.dp.toPx())"))

        assertTrue(selectedBounds.startIndex > generalBounds.endExclusive)
        assertTrue(selectedBounds.text.contains("color = borderColors.selected.toComposeColor()"))
        assertTrue(selectedBounds.text.contains("Stroke(width = 3.dp.toPx())"))
        assertFalse(selectedBounds.text.contains("borderColors.normal"))
        assertFalse(selectedBounds.text.contains("Stroke(width = 1.dp.toPx())"))

        assertTrue(hoveredBounds.startIndex > selectedBounds.endExclusive)
        assertTrue(hoveredBounds.text.contains("color = borderColors.hovered.toComposeColor()"))
        assertTrue(hoveredBounds.text.contains("Stroke(width = 2.dp.toPx())"))
        assertFalse(hoveredBounds.text.contains("state.hoveredNodeId != state.selectedNodeId"))
    }

    private fun extractPreviewPaneCall(source: String): SourceSlice {
        val beforeDefinition = source.substringBefore("private fun PreviewPane(")
        val callStart = beforeDefinition.lastIndexOf("PreviewPane(")
        check(callStart >= 0) { "PreviewPane call not found" }
        return extractDelimited(
            source = beforeDefinition,
            startIndex = callStart,
            opening = '(',
            closing = ')',
        )
    }

    private fun extractBlock(source: String, marker: String): SourceSlice {
        val startIndex = source.indexOf(marker)
        check(startIndex >= 0) { "$marker block not found" }
        return extractDelimited(
            source = source,
            startIndex = startIndex,
            opening = '{',
            closing = '}',
        )
    }

    private fun extractDelimited(
        source: String,
        startIndex: Int,
        opening: Char,
        closing: Char,
    ): SourceSlice {
        val openingIndex = source.indexOf(opening, startIndex)
        check(openingIndex >= 0) { "$opening delimiter not found" }

        var depth = 0
        for (index in openingIndex until source.length) {
            when (source[index]) {
                opening -> depth += 1
                closing -> {
                    depth -= 1
                    if (depth == 0) {
                        return SourceSlice(
                            startIndex = startIndex,
                            endExclusive = index + 1,
                            text = source.substring(startIndex, index + 1),
                        )
                    }
                }
            }
        }
        error("Unbalanced $opening$closing delimiters")
    }
}

private data class SourceSlice(
    val startIndex: Int,
    val endExclusive: Int,
    val text: String,
)
