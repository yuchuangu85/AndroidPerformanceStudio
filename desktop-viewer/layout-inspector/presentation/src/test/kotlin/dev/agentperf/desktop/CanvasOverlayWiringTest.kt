package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanvasOverlayWiringTest {
    @Test
    fun `general bounds are optional and hover focus is drawn last`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
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
