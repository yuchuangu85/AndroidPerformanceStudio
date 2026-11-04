package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MemoryHeapPagingUiTest {
    @Test
    fun `instance pages display the global total and issue bounded offsets from both entry points`() {
        listOf(false, true).forEach { legacyEntry ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                var state by mutableStateOf(instanceState())
                val offsets = mutableListOf<Int>()
                val selected = mutableListOf<Long>()
                val actions =
                    MemoryProfilerActions(onLoadInstancePage = { offsets += it }, onSelectInstance = { selected += it })
                setContent {
                    ViewerTheme(darkTheme = false) {
                        if (legacyEntry) {
                            MemoryProfilerClassListPane(state, actions, UiLanguage.ENGLISH)
                        } else {
                            MemoryProfilerScreen(state, actions)
                        }
                    }
                }

                onNodeWithTag("memory-instance-total-count").assertTextEquals("Count: 5")
                onNodeWithTag("memory-instance-page-range").assertTextEquals("1-2 of 5")
                onNodeWithTag("memory-instance-page-previous").assertIsNotEnabled()
                onNodeWithTag("memory-instance-page-next").performClick()
                assertEquals(listOf(2), offsets)
                onNodeWithTag("memory-instance-row-42").performClick()
                assertEquals(listOf(42L), selected)

                runOnIdle { state = state.copy(instancePageOffset = 2) }
                onNodeWithTag("memory-instance-page-range").assertTextEquals("3-4 of 5")
                onNodeWithTag("memory-instance-page-previous").performClick()
                onNodeWithTag("memory-instance-page-next").performClick()
                assertEquals(listOf(2, 0, 4), offsets)

                runOnIdle {
                    state =
                        state.copy(
                            instancePageOffset = 4,
                            selectedClassInstances = state.selectedClassInstances.take(1),
                        )
                }
                onNodeWithTag("memory-instance-page-range").assertTextEquals("5-5 of 5")
                onNodeWithTag("memory-instance-page-next").assertIsNotEnabled()
                onNodeWithTag("memory-instance-page-previous").performClick()
                assertEquals(listOf(2, 0, 4, 2), offsets)
            }
        }
    }

    @Test
    fun `complete legacy instance lists keep their count without page controls`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(instanceState().copy(instanceTotalCount = 2), MemoryProfilerActions())
                }
            }
            onNodeWithTag("memory-instance-total-count").assertTextEquals("Count: 2")
            onNodeWithTag("memory-instance-page").assertDoesNotExist()
        }

    @Test
    fun `instance paging localizes count ranges and disables navigation during selection queries`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    MemoryProfilerScreen(
                        instanceState().copy(isSelectionLoading = true),
                        MemoryProfilerActions(),
                        UiLanguage.SIMPLIFIED_CHINESE,
                    )
                }
            }
            onNodeWithTag("memory-instance-page-range").assertTextEquals("1-2 / \u5171 5")
            onNodeWithTag("memory-instance-page-next").assertIsNotEnabled()
        }

    @Test
    fun `remote tree queries unknown children and pages roots and children independently`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var state by mutableStateOf(dominatorState())
            val roots = mutableListOf<Int>()
            val children = mutableListOf<Pair<Long, Int>>()
            val selected = mutableListOf<Long>()
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(
                        state,
                        MemoryProfilerActions(
                            onLoadDominatorPage = { roots += it },
                            onLoadDominatorChildren = { id, offset -> children += id to offset },
                            onSelectInstance = { selected += it },
                        ),
                    )
                }
            }

            onNodeWithTag("memory-dominator-page-range").assertTextEquals("1-2 of 5")
            onNodeWithTag("memory-dominator-page-next").performClick()
            assertEquals(listOf(2), roots)
            onNodeWithTag("memory-dominator-expand-42").performClick()
            assertEquals(listOf(42L to 0), children)
            onNodeWithTag("memory-dominator-children-42-loading").assertExists()

            runOnIdle {
                state =
                    state.copy(
                        dominatorChildren = mapOf(42L to listOf(dominator(44, 42), dominator(45, 42))),
                        dominatorChildTotals = mapOf(42L to 5, 44L to 0, 45L to 0),
                        dominatorChildOffsets = mapOf(42L to 0),
                    )
            }
            onNodeWithTag("memory-dominator-children-42-range").assertTextEquals("1-2 of 5")
            onNodeWithTag("memory-dominator-expand-44").assertDoesNotExist()
            onNodeWithTag("memory-dominator-tree-row-44").performClick()
            assertEquals(listOf(44L), selected)
            onNodeWithTag("memory-dominator-children-42-next").performClick()
            assertEquals(listOf(42L to 0, 42L to 2), children)

            runOnIdle {
                state =
                    state.copy(
                        dominatorChildren = mapOf(42L to listOf(dominator(46, 42))),
                        dominatorChildOffsets = mapOf(42L to 4),
                    )
            }
            onNodeWithTag("memory-dominator-tree-row-44").assertDoesNotExist()
            onNodeWithTag("memory-dominator-tree-row-46").assertExists()
            onNodeWithTag("memory-dominator-children-42-range").assertTextEquals("5-5 of 5")
            onNodeWithTag("memory-dominator-children-42-next").assertIsNotEnabled()
            onNodeWithTag("memory-dominator-children-42-previous").performClick()
            assertEquals(listOf(42L to 0, 42L to 2, 42L to 2), children)

            onNodeWithTag("memory-dominator-expand-42").performClick()
            onNodeWithTag("memory-dominator-tree-row-46").assertDoesNotExist()
            onNodeWithTag("memory-dominator-expand-42").performClick()
            onNodeWithTag("memory-dominator-tree-row-46").assertExists()
            assertEquals(3, children.size)
        }

    @Test
    fun `an explicit zero proves a remote leaf and switching snapshots resets expansion`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var state by mutableStateOf(dominatorState())
            val requests = mutableListOf<Pair<Long, Int>>()
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(
                        state,
                        MemoryProfilerActions(onLoadDominatorChildren = { id, offset -> requests += id to offset }),
                    )
                }
            }
            onNodeWithTag("memory-dominator-expand-42").performClick()
            runOnIdle {
                state =
                    state.copy(dominatorChildren = mapOf(42L to emptyList()), dominatorChildTotals = mapOf(42L to 0))
            }
            onNodeWithTag("memory-dominator-expand-42").assertDoesNotExist()
            onNodeWithTag("memory-dominator-children-42-loading").assertDoesNotExist()
            assertEquals(listOf(42L to 0), requests)

            runOnIdle {
                state =
                    state.copy(
                        activeSnapshotId = "next",
                        dominatorChildren = emptyMap(),
                        dominatorChildTotals = emptyMap(),
                    )
            }
            onNodeWithTag("memory-dominator-expand-42").assertIsEnabled()
            onNodeWithTag("memory-dominator-children-42-loading").assertDoesNotExist()
            onNodeWithTag("memory-dominator-expand-42").performClick()
            assertEquals(listOf(42L to 0, 42L to 0), requests)
        }

    @Test
    fun `failed child queries show unavailable in both languages without pretending the parent is a leaf`() {
        UiLanguage.entries.forEach { language ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                var state by mutableStateOf(dominatorState())
                val requests = mutableListOf<Pair<Long, Int>>()
                setContent {
                    ViewerTheme(darkTheme = false) {
                        MemoryProfilerScreen(
                            state,
                            MemoryProfilerActions(onLoadDominatorChildren = { id, offset -> requests += id to offset }),
                            language,
                        )
                    }
                }

                onNodeWithTag("memory-dominator-expand-42").performClick()
                onNodeWithTag("memory-dominator-children-42-loading").assertExists()
                runOnIdle { state = state.copy(failedDominatorChildren = setOf(42L)) }
                onNodeWithTag("memory-dominator-children-42-loading").assertDoesNotExist()
                onNodeWithTag("memory-dominator-children-42-unavailable").assertTextEquals(
                    if (language == UiLanguage.SIMPLIFIED_CHINESE) "\u4e0d\u53ef\u7528" else "Unavailable",
                )
                onNodeWithTag("memory-dominator-expand-42").assertIsEnabled()

                onNodeWithTag("memory-dominator-expand-42").performClick()
                onNodeWithTag("memory-dominator-expand-42").performClick()
                assertEquals(listOf(42L to 0, 42L to 0), requests)
                runOnIdle {
                    state =
                        state.copy(
                            failedDominatorChildren = emptySet(),
                            dominatorChildren = mapOf(42L to listOf(dominator(44, 42))),
                            dominatorChildTotals = mapOf(42L to 1, 44L to 0),
                        )
                }
                onNodeWithTag("memory-dominator-children-42-unavailable").assertDoesNotExist()
                onNodeWithTag("memory-dominator-tree-row-44").assertExists()
            }
        }
    }

    @Test
    fun `a failed continuation keeps loaded children and exposes failure instead of stale page controls`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var state by mutableStateOf(
                dominatorState().copy(
                    dominatorChildren = mapOf(42L to listOf(dominator(44, 42))),
                    dominatorChildTotals = mapOf(42L to 5, 44L to 0),
                ),
            )
            val requests = mutableListOf<Pair<Long, Int>>()
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(
                        state,
                        MemoryProfilerActions(onLoadDominatorChildren = { id, offset -> requests += id to offset }),
                    )
                }
            }
            onNodeWithTag("memory-dominator-expand-42").performClick()
            onNodeWithTag("memory-dominator-children-42-next").performClick()
            runOnIdle { state = state.copy(failedDominatorChildren = setOf(42L)) }

            onNodeWithTag("memory-dominator-tree-row-44").assertExists()
            onNodeWithTag("memory-dominator-children-42-unavailable").assertTextEquals("Unavailable")
            onNodeWithTag("memory-dominator-children-42-loading").assertDoesNotExist()
            onNodeWithTag("memory-dominator-children-42-next").assertDoesNotExist()
            onNodeWithTag("memory-dominator-expand-42").performClick()
            onNodeWithTag("memory-dominator-expand-42").performClick()
            assertEquals(listOf(42L to 1, 42L to 0), requests)
        }

    @Test
    fun `legacy dominators keep flat local expansion and selection without remote paging`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val selected = mutableListOf<Long>()
            val requested = mutableListOf<Pair<Long, Int>>()
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(
                        dominatorState().copy(
                            remoteDominators = false,
                            dominatorRows = listOf(dominator(42), dominator(44, 42).copy(depth = 1)),
                        ),
                        MemoryProfilerActions(
                            onSelectInstance = { selected += it },
                            onLoadDominatorChildren = { id, offset -> requested += id to offset },
                        ),
                    )
                }
            }
            onNodeWithTag("memory-dominator-page").assertDoesNotExist()
            onNodeWithTag("memory-dominator-tree-row-44").assertDoesNotExist()
            onNode(hasText("\u25b8") and hasAnyAncestor(hasTestTag("memory-dominator-tree-row-42"))).performClick()
            onNodeWithTag("memory-dominator-tree-row-44").performClick()
            assertEquals(listOf(44L), selected)
            assertEquals(emptyList(), requested)
        }

    private fun instanceState(): MemoryProfilerState =
        MemoryProfilerState(
            activeSnapshotId = "snapshot",
            selectedClassName = "sample.Object",
            selectedClassInstances =
                List(2) { MemoryInstanceRow(42L + it, it, 16, 32, 1, true, className = "sample.Object") },
            instanceTotalCount = 5,
            instancePageSize = 2,
        )

    private fun dominatorState(): MemoryProfilerState =
        MemoryProfilerState(
            activeSnapshotId = "snapshot",
            viewMode = MemoryProfilerViewMode.Dominators,
            remoteDominators = true,
            dominatorRows = listOf(dominator(42), dominator(43)),
            dominatorTotalCount = 5,
            dominatorPageSize = 2,
        )

    private fun dominator(
        id: Long,
        parent: Long? = null,
    ): MemoryDominatorRow = MemoryDominatorRow(id, "sample.Object$id", 16, 1000 - id, 0, parent)
}
