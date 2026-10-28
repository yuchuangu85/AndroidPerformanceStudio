package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryLeakWarningPlacementTest {
    @Test
    fun `table warnings stay in their name column before and after horizontal scrolling`() {
        listOf(false, true).forEach { legacy ->
            UiLanguage.entries.forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = 760, height = 800) {
                        val state = leakingState()
                        var selectedClass: String? = null
                        var selectedInstance: Long? = null
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                val actions =
                                    MemoryProfilerActions(
                                        onSelectClassifier = { selectedClass = it.className },
                                        onSelectInstance = { selectedInstance = it },
                                    )
                                if (legacy) {
                                    MemoryProfilerClassListPane(
                                        MemoryProfilerPresenter.present(state),
                                        actions,
                                        language,
                                        Modifier.fillMaxSize(),
                                    )
                                } else {
                                    MemoryProfilerScreen(state, actions, language)
                                }
                            }
                        }
                        assertPinnedClassWarning()
                        assertPinnedInstanceWarning()
                        assertClassMetricsAligned()
                        if (!legacy && language == UiLanguage.ENGLISH && !dark) saveWarningCapture()
                        val classRow = onNodeWithTag("memory-classifier-row-class:$LONG_CLASS")
                        val classLeft = classRow.getUnclippedBoundsInRoot().left
                        scrollColumns("memory-classifier-table")
                        assertTrue(
                            classRow.getUnclippedBoundsInRoot().left < classLeft,
                            "Class table did not scroll: legacy=$legacy",
                        )
                        assertPinnedClassWarning()
                        val instanceRow = onNodeWithTag("memory-instance-row-1")
                        val instanceLeft = instanceRow.getUnclippedBoundsInRoot().left
                        scrollColumns("memory-instance-table")
                        assertTrue(instanceRow.getUnclippedBoundsInRoot().left < instanceLeft)
                        assertPinnedInstanceWarning()
                        classRow.performClick()
                        instanceRow.performClick()
                        runOnIdle {
                            assertEquals(LONG_CLASS, selectedClass)
                            assertEquals(1L, selectedInstance)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `instance title warning follows its text and survives a truncated long title`() {
        listOf("sample.Screen", LONG_CLASS).forEach { name ->
            UiLanguage.entries.forEach { language ->
                runDesktopComposeUiTest(width = 900, height = 720) {
                    setContent {
                        ViewerTheme(darkTheme = false) {
                            MemoryProfilerScreen(leakingState(name), MemoryProfilerActions(), language)
                        }
                    }
                    val text =
                        onNodeWithTag("memory-instance-list-title-text", useUnmergedTree = true)
                            .getUnclippedBoundsInRoot()
                    val icon =
                        onNodeWithTag("memory-leak-instance-class-warning", useUnmergedTree = true)
                            .assertIsDisplayed()
                            .getUnclippedBoundsInRoot()
                    assertEquals(14f, icon.right.value - icon.left.value, 0.6f)
                    assertEquals(6f, icon.left.value - text.right.value, 0.6f)
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertPinnedClassWarning() {
        val viewport = onNodeWithTag("memory-classifier-viewport").fetchSemanticsNode().boundsInRoot
        val cellTag = "memory-classifier-name-cell-class:$LONG_CLASS"
        val cell = onNodeWithTag(cellTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val icon =
            onNode(
                hasTestTag("memory-classifier-leak-warning-class:$LONG_CLASS") and hasAnyAncestor(hasTestTag(cellTag)),
                useUnmergedTree = true,
            ).assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(14f, icon.width, 0.6f)
        assertEquals(minOf(cell.right.value, viewport.right) - 4f, icon.right, 0.6f)
        val name =
            onNodeWithTag("memory-classifier-name-class:$LONG_CLASS", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertTrue(name.right.value <= icon.left, "Class text must leave room for the warning")
        val count = onNodeWithTag("memory-classifier-header-TOTAL_COUNT").getUnclippedBoundsInRoot()
        assertTrue(icon.right < count.left.value, "Class warning must be before the numeric columns")
    }

    private fun DesktopComposeUiTest.assertClassMetricsAligned() {
        val header = onNodeWithTag("memory-classifier-header-TOTAL_COUNT").getUnclippedBoundsInRoot()
        val count =
            onNode(
                hasText("2") and hasAnyAncestor(hasTestTag("memory-classifier-row-class:$LONG_CLASS")),
                useUnmergedTree = true,
            ).getUnclippedBoundsInRoot()
        assertEquals(
            (header.left.value + header.right.value) / 2,
            (count.left.value + count.right.value) / 2,
            0.6f,
        )
    }

    private fun DesktopComposeUiTest.assertPinnedInstanceWarning() {
        val viewport = onNodeWithTag("memory-instance-list-table-pane").fetchSemanticsNode().boundsInRoot
        val cell = onNodeWithTag("memory-instance-name-cell-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val inCell = hasAnyAncestor(hasTestTag("memory-instance-name-cell-1"))
        val icon =
            onNode(
                hasTestTag("memory-leak-instance-warning-1") and inCell,
                useUnmergedTree = true,
            ).assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(14f, icon.width, 0.6f)
        assertEquals(minOf(cell.right.value, viewport.right - 10f) - 4f, icon.right, 0.6f)
        assertTrue(icon.right < cell.right.value, "Instance warning must remain inside the name column")
        val name = onNodeWithTag("memory-instance-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(name.right.value <= icon.left, "Instance text must leave room for the warning")
        onNodeWithTag("memory-leak-instance-warning-2", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun DesktopComposeUiTest.scrollColumns(tag: String) {
        val table = hasTestTag(tag) or hasAnyDescendant(hasTestTag(tag))
        val horizontal =
            SemanticsMatcher("horizontal content overflows") {
                SemanticsProperties.HorizontalScrollAxisRange in it.config &&
                    it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f
            }
        onNode(table and horizontal).performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue(scroll(40f, 0f))
        }
    }

    private fun DesktopComposeUiTest.saveWarningCapture() {
        val image = onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val output = Path.of("build/screenshots/memory-leak-warning-name-column-760-en-light.png")
        Files.createDirectories(output.parent)
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) bitmap.setRGB(x, y, pixels[x, y].toArgb())
        }
        assertTrue(ImageIO.write(bitmap, "png", output.toFile()))
    }

    private fun leakingState(name: String = LONG_CLASS): MemoryProfilerState =
        MemoryProfilerState(
            activeSnapshotId = "leak-warning-placement",
            snapshotSummary = HeapSnapshotSummary("leak-warning-placement"),
            classes = listOf(ClassStats(name, instanceCount = 2, shallowSize = 48)),
            heapBaseClasses = listOf(ClassStats(name, instanceCount = 2, shallowSize = 48)),
            lifecycleLeakObjects = listOf(MemoryLifecycleLeakObject(1, name, "App")),
            selectedClassName = name,
            selectedClassifierLabel = name,
            selectedClassInstances =
                listOf(
                    MemoryInstanceRow(1, 1, 24, 48, 2, true, className = name),
                    MemoryInstanceRow(2, 2, 24, 24, 2, true, className = name),
                ),
            selectedInstanceDetail =
                MemoryInstanceDetail(
                    1,
                    name,
                    24,
                    48,
                    2,
                    false,
                    null,
                    fields = listOf(MemoryInstanceField("count", "1", null, null)),
                    referenceChain = emptyList(),
                ),
        )

    private companion object {
        val LONG_CLASS = "sample." + "VeryLongLeakingScreenName".repeat(12)
    }
}
