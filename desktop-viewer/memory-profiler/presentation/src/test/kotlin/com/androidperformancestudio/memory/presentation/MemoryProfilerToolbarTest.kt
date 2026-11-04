@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.ProfilerCompactButton
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
class MemoryProfilerToolbarTest {
    @Test
    fun `capture and analysis share one unlabeled row across widths languages and themes`() {
        listOf(1280, 900, 500).forEach { width ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = width, height = 120) {
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                ToolbarFixture(language = language)
                            }
                        }

                        assertSingleRowLayout()
                        val image = onNodeWithTag("memory-profiler-toolbar").captureToImage()
                        assertEquals(width, image.width)
                        val locale = if (language == UiLanguage.ENGLISH) "en" else "zh"
                        val theme = if (dark) "dark" else "light"
                        saveCapture(image, "memory-toolbar-$width-$locale-$theme")
                    }
                }
            }
        }
    }

    @Test
    fun `capture and analysis slots dispatch without a capture backend`() =
        runDesktopComposeUiTest(width = 1280, height = 120) {
            val events = mutableListOf<String>()
            setContent {
                ViewerTheme(darkTheme = false) {
                    ToolbarFixture(onAction = { events += it })
                }
            }

            listOf("Dump Heap", "Capture Native Heap", "Dump Bitmaps", "Classes", "Mapping").forEach {
                onNodeWithText(it).assertIsEnabled().performClick()
            }
            assertEquals(listOf("dump", "native", "bitmaps", "classes", "mapping"), events)
        }

    @Test
    fun `disabled capture and analysis buttons never dispatch callbacks`() =
        runDesktopComposeUiTest(width = 1280, height = 120) {
            val events = mutableListOf<String>()
            var state by mutableStateOf(readyState().copy(selectedDeviceSerial = null))
            var targetReady by mutableStateOf(true)
            setContent {
                ViewerTheme(darkTheme = true) {
                    ToolbarFixture(state = state, targetReady = targetReady, onAction = { events += it })
                }
            }

            listOf(
                readyState().copy(selectedDeviceSerial = null) to true,
                readyState().copy(selectedProcessId = null) to true,
                readyState().copy(isDumping = true) to true,
                readyState() to false,
            ).forEach { (nextState, ready) ->
                runOnIdle {
                    state = nextState
                    targetReady = ready
                }
                val dumpLabel = if (nextState.isDumping) "Dumping\u2026" else "Dump Heap"
                listOf(dumpLabel, "Capture Native Heap", "Dump Bitmaps").forEach {
                    onNodeWithText(it).assertIsNotEnabled().performClick()
                }
                onNodeWithText("Classes").assertIsEnabled()
                onNodeWithText("Mapping").assertIsEnabled()
            }
            assertTrue(events.isEmpty())

            runOnIdle { state = MemoryProfilerState() }
            onNodeWithText("Mapping").assertIsNotEnabled().performClick()
            assertTrue(events.isEmpty())
            onNodeWithText("Classes").performClick()
            assertEquals(listOf("classes"), events)
        }

    @Test
    @Suppress("LongMethod")
    fun `capture and analysis share one horizontal scroll state`() =
        runDesktopComposeUiTest(width = 500, height = 120) {
            val events = mutableListOf<String>()
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerToolbar(
                        language = UiLanguage.ENGLISH,
                        captureContent = {
                            MemoryProfilerDumpHeapButton(readyState(), { events += "dump" })
                            Spacer(Modifier.width(800.dp))
                            MemoryProfilerCaptureNativeHeapButton(readyState(), { events += "native" })
                        },
                        analysisContent = {
                            ProfilerCompactButton("Classes", { events += "classes" })
                            Spacer(Modifier.width(800.dp))
                            ProfilerCompactButton("Mapping", { events += "mapping" })
                        },
                    )
                }
            }

            val scroll = onNodeWithTag("memory-toolbar-scroll")
            assertEquals(0f, scroll.scrollPosition())

            onNodeWithText("Capture Native Heap").performScrollTo().assertIsDisplayed().performClick()
            val capturePosition = scroll.scrollPosition()
            assertTrue(capturePosition > 0f)

            onNodeWithText("Mapping").performScrollTo().assertIsDisplayed().performClick()
            val analysisPosition = scroll.scrollPosition()
            assertTrue(analysisPosition > 0f)

            onNodeWithText("Dump Heap").performScrollTo().assertIsDisplayed().performClick()
            assertTrue(scroll.scrollPosition() >= 0f)
            onNodeWithText("Classes").performScrollTo().assertIsDisplayed().performClick()
            assertTrue(scroll.scrollPosition() >= 0f)
            assertSingleRowLayout()
            assertEquals(listOf("native", "mapping", "dump", "classes"), events)
        }

    private fun ComposeUiTest.assertSingleRowLayout() {
        val root = onNodeWithTag("memory-profiler-toolbar").assertHeightIsEqualTo(40.dp)
        val scroll = onNodeWithTag("memory-toolbar-scroll").assertHeightIsEqualTo(40.dp)
        val capture = onNodeWithTag("memory-toolbar-capture-content").assertHeightIsEqualTo(40.dp)
        val analysis = onNodeWithTag("memory-toolbar-analysis-content").assertHeightIsEqualTo(40.dp)
        onNodeWithTag("memory-toolbar-group-gap").assertWidthIsEqualTo(8.dp)
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val scrollBounds = scroll.fetchSemanticsNode().boundsInRoot
        val captureBounds = capture.fetchSemanticsNode().boundsInRoot
        val analysisBounds = analysis.fetchSemanticsNode().boundsInRoot
        assertEquals(rootBounds.top, scrollBounds.top)
        assertEquals(rootBounds.bottom, scrollBounds.bottom)
        assertEquals(scrollBounds.top, captureBounds.top)
        assertEquals(scrollBounds.top, analysisBounds.top)
        assertTrue(analysisBounds.top == captureBounds.top)
        onNodeWithTag("memory-toolbar-capture-label").assertDoesNotExist()
        onNodeWithTag("memory-toolbar-analysis-label").assertDoesNotExist()
    }

    private fun SemanticsNodeInteraction.scrollPosition(): Float =
        fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

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
                bitmap.setRGB(x, y, pixels[x, y].toArgb())
            }
        }
        assertTrue(ImageIO.write(bitmap, "png", output.toFile()))
    }
}

private fun readyState(): MemoryProfilerState =
    MemoryProfilerState(
        devices = listOf(MemoryDeviceOption("toolbar-device", "Toolbar device", supportsBitmapDump = true)),
        selectedDeviceSerial = "toolbar-device",
        selectedProcessId = 42,
        activeSnapshotId = "toolbar-snapshot",
    )

@Composable
private fun ToolbarFixture(
    language: UiLanguage = UiLanguage.ENGLISH,
    state: MemoryProfilerState = readyState(),
    targetReady: Boolean = true,
    onAction: (String) -> Unit = {},
) {
    val english = language == UiLanguage.ENGLISH
    MemoryProfilerToolbar(
        language = language,
        captureContent = {
            MemoryProfilerDumpHeapButton(state, { onAction("dump") }, language, targetReady)
            MemoryProfilerCaptureNativeHeapButton(state, { onAction("native") }, language, targetReady)
            ProfilerCompactButton(
                if (english) "Record ART Samples" else "录制 ART 分配采样",
                { onAction("allocations") },
                enabled = targetReady,
            )
            MemoryProfilerDumpBitmapsButton(state, { onAction("bitmaps") }, language, targetReady)
            ProfilerCompactButton(if (english) "Inject Agent" else "注入 Agent", { onAction("agent") })
        },
        analysisContent = {
            ProfilerCompactButton(if (english) "Overview" else "概览", { onAction("overview") }, selected = true)
            ProfilerCompactButton(if (english) "Classes" else "类列表", { onAction("classes") })
            ProfilerCompactButton(if (english) "Dominators" else "支配树", { onAction("dominators") })
            ProfilerCompactButton(if (english) "Diff" else "差异", { onAction("diff") })
            ProfilerCompactButton(if (english) "Bitmaps" else "Bitmap", { onAction("bitmap-view") })
            ProfilerCompactButton(if (english) "Native Heap" else "Native 堆", { onAction("native-view") })
            ProfilerCompactButton(if (english) "ART Samples" else "ART 采样", { onAction("art-view") })
            ProfilerCompactButton(if (english) "Leak Analysis" else "泄漏分析", { onAction("leaks") })
            ProfilerCompactButton(
                "Mapping",
                { onAction("mapping") },
                enabled = state.activeSnapshotId != null,
            )
        },
    )
}
