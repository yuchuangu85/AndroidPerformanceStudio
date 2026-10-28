package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryClassInstanceHeightTest {
    @Test
    fun `dragging the horizontal divider moves both panes and clamps usable heights`() {
        listOf(1280, 900).forEach { width ->
            runDesktopComposeUiTest(width = width, height = 800) {
                setContent {
                    ViewerTheme(darkTheme = width == 900) {
                        MemoryClassesPane(
                            MemoryProfilerPresenter.present(fixture()),
                            MemoryProfilerActions(),
                            if (width == 900) UiLanguage.SIMPLIFIED_CHINESE else UiLanguage.ENGLISH,
                            Modifier.fillMaxSize(),
                        )
                    }
                }
                assertGeometry()
                assertEquals(0.5f, heightFraction(), 0.005f)
                val initialUpper = bounds(CLASSES).height
                val initialLower = bounds(INSTANCES).height
                drag(DIVIDER, Offset(0f, 80f))
                assertTrue(bounds(CLASSES).height > initialUpper + 40f)
                assertTrue(bounds(INSTANCES).height < initialLower - 40f)
                val enlarged = bounds(CLASSES).height
                drag(DIVIDER, Offset(0f, -160f))
                assertTrue(bounds(CLASSES).height < enlarged - 100f)
                drag(DIVIDER, Offset(0f, bounds(INSTANCES).bottom - bounds(DIVIDER).center.y - 1f))
                assertEquals(180f, bounds(INSTANCES).height, 1f)
                drag(DIVIDER, Offset(0f, bounds(CLASSES).top - bounds(DIVIDER).center.y + 1f))
                assertEquals(100f, bounds(CLASSES).height, 1f)
                assertGeometry()
            }
        }
    }

    @Test
    fun `height ratio survives data changes and restores after a short viewport`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            var viewportHeight by mutableStateOf(800.dp)
            var state by mutableStateOf(fixture())
            setContent {
                ViewerTheme(darkTheme = false) {
                    Box(Modifier.fillMaxWidth().height(viewportHeight)) {
                        MemoryClassesPane(
                            MemoryProfilerPresenter.present(state),
                            MemoryProfilerActions(),
                            UiLanguage.ENGLISH,
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            drag(DIVIDER, Offset(0f, 80f))
            val ratio = heightFraction()
            val height = bounds(CLASSES).height
            runOnIdle { state = state.copy(selectedInstanceDetail = detail(43), matchCase = true) }
            assertEquals(height, bounds(CLASSES).height, 1f)
            runOnIdle { viewportHeight = 600.dp }
            assertEquals(ratio, heightFraction(), 0.005f)
            assertGeometry()
            runOnIdle { viewportHeight = 300.dp }
            assertEquals(180f, bounds(INSTANCES).height, 1f)
            runOnIdle { viewportHeight = 220.dp }
            assertGeometry()
            runOnIdle { viewportHeight = 800.dp }
            assertEquals(ratio, heightFraction(), 0.005f)
            assertEquals(height, bounds(CLASSES).height, 1f)
        }

    @Test
    fun `height divider supports keyboard and accessibility adjustment`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryClassesPane(fixture(), MemoryProfilerActions(), UiLanguage.ENGLISH, Modifier.fillMaxSize())
                }
            }
            val divider = onNodeWithTag(DIVIDER)
            divider.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            val initial = bounds(CLASSES).height
            divider.performKeyInput { repeat(2) { pressKey(Key.DirectionDown) } }
            assertEquals(initial + 32f, bounds(CLASSES).height, 1f)
            divider.performKeyInput { repeat(2) { pressKey(Key.DirectionUp) } }
            assertEquals(initial, bounds(CLASSES).height, 1f)
            divider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
            assertEquals(180f, bounds(INSTANCES).height, 1f)
            divider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
            assertEquals(100f, bounds(CLASSES).height, 1f)
            assertGeometry()
        }

    @Test
    fun `vertical resizing preserves shared frame spacing horizontal width and inline expansion`() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            var state by mutableStateOf(fixture())
            setContent {
                ViewerTheme(darkTheme = true) {
                    MemoryProfilerScreen(
                        state,
                        MemoryProfilerActions(
                            onSelectInstance = { state = state.copy(selectedInstanceDetail = detail(it)) },
                        ),
                    )
                }
            }
            val frame = bounds("memory-class-analysis-card")
            drag("memory-instance-list-resizer", Offset(80f, 0f))
            val tableWidth = bounds("memory-instance-list-table-pane").width
            onNodeWithTag("memory-inspector-row-0").performClick()
            onNodeWithTag("memory-inspector-row-0-child-0").assertIsDisplayed()
            drag(DIVIDER, Offset(0f, -60f))
            onNodeWithTag("memory-instance-row-42").assertIsSelected()
            onNodeWithTag("memory-inspector-row-0-child-0").assertIsDisplayed()
            assertEquals(tableWidth, bounds("memory-instance-list-table-pane").width, 1f)
            assertEquals(frame, bounds("memory-class-analysis-card"))
            assertEquals(4f, frame.top - bounds("memory-distribution-card").bottom, 0.6f)
            assertEquals(4f, bounds("memory-profiler-status-bar").top - frame.bottom, 0.6f)
            val resizedHeight = bounds(CLASSES).height
            onNodeWithTag("memory-instance-row-43").performClick().assertIsSelected()
            assertEquals(resizedHeight, bounds(CLASSES).height, 1f)
            assertEquals(tableWidth, bounds("memory-instance-list-table-pane").width, 1f)
            assertGeometry()
        }

    private fun DesktopComposeUiTest.assertGeometry() {
        val upper = bounds(CLASSES)
        val divider = bounds(DIVIDER)
        val lower = bounds(INSTANCES)
        val line = bounds("memory-class-instance-divider-line")
        assertTrue(upper.height > 0f && lower.height > 0f)
        assertEquals(upper.bottom, divider.top, 1f)
        assertEquals(divider.bottom, lower.top, 1f)
        assertEquals(7f, divider.height, 0.6f)
        assertEquals(1f, line.height, 0.6f)
        assertEquals(divider.center.y, line.center.y, 0.6f)
        assertEquals(upper.left, lower.left, 1f)
        assertEquals(upper.right, lower.right, 1f)
    }

    private fun DesktopComposeUiTest.heightFraction(): Float {
        val upperHeight = bounds(CLASSES).height
        return upperHeight / (upperHeight + bounds(INSTANCES).height)
    }

    private fun DesktopComposeUiTest.bounds(tag: String): Rect {
        val node = onNodeWithTag(tag).fetchSemanticsNode()
        return node.boundsInRoot
    }

    private fun DesktopComposeUiTest.drag(
        tag: String,
        delta: Offset,
    ) {
        onNodeWithTag(tag).performMouseInput {
            moveTo(center)
            press()
            moveBy(delta, delayMillis = 100L)
            release()
        }
    }

    private fun fixture(): MemoryProfilerState {
        val snapshot = HeapSnapshotSummary("height-split")
        val classes = listOf(ClassStats("sample.Target", instanceCount = 2, shallowSize = 64))
        return MemoryProfilerState(
            activeSnapshotId = snapshot.id,
            snapshotSummary = snapshot,
            snapshotSummaries = listOf(snapshot),
            classes = classes,
            heapBaseClasses = classes,
            selectedClassName = "sample.Target",
            selectedClassifierId = "class:sample.Target",
            selectedClassInstances =
                listOf(
                    MemoryInstanceRow(42, 1, 32, 48, 1, true, className = "sample.Target"),
                    MemoryInstanceRow(43, 2, 32, 48, 1, true, className = "sample.Target"),
                ),
            selectedInstanceDetail = detail(42),
            inspectorObjects = mapOf(MemoryInspectorObjectKey(142) to MemoryInspectorObjectState.Loaded(detail(142))),
        )
    }

    private fun detail(objectId: Long): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = objectId,
            className = "sample.Target",
            shallowSize = 32,
            retainedSize = 48,
            depth = 1,
            isArray = false,
            elementCount = null,
            fields =
                if (objectId == 142L) {
                    listOf(MemoryInstanceField("leaf", "1", null, null))
                } else {
                    listOf(MemoryInstanceField("child", "sample.Child #142", 142, "sample.Child"))
                },
            referenceChain = emptyList(),
        )

    private companion object {
        const val CLASSES = "memory-classes-card"
        const val INSTANCES = "memory-instances-card"
        const val DIVIDER = "memory-class-instance-divider"
    }
}
