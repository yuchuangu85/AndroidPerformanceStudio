package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class MemoryHeapClassifierUiTest {
    @Test
    fun `All and App retain expandable heap rows across widths languages and themes`() {
        listOf(1280, 900).forEach { width ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = width, height = 720) {
                        var state by mutableStateOf(fixture())
                        var selected: String? = null
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                MemoryProfilerScreen(
                                    state,
                                    MemoryProfilerActions(
                                        onHeapFilterChange = { heap ->
                                            val classes = state.perHeapClasses[heap] ?: state.classes
                                            state =
                                                state.copy(
                                                    heapFilter = heap,
                                                    heapBaseClasses = classes,
                                                )
                                        },
                                        onSelectClassifier = { selected = it.id },
                                    ),
                                    language,
                                )
                            }
                        }
                        onNodeWithTag("memory-classifier-row-heap:App/class:sample.Shared").assertDoesNotExist()
                        onNodeWithTag("memory-classifier-toggle-heap:App").performClick()
                        onNodeWithTag("memory-classifier-row-heap:App/class:sample.Shared").assertIsDisplayed()
                        onNodeWithTag("memory-classifier-toggle-heap:App").performClick()
                        assertNull(selected, "Expanding a heap must not query all of its instances")
                        val roots =
                            listOf("App", "Image", "Zygote").map { heap ->
                                onNodeWithTag("memory-classifier-row-heap:$heap")
                                    .assertIsDisplayed()
                                    .fetchSemanticsNode()
                                    .boundsInRoot
                            }
                        assertEquals(20f, roots[1].top - roots[0].top)
                        assertEquals(20f, roots[2].top - roots[1].top)
                        saveCapture(onRoot().captureToImage(), "memory-heap-groups-$width-${language.name}-$dark")
                        onNodeWithContentDescription(if (language == UiLanguage.ENGLISH) "Heap" else "堆").performClick()
                        onNodeWithText("App").performClick()
                        onNodeWithText("App heap").assertIsDisplayed()
                        onNodeWithText("Image heap").assertDoesNotExist()
                        onNodeWithText("Zygote heap").assertDoesNotExist()
                        onNodeWithTag("memory-classifier-row-heap:App/class:sample.Shared")
                            .performScrollTo()
                            .performClick()
                        assertEquals("heap:App/class:sample.Shared", selected)
                        saveCapture(onRoot().captureToImage(), "memory-app-heap-$width-${language.name}-$dark")
                    }
                }
            }
        }
    }

    private fun fixture(): MemoryProfilerState {
        val app =
            listOf(ClassStats("sample.Shared", 2, 20, 150, reachableShallowSize = 150, heapNames = setOf("App")))
        val image =
            listOf(ClassStats("sample.Image", 1, 12, 100, reachableShallowSize = 100, heapNames = setOf("Image")))
        val zygote =
            listOf(ClassStats("sample.Shared", 3, 30, 50, reachableShallowSize = 50, heapNames = setOf("Zygote")))
        return MemoryProfilerState(
            snapshotSummary = HeapSnapshotSummary("heap-group-fixture"),
            activeSnapshotId = "heap-group-fixture",
            classes = app + image + zygote,
            heapBaseClasses = app + image + zygote,
            perHeapClasses = mapOf("App" to app, "Image" to image, "Zygote" to zygote),
            availableHeaps = listOf("App", "Image", "Zygote"),
        )
    }

    private fun saveCapture(
        image: ImageBitmap,
        name: String,
    ) {
        val output = Path.of("build/screenshots/$name.png")
        Files.createDirectories(output.parent)
        val pixels = image.toPixelMap()
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = pixels[x, y]
                bitmap.setRGB(
                    x,
                    y,
                    ((color.alpha * 255).toInt() shl 24) or ((color.red * 255).toInt() shl 16) or
                        ((color.green * 255).toInt() shl 8) or (color.blue * 255).toInt(),
                )
            }
        }
        ImageIO.write(bitmap, "png", output.toFile())
    }
}
