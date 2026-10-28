package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
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
            Triple(1128, 720, UiLanguage.ENGLISH),
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
                    onNodeWithText(if (language == UiLanguage.ENGLISH) "Leak Analysis" else "泄漏分析")
                        .assertIsDisplayed()
                    assertBottomSpacing(width < 1128)
                    val summary = onNodeWithTag("memory-leak-report-summary").fetchSemanticsNode().boundsInRoot
                    val live = onNodeWithTag("memory-leak-live-controls").fetchSemanticsNode().boundsInRoot
                    val workspace = onNodeWithTag("memory-leak-workspace").fetchSemanticsNode().boundsInRoot
                    val root = onRoot().fetchSemanticsNode().boundsInRoot
                    assertTrue(summary.bottom <= live.top)
                    assertTrue(live.bottom <= workspace.top)
                    assertTrue(workspace.bottom <= root.bottom)
                    assertTrue(workspace.height > 100f)
                    assertEquals(height.toFloat(), root.bottom)
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
                    if (width < 1128) {
                        onNodeWithTag("memory-object-inspector").performScrollTo().assertIsDisplayed()
                        saveCapture(onRoot().captureToImage(), "$name-inspector")
                    }
                }
            }
        }
    }

    @Test
    fun `empty leak analysis page retains the same compact bottom spacing`() {
        listOf(1280, 620).forEach { width ->
            UiLanguage.entries.forEach { language ->
                runDesktopComposeUiTest(width = width, height = 480) {
                    setContent {
                        ViewerTheme(darkTheme = false) {
                            MemoryProfilerScreen(
                                MemoryProfilerState(viewMode = MemoryProfilerViewMode.LeakCanary),
                                MemoryProfilerActions(),
                                language,
                            )
                        }
                    }
                    assertBottomSpacing(width < 1128, loaded = false)
                    onNodeWithText(if (language == UiLanguage.ENGLISH) "Leak Analysis" else "泄漏分析")
                        .assertIsDisplayed()
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertBottomSpacing(
        narrow: Boolean,
        loaded: Boolean = true,
    ) {
        val workspace = onNodeWithTag("memory-leak-workspace").getUnclippedBoundsInRoot()
        val panes = onNodeWithTag("memory-leak-panes").getUnclippedBoundsInRoot()
        val root = onRoot().getUnclippedBoundsInRoot()
        onNodeWithTag("memory-profiler-status-bar").assertDoesNotExist()
        assertEquals(4.dp, root.bottom - workspace.bottom)
        if (narrow) {
            val scrollbar = onNodeWithTag("memory-leak-horizontal-scrollbar").getUnclippedBoundsInRoot()
            assertEquals(workspace.bottom, scrollbar.bottom)
            assertEquals(scrollbar.top, panes.bottom)
        } else {
            onNodeWithTag("memory-leak-horizontal-scrollbar").assertDoesNotExist()
            assertEquals(workspace.bottom, panes.bottom)
        }
        val tags =
            if (loaded) {
                listOf("memory-leak-findings", "memory-leak-path", "memory-object-inspector")
            } else {
                listOf("memory-leak-findings")
            }
        tags.forEach { tag -> assertEquals(panes.bottom, onNodeWithTag(tag).getUnclippedBoundsInRoot().bottom) }
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
