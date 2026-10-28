package com.androidperformancestudio.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameGraphPalette
import com.androidperformancestudio.visualization.FlameTheme
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StackChartFirefoxParityTest {
    @Test
    fun `standalone stack chart starts at the left edge and keeps Firefox row gaps and labels`() {
        listOf(180, 365).forEach { width ->
            listOf(false, true).forEach { dark ->
                listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                    checkLayout(width, dark, language)
                }
            }
        }
    }

    private fun checkLayout(
        width: Int,
        dark: Boolean,
        language: UiLanguage,
    ) = runDesktopComposeUiTest(width = width, height = 64) {
        val snapshot = snapshot()
        setContent {
            ViewerTheme(darkTheme = dark) {
                SimpleperfLocalization(language) {
                    StackChartCanvas(
                        snapshot = snapshot,
                        viewport = StackChartViewport(0, 100),
                        selectedBlockId = null,
                        onSelect = {},
                        onCommitRange = { _, _ -> },
                    )
                }
            }
        }

        val canvas = onNodeWithTag("stack-chart-canvas")
        val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot
        val blockBounds = onNodeWithTag("stack-block-block").fetchSemanticsNode().boundsInRoot
        assertEquals(canvasBounds.left, blockBounds.left, "The stack chart has no track-label column")
        assertEquals(canvasBounds.width - 15f, blockBounds.width, absoluteTolerance = 1f)

        val image = canvas.captureToImage()
        val path = Path.of("build/reports/stack-chart-layout/stack-$width-$dark-${language.name}.png")
        Files.createDirectories(path.parent)
        ImageIO.write(image.toBufferedImage(), "png", path.toFile())
        val pixels = image.toPixelMap()
        val style = FirefoxFlameGraphStyle.resolve(if (dark) FlameTheme.DARK else FlameTheme.LIGHT)
        val colors = style.nodeColors(FlameGraphPalette.categoryRole(null, snapshot.framesById[1L]))
        val background = style.canvasBackground.toComposeColor()
        val fill = colors.fill.toComposeColor().compositeOver(background)
        val hasVisibleLabel =
            (3..100).any { x ->
                (2..13).any { y -> pixels[x, y].isNear(style.canvasForeground.toComposeColor()) }
            }
        assertTrue(pixels[2, 14].isNear(fill), "Left-edge fill: ${pixels[2, 14]}, expected: $fill")
        assertTrue(
            pixels[2, 15].isNear(background),
            "Row gaps must retain the canvas background",
        )
        assertTrue(hasVisibleLabel, "The frame label must remain visible at the left edge")
    }

    private fun snapshot(): StackChartSnapshot {
        val frame =
            CallStackFrame(
                frameId = 1,
                functionId = FlameFunctionId(1),
                symbolName = "renderFrame",
                resource = "libui.so",
                virtualAddress = 1,
                implementation = FrameImplementation.NATIVE,
            )
        return StackChartSnapshot(
            framesById = mapOf(frame.frameId to frame),
            blocks = listOf(StackChartBlock(StackChartBlockId("block"), 1, 0, 100, 0, frame.frameId, "main", 1)),
            startNanos = 0,
            endNanosExclusive = 100,
            maxDepth = 0,
            emptyReason = null,
        )
    }
}

private fun Color.isNear(other: Color): Boolean =
    kotlin.math.abs(red - other.red) < 0.05f &&
        kotlin.math.abs(green - other.green) < 0.05f &&
        kotlin.math.abs(blue - other.blue) < 0.05f
