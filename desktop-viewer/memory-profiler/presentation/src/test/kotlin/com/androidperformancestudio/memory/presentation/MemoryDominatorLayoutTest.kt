package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryDominatorLayoutTest {
    @Test
    fun `filters and the unified tree details card fill the viewport in empty loaded and narrow layouts`() {
        listOf(1280 to 800, 900 to 600, 500 to 480).forEach { (width, height) ->
            UiLanguage.entries.forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = width, height = height) {
                        var loaded by mutableStateOf(false)
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                MemoryProfilerScreen(fixture(loaded), MemoryProfilerActions(), language)
                            }
                        }
                        listOf(false, true).forEach { hasData ->
                            runOnIdle { loaded = hasData }
                            assertFrameGeometry(width < 729)
                            if (hasData) {
                                assertEquals(bounds(TREE).bottom, bounds("memory-dominator-list").bottom, 0.6f)
                                if (width >= 900) {
                                    onNodeWithTag("memory-inspector-horizontal-scrollbar").assertDoesNotExist()
                                    assertEquals(bounds(DETAILS).bottom, bounds("memory-inspector-table").bottom, 0.6f)
                                }
                            }
                        }
                        if (width == 1280 && language == UiLanguage.ENGLISH && !dark) saveCapture()
                        if (width < 729) onNodeWithTag(DETAILS).performScrollTo().assertIsDisplayed()
                    }
                }
            }
        }
    }

    @Test
    fun `dragging the split changes widths and clamps both panes without moving filters or the bottom edge`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                ViewerTheme(darkTheme = false) { MemoryProfilerScreen(fixture(true), MemoryProfilerActions()) }
            }
            val filters = bounds("memory-filters-card")
            val frame = bounds(FRAME)
            val initialWidth = bounds(TREE).width
            drag(Offset(80f, 0f))
            assertEquals(initialWidth + 80f, bounds(TREE).width, 1f)
            drag(Offset(-160f, 0f))
            assertEquals(initialWidth - 80f, bounds(TREE).width, 1f)
            drag(Offset(frame.right - bounds(SPLITTER).center.x - 1f, 0f))
            assertEquals(260f, bounds(DETAILS).width, 1f)
            drag(Offset(frame.left - bounds(SPLITTER).center.x + 1f, 0f))
            assertEquals(260f, bounds(TREE).width, 1f)
            assertEquals(filters, bounds("memory-filters-card"))
            assertEquals(frame, bounds(FRAME))
            assertFrameGeometry(false)
        }

    @Test
    fun `split ratio survives instance selection language updates and a narrow viewport round trip`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var width by mutableStateOf(1280.dp)
            var language by mutableStateOf(UiLanguage.ENGLISH)
            var state by mutableStateOf(fixture(true))
            setContent {
                ViewerTheme(darkTheme = false) {
                    Box(Modifier.width(width)) {
                        MemoryProfilerScreen(
                            state,
                            MemoryProfilerActions(
                                onSelectInstance = { state = state.copy(selectedInstanceDetail = detail(it)) },
                            ),
                            language,
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            drag(Offset(-80f, 0f))
            val fraction = treeFraction()
            onNode(hasText("▸") and hasAnyAncestor(hasTestTag("memory-dominator-tree-row-42"))).performClick()
            onNodeWithTag("memory-dominator-tree-row-44").assertIsDisplayed()
            onNodeWithText("References").performClick()
            onNodeWithTag("memory-dominator-tree-row-43").performClick()
            assertEquals(43L, state.selectedInstanceDetail?.objectId)
            onNodeWithTag("memory-show-nearest-gc-root-only").assertIsDisplayed()
            runOnIdle { language = UiLanguage.SIMPLIFIED_CHINESE }
            assertEquals(fraction, treeFraction(), 0.005f)
            onNodeWithTag("memory-dominator-tree-row-44").assertIsDisplayed()
            runOnIdle { width = 900.dp }
            assertEquals(fraction, treeFraction(), 0.005f)
            assertFrameGeometry(false)
            runOnIdle { width = 640.dp }
            assertFrameGeometry(true)
            assertEquals(260f, bounds(TREE).width, 1f)
            assertEquals(260f, bounds(DETAILS).width, 1f)
            runOnIdle { width = 1280.dp }
            assertEquals(fraction, treeFraction(), 0.005f)
            assertEquals(43L, state.selectedInstanceDetail?.objectId)
        }

    @Test
    fun `splitter supports keyboard and accessibility width adjustment`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                ViewerTheme(darkTheme = true) { MemoryProfilerScreen(fixture(true), MemoryProfilerActions()) }
            }
            val splitter = onNodeWithTag(SPLITTER)
            splitter.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            val initial = bounds(TREE).width
            splitter.performKeyInput { pressKey(Key.DirectionLeft) }
            assertEquals(initial - 16f, bounds(TREE).width, 1f)
            splitter.performKeyInput { pressKey(Key.DirectionRight) }
            assertEquals(initial, bounds(TREE).width, 1f)
            splitter.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
            assertEquals(260f, bounds(TREE).width, 1f)
            splitter.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
            assertEquals(260f, bounds(DETAILS).width, 1f)
        }

    private fun DesktopComposeUiTest.assertFrameGeometry(narrow: Boolean) {
        val root = onRoot().getUnclippedBoundsInRoot()
        val frame = bounds(FRAME)
        val filters = bounds("memory-filters-card")
        val tree = bounds(TREE)
        val details = bounds(DETAILS)
        val splitter = bounds(SPLITTER)
        val line = bounds("memory-dominator-details-divider-line")
        assertEquals(filters.bottom, frame.bottom, 0.6f)
        assertEquals(frame.bottom, tree.bottom, 0.6f)
        assertEquals(frame.bottom, details.bottom, 0.6f)
        assertEquals(tree.right, splitter.left, 0.6f)
        assertEquals(splitter.right, details.left, 0.6f)
        assertEquals(7f, splitter.width, 0.6f)
        assertEquals(1f, line.width, 0.6f)
        assertEquals(splitter.center.x, line.center.x, 0.6f)
        assertEquals(4f, frame.left - filters.right, 0.6f)
        onNodeWithTag("memory-profiler-status-bar").assertDoesNotExist()
        if (narrow) {
            val scrollbar = bounds("memory-analysis-horizontal-scrollbar")
            assertEquals(scrollbar.top, frame.bottom, 0.6f)
            assertEquals(4f, root.bottom.value - scrollbar.bottom, 0.6f)
        } else {
            onNodeWithTag("memory-analysis-horizontal-scrollbar").assertDoesNotExist()
            assertEquals(4f, root.bottom.value - frame.bottom, 0.6f)
        }
        onNode(hasTestTag(TREE) and hasAnyAncestor(hasTestTag(FRAME))).assertExists()
        onNode(hasTestTag(DETAILS) and hasAnyAncestor(hasTestTag(FRAME))).assertExists()
        onNodeWithTag("memory-analysis-card").assertDoesNotExist()
    }

    private fun DesktopComposeUiTest.bounds(tag: String): Rect {
        val bounds = onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return Rect(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
    }

    private fun DesktopComposeUiTest.treeFraction(): Float {
        val treeWidth = bounds(TREE).width
        return treeWidth / (treeWidth + bounds(DETAILS).width)
    }

    private fun DesktopComposeUiTest.drag(delta: Offset) {
        onNodeWithTag(SPLITTER).performMouseInput {
            moveTo(center)
            press()
            moveBy(delta)
            release()
        }
    }

    private fun DesktopComposeUiTest.saveCapture() {
        val image = onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val output = Path.of("build/screenshots/memory-dominators-unified-1280-en-light.png")
        Files.createDirectories(output.parent)
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) bitmap.setRGB(x, y, pixels[x, y].toArgb())
        }
        assertTrue(ImageIO.write(bitmap, "png", output.toFile()))
    }

    private fun fixture(loaded: Boolean): MemoryProfilerState =
        MemoryProfilerState(
            viewMode = MemoryProfilerViewMode.Dominators,
            snapshotSummary = if (loaded) HeapSnapshotSummary("dominator-layout") else null,
            classes = if (loaded) listOf(ClassStats("sample.Screen", 3, 96, 1024)) else emptyList(),
            dominatorRows =
                if (loaded) {
                    listOf(
                        MemoryDominatorRow(42, "sample.Screen", 32, 1024, 0, null),
                        MemoryDominatorRow(43, "sample.Screen", 32, 128, 0, null),
                        MemoryDominatorRow(44, "sample.Child", 32, 64, 1, 42),
                    )
                } else {
                    emptyList()
                },
            selectedInstanceDetail = if (loaded) detail(42) else null,
        )

    private fun detail(id: Long): MemoryInstanceDetail =
        MemoryInstanceDetail(
            id,
            "sample.Screen",
            32,
            1024,
            1,
            false,
            null,
            fields = listOf(MemoryInstanceField("count", "7", null, null)),
            references = listOf(MemoryInstanceField("owner", "Root@4", 4, "sample.Root", depth = 0, childCount = 0)),
            referenceChain = emptyList(),
        )

    private companion object {
        const val FRAME = "memory-dominator-analysis-card"
        const val TREE = "memory-dominator-tree-pane"
        const val DETAILS = "memory-inspector-card"
        const val SPLITTER = "memory-dominator-details-resizer"
    }
}
