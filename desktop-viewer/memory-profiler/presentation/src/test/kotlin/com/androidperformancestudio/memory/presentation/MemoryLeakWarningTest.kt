package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryLeakWarningTest {
    @Test
    fun `main class workspace shows warnings on leak count class instance and inspector`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 1280, height = 720) {
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            MemoryProfilerScreen(leakingState(), MemoryProfilerActions(), language)
                        }
                    }
                    val description = if (language == UiLanguage.ENGLISH) "Leak Suspects" else "泄漏疑点"
                    listOf(
                        "memory-leak-summary-warning",
                        "memory-classifier-leak-warning-class:sample.Screen",
                        "memory-leak-instance-class-warning",
                        "memory-leak-instance-warning-1",
                        "memory-leak-inspector-warning",
                    ).forEach { tag ->
                        val icon =
                            onNode(
                                hasContentDescription(description) and hasTestTag(tag),
                                useUnmergedTree = true,
                            ).assertIsDisplayed()
                        val pixels = icon.captureToImage().toPixelMap()
                        val colors =
                            (0 until pixels.height).flatMap { y -> (0 until pixels.width).map { x -> pixels[x, y] } }
                        assertTrue(colors.distinct().size > 1, "$tag is blank")
                    }
                }
            }
        }
    }

    @Test
    fun `instance warnings are derived again after navigation and cleared with new snapshot evidence`() {
        val input = leakingState()
        val presented = MemoryProfilerPresenter.present(input)
        assertEquals(listOf(true, false), presented.selectedClassInstances.map { it.isLeakWarning })
        val navigated =
            input.copy(
                selectedClassInstances = input.selectedClassInstances.map { it.copy(isLeakWarning = false) },
            )
        assertEquals(
            listOf(true, false),
            MemoryProfilerPresenter.present(navigated).selectedClassInstances.map { it.isLeakWarning },
        )
        assertEquals(
            listOf(false, false),
            MemoryProfilerPresenter
                .present(presented.copy(lifecycleLeakObjects = emptyList()))
                .selectedClassInstances
                .map { it.isLeakWarning },
        )
    }

    @Test
    fun `healthy instance of leaking class has no object inspector warning`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val input = leakingState()
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(
                        input.copy(selectedInstanceDetail = input.selectedInstanceDetail!!.copy(objectId = 2)),
                        MemoryProfilerActions(),
                    )
                }
            }
            onNode(
                hasContentDescription("Leak Suspects") and
                    hasAnyAncestor(hasTestTag("memory-object-inspector")),
                useUnmergedTree = true,
            ).assertDoesNotExist()
        }

    @Test
    fun `leak count warning disappears when the current scope has no candidates`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var state by mutableStateOf(leakingState())
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(state, MemoryProfilerActions())
                }
            }
            onNodeWithTag("memory-leak-summary-warning").assertIsDisplayed()
            runOnIdle { state = state.copy(lifecycleLeakObjects = emptyList()) }
            onNodeWithTag("memory-leak-summary-warning").assertDoesNotExist()
        }

    @Test
    fun `collapsed package containing a leaking class retains its warning`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(
                        leakingState().copy(arrangeBy = MemoryArrangeBy.PACKAGE),
                        MemoryProfilerActions(),
                    )
                }
            }
            onNodeWithText("sample").assertIsDisplayed()
            onNodeWithTag("memory-classifier-toggle-group:sample").performClick()
            onNodeWithTag("memory-classifier-leak-warning-group:sample", useUnmergedTree = true).assertIsDisplayed()
            onNode(
                hasText("sample.Screen") and hasAnyAncestor(hasTestTag("memory-classes-card")),
                useUnmergedTree = true,
            ).assertDoesNotExist()
        }

    private fun leakingState(): MemoryProfilerState =
        MemoryProfilerState(
            snapshotSummary = HeapSnapshotSummary("leak-snapshot"),
            activeSnapshotId = "leak-snapshot",
            classes = listOf(ClassStats("sample.Screen", instanceCount = 2, shallowSize = 48)),
            heapBaseClasses = listOf(ClassStats("sample.Screen", instanceCount = 2, shallowSize = 48)),
            lifecycleLeakObjects = listOf(MemoryLifecycleLeakObject(1, "sample.Screen", "App")),
            selectedClassName = "sample.Screen",
            selectedClassifierId = "class:sample.Screen",
            selectedClassInstances =
                listOf(
                    MemoryInstanceRow(1, 1, 24, 48, 2, true),
                    MemoryInstanceRow(2, 2, 24, 24, 2, true),
                ),
            selectedInstanceDetail =
                MemoryInstanceDetail(
                    1,
                    "sample.Screen",
                    24,
                    48,
                    2,
                    false,
                    null,
                    fields = emptyList(),
                    referenceChain = emptyList(),
                ),
        )
}
