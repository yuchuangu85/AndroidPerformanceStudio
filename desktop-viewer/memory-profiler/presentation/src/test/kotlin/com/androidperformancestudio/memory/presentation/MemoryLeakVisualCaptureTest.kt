package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryLeakVisualCaptureTest {
    @Test
    fun `offline leak workspace renders desktop short and narrow layouts in both themes`() {
        listOf(
            Triple(1280, 720, UiLanguage.ENGLISH),
            Triple(900, 560, UiLanguage.ENGLISH),
            Triple(900, 560, UiLanguage.SIMPLIFIED_CHINESE),
            Triple(620, 480, UiLanguage.SIMPLIFIED_CHINESE),
        ).forEach { (width, height, language) ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = width, height = height) {
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            MemoryProfilerScreen(memoryLeakFixture(), MemoryProfilerActions(), language)
                        }
                    }
                    onNodeWithTag("memory-leak-analyze").assertIsDisplayed()
                    onNodeWithTag("memory-leak-live-start").assertIsDisplayed()
                    onNodeWithTag("memory-leak-live-stop").assertIsDisplayed()
                    onNodeWithTag("memory-leak-analysis-note").assertIsDisplayed()
                    val summary = onNodeWithTag("memory-leak-report-summary").fetchSemanticsNode().boundsInRoot
                    val live = onNodeWithTag("memory-leak-live-controls").fetchSemanticsNode().boundsInRoot
                    val workspace = onNodeWithTag("memory-leak-workspace").fetchSemanticsNode().boundsInRoot
                    val footer = onNodeWithTag("memory-profiler-status-bar").fetchSemanticsNode().boundsInRoot
                    assertTrue(summary.bottom <= live.top)
                    assertTrue(live.bottom <= workspace.top)
                    assertTrue(workspace.bottom <= footer.top)
                    assertTrue(workspace.height > 100f)
                    assertEquals(height.toFloat(), footer.bottom)
                    val locale = if (language == UiLanguage.ENGLISH) "en" else "zh"
                    val theme = if (dark) "dark" else "light"
                    val name = "aps-memory-leaks-${width}x$height-$locale-$theme"
                    val image = onRoot().captureToImage()
                    assertEquals(width, image.width)
                    assertEquals(height, image.height)
                    val pixels = image.toPixelMap()
                    assertTrue(
                        (20 until height step 20)
                            .flatMap { y -> (20 until width step 20).map { x -> pixels[x, y] } }
                            .distinct()
                            .size > 3,
                        "The rendered workspace must contain visible content",
                    )
                    saveCapture(image, name)
                    if (width < 1120) {
                        onNodeWithTag("memory-object-inspector").performScrollTo().assertIsDisplayed()
                        saveCapture(onRoot().captureToImage(), "$name-inspector")
                    }
                }
            }
        }
    }

    private fun saveCapture(
        image: ImageBitmap,
        name: String,
    ) {
        val directory = System.getenv("APS_MEMORY_LEAKS_SCREENSHOT_DIR") ?: "/tmp"
        val output = Path.of(directory, "$name.png")
        Files.createDirectories(output.parent)
        val pixels = image.toPixelMap()
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val c = pixels[x, y]
                bitmap.setRGB(
                    x,
                    y,
                    ((c.alpha * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or
                        ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt(),
                )
            }
        }
        ImageIO.write(bitmap, "png", output.toFile())
    }
}
