package com.androidperformancestudio.presentation

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StackChartVisualParityTest {
    @Test
    fun `report renders in both themes languages and display modes without clipping its plot`() {
        listOf(900, 1280).forEach { width ->
            listOf(false, true).forEach { dark ->
                verifyLanguagesAndModes(width, dark)
            }
        }
    }

    private fun verifyLanguagesAndModes(
        width: Int,
        dark: Boolean,
    ) {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { sameWidths -> verifyReport(width, dark, language, sameWidths) }
        }
    }

    private fun verifyReport(
        width: Int,
        dark: Boolean,
        language: UiLanguage,
        sameWidths: Boolean,
    ) = runDesktopComposeUiTest(width = width, height = 760) {
        val base = linkedStackChartState()
        val state = base.copy(workspace = base.workspace.copy(stackChartSameWidths = sameWidths))
        setContent {
            ViewerTheme(darkTheme = dark) {
                SimpleperfLocalization(language) { ReportPage(state, goldenActions()) }
            }
        }
        val canvas = onNodeWithTag("stack-chart-canvas")
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val root = onNodeWithTag("stack-block-root").fetchSemanticsNode().boundsInRoot
        val child = onNodeWithTag("stack-block-render").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > 500f && bounds.height > 180f)
        assertTrue(root.left >= bounds.left && root.right <= bounds.right)
        assertTrue(child.top >= bounds.top && child.bottom <= bounds.bottom)
        val image = canvas.captureToImage()
        val pixels = image.toPixelMap()
        assertTrue(pixels[155, 8] != pixels[155, 60], "The graph must paint actual sample blocks")
        val path = Path.of("build/reports/cpu-parity/stack-$width-$dark-${language.name}-$sameWidths.png")
        Files.createDirectories(path.parent)
        ImageIO.write(image.toBufferedImage(), "png", path.toFile())
        if (language == UiLanguage.ENGLISH && !sameWidths) {
            ImageIO.write(
                onRoot().captureToImage().toBufferedImage(),
                "png",
                path.resolveSibling("workspace-$width-$dark.png").toFile(),
            )
        }
    }
}
