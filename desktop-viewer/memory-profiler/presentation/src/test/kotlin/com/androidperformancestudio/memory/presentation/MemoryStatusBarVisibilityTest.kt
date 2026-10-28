package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MemoryStatusBarVisibilityTest {
    @Test
    fun `status feedback appears only for active work errors and nonblank warnings then releases its height`() {
        UiLanguage.entries.forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 1280, height = 720) {
                    val idle = fixture()
                    var state by mutableStateOf(idle)
                    setContent {
                        ViewerTheme(darkTheme = dark) { MemoryProfilerScreen(state, MemoryProfilerActions(), language) }
                    }
                    val references = if (language == UiLanguage.ENGLISH) "References" else "\u5f15\u7528"
                    onNodeWithText(references).performClick().assertIsSelected()
                    assertIdleGeometry()
                    val idleHeight = onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot.height
                    listOf(
                        idle.copy(isDumping = true),
                        idle.copy(operationMessage = "Importing sample.hprof"),
                        idle.copy(isSelectionLoading = true),
                        idle.copy(error = MemoryProfilerError("Capture failed", "Device disconnected")),
                        idle.copy(cleanupWarning = "Temporary device file remains"),
                        idle.copy(warning = "Mapping is unavailable"),
                    ).forEach { active ->
                        runOnIdle { state = active }
                        val footer = onNodeWithTag(STATUS).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        val root = onRoot().fetchSemanticsNode().boundsInRoot
                        val card = onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot
                        assertEquals(root.bottom, footer.bottom)
                        assertEquals(29f, footer.height, 0.6f)
                        assertEquals(4f, footer.top - card.bottom, 0.6f)
                        assertEquals(idleHeight - footer.height, card.height, 0.6f)
                        onNodeWithText(references).assertIsSelected()
                        onNodeWithTag("memory-instance-row-42").assertIsSelected()
                        runOnIdle { state = idle }
                        assertIdleGeometry()
                        onNodeWithText(references).assertIsSelected()
                    }
                    runOnIdle { state = idle.copy(operationMessage = " \n", warning = " ", cleanupWarning = "\t") }
                    assertIdleGeometry()
                }
            }
        }
    }

    @Test
    fun `capture without a progress message still exposes cancel and error retry remains actionable`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val idle = fixture()
            var state by mutableStateOf(idle.copy(isDumping = true))
            var cancelled = 0
            var retried = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(
                        state,
                        MemoryProfilerActions(
                            onCancelOperation = {
                                cancelled++
                                state = idle
                            },
                            onRetry = {
                                retried++
                                state = idle
                            },
                        ),
                    )
                }
            }
            onNodeWithTag(STATUS).assertIsDisplayed()
            onNodeWithText("Cancel", useUnmergedTree = true).performClick()
            assertEquals(1, cancelled)
            assertIdleGeometry()
            runOnIdle { state = idle.copy(error = MemoryProfilerError("Import failed", "Unreadable snapshot")) }
            onNodeWithText("Unreadable snapshot", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Retry", useUnmergedTree = true).performClick()
            assertEquals(1, retried)
            assertIdleGeometry()
            assertEquals(idle, state)
        }

    private fun DesktopComposeUiTest.assertIdleGeometry() {
        onNodeWithTag(STATUS).assertDoesNotExist()
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val card = onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot
        assertEquals(4f, root.bottom - card.bottom, 0.6f)
        onNodeWithTag("memory-instance-row-42").assertIsSelected()
    }

    private fun fixture(): MemoryProfilerState =
        MemoryProfilerState(
            activeSnapshotId = "status-visibility",
            snapshotSummary = HeapSnapshotSummary("status-visibility"),
            classes = listOf(ClassStats("sample.Screen", 1, 32, 64)),
            heapBaseClasses = listOf(ClassStats("sample.Screen", 1, 32, 64)),
            selectedClassName = "sample.Screen",
            selectedClassInstances = listOf(MemoryInstanceRow(42, 1, 32, 64, 1, true, className = "sample.Screen")),
            selectedInstanceDetail =
                MemoryInstanceDetail(
                    42,
                    "sample.Screen",
                    32,
                    64,
                    1,
                    false,
                    null,
                    fields = listOf(MemoryInstanceField("count", "7", null, null)),
                    references =
                        listOf(
                            MemoryInstanceField(
                                name = "owner",
                                displayValue = "Root@4",
                                targetObjectId = 4,
                                targetClassName = "sample.Root",
                                depth = 0,
                                childCount = 0,
                            ),
                        ),
                    referenceChain = emptyList(),
                ),
        )

    private companion object {
        const val STATUS = "memory-profiler-status-bar"
        const val CARD = "memory-class-analysis-card"
    }
}
