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
    fun `import duration is localized and remains visible without engine during other feedback`() {
        UiLanguage.entries.forEach { language ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                val imported = fixture().copy(importDurationMillis = 12_340)
                var state by mutableStateOf(imported)
                setContent {
                    ViewerTheme(darkTheme = false) { MemoryProfilerScreen(state, MemoryProfilerActions(), language) }
                }
                val expected = if (language == UiLanguage.ENGLISH) "Import duration: 12.34 s" else "导入耗时：12.34 秒"
                listOf(
                    imported,
                    imported.copy(error = MemoryProfilerError("Analysis failed", "Try again")),
                    imported.copy(operationMessage = "Analyzing imported snapshot"),
                    imported.copy(isSelectionLoading = true),
                    imported.copy(warning = "Mapping unavailable", cleanupWarning = "Cleanup pending"),
                ).forEach { feedback ->
                    runOnIdle { state = feedback }
                    onNodeWithTag("memory-import-timing").assertIsDisplayed()
                    onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
                }
                runOnIdle { state = imported.copy(importDurationMillis = 0) }
                onNodeWithText(
                    if (language == UiLanguage.ENGLISH) "Import duration: 0.00 s" else "导入耗时：0.00 秒",
                    useUnmergedTree = true,
                ).assertIsDisplayed()
            }
        }
    }

    @Test
    fun `imported file path and elapsed seconds remain visible while idle`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val path = java.nio.file.Path.of("/tmp/heap captures/sample.hprof")
            var state by mutableStateOf(
                fixture().copy(
                    snapshotSummary = HeapSnapshotSummary("status-visibility", sourceFile = path),
                    importDurationMillis = 0,
                    importEngine = "RUST",
                ),
            )
            setContent {
                ViewerTheme(darkTheme = false) { MemoryProfilerScreen(state, MemoryProfilerActions()) }
            }
            onNodeWithText(path.toAbsolutePath().normalize().toString()).assertIsDisplayed()
            listOf(0L to "0.00", 8L to "0.01", 1234L to "1.23", 1236L to "1.24", 12300L to "12.30").forEach { (millis, expected) ->
                runOnIdle { state = state.copy(importDurationMillis = millis) }
                onNodeWithText("RUST · Import duration: $expected s").assertIsDisplayed()
                onNodeWithTag("memory-import-path").assertIsDisplayed()
            }
        }

    @Test
    fun `status feedback appears only for active work errors and nonblank warnings while the footer stays visible`() {
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
                    val idleGeometry = assertIdleGeometry()
                    listOf(
                        idle.copy(isDumping = true),
                        idle.copy(operationMessage = "Importing sample.hprof"),
                        idle.copy(isSelectionLoading = true),
                        idle.copy(error = MemoryProfilerError("Capture failed", "Device disconnected")),
                        idle.copy(cleanupWarning = "Temporary device file remains"),
                        idle.copy(warning = "Mapping is unavailable"),
                    ).forEach { active ->
                        runOnIdle { state = active }
                        val footer = onNodeWithTag(STATUS).assertIsDisplayed()
                        val activeGeometry = statusGeometry()
                        assertEquals(idleGeometry, activeGeometry)
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

    private fun DesktopComposeUiTest.assertIdleGeometry(): StatusGeometry {
        onNodeWithTag(STATUS).assertIsDisplayed()
        val geometry = statusGeometry()
        assertEquals(720f, geometry.rootBottom)
        assertEquals(29f, geometry.footerHeight, 0.6f)
        assertEquals(4f, geometry.footerTop - geometry.cardBottom, 0.6f)
        onNodeWithTag("memory-instance-row-42").assertIsSelected()
        return geometry
    }

    private fun DesktopComposeUiTest.statusGeometry(): StatusGeometry {
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val footer = onNodeWithTag(STATUS).fetchSemanticsNode().boundsInRoot
        val card = onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot
        return StatusGeometry(root.bottom, footer.top, footer.height, card.bottom)
    }

    private data class StatusGeometry(
        val rootBottom: Float,
        val footerTop: Float,
        val footerHeight: Float,
        val cardBottom: Float,
    )

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
