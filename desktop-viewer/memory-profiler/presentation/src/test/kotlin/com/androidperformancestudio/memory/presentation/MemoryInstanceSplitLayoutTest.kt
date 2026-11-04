package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.back
import com.androidperformancestudio.memory.presentation.generated.resources.forward
import com.androidperformancestudio.memory.presentation.generated.resources.instance_list
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryInstanceSplitLayoutTest {
    @Test
    fun `active class list puts full width classes above both panes under one shared instance heading`() {
        listOf(1280, 900).forEach { width ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = width, height = 900) {
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                MemoryProfilerScreen(fixture(), MemoryProfilerActions(), language)
                            }
                        }

                        assertSplitGeometry(language)
                        val classes = bounds("memory-classes-card")
                        val instances = bounds(INSTANCE_CARD)
                        val inspector = bounds(INSPECTOR)
                        assertNear(classes.left, instances.left)
                        assertNear(classes.right, instances.right)
                        assertTrue(classes.bottom < instances.top)
                        assertTrue(inspector.top > classes.bottom)
                        assertTrue(classes.width >= width - 16f, "Classes must not share an outer inspector rail")
                        assertTrue(instances.right <= onRoot().fetchSemanticsNode().boundsInRoot.right)
                        assertNear(0.62f, leftFraction(), tolerance = 0.01f)
                        onNodeWithTag("memory-inspector-title-text")
                            .assertIsDisplayed()
                    }
                }
            }
        }
    }

    @Test
    fun `mouse dragging moves both pane edges and clamps at table and inspector minimum widths`() {
        listOf(1280, 900).forEach { width ->
            runDesktopComposeUiTest(width = width, height = 900) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        MemoryProfilerScreen(fixture(), MemoryProfilerActions(), UiLanguage.ENGLISH)
                    }
                }
                val classes = bounds("memory-classes-card")
                val initialLeft = bounds(TABLE_PANE)
                val initialRight = bounds(INSPECTOR)

                dragDivider(80f)
                assertTrue(bounds(TABLE_PANE).width > initialLeft.width + 40f)
                assertTrue(bounds(INSPECTOR).width < initialRight.width - 40f)
                assertSplitGeometry(UiLanguage.ENGLISH)

                val widerLeft = bounds(TABLE_PANE).width
                dragDivider(-160f)
                assertTrue(bounds(TABLE_PANE).width < widerLeft - 80f)
                assertSplitGeometry(UiLanguage.ENGLISH)

                dragDivider(bounds(CONTENT).right - bounds(RESIZER).center.x - 1f)
                onNodeWithTag(INSPECTOR).assertWidthIsEqualTo(260.dp)
                onNodeWithTag(TABLE_PANE).assertWidthIsAtLeast(280.dp)
                assertSplitGeometry(UiLanguage.ENGLISH)

                dragDivider(bounds(CONTENT).left - bounds(RESIZER).center.x + 1f)
                onNodeWithTag(TABLE_PANE).assertWidthIsEqualTo(280.dp)
                onNodeWithTag(INSPECTOR).assertWidthIsAtLeast(260.dp)
                assertSplitGeometry(UiLanguage.ENGLISH)
                assertTableScrollsWithinPane()
                assertEquals(classes, bounds("memory-classes-card"))
            }
        }
    }

    @Test
    fun `user split survives recomposition and follows viewport resizing`() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            var viewportWidth by mutableStateOf(1280.dp)
            var state by mutableStateOf(fixture())
            setContent {
                ViewerTheme(darkTheme = false) {
                    Box(Modifier.width(viewportWidth).fillMaxHeight()) {
                        MemoryClassesPane(
                            MemoryProfilerPresenter.present(state),
                            MemoryProfilerActions(),
                            UiLanguage.ENGLISH,
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            assertSplitGeometry(UiLanguage.ENGLISH)
            assertNear(0.62f, leftFraction(), tolerance = 0.01f)
            dragDivider(-100f)
            val fraction = leftFraction()
            assertTrue(fraction < 0.60f)
            val selectedWidth = bounds(TABLE_PANE).width

            runOnIdle { state = state.copy(selectedInstanceDetail = detail(43L), matchCase = true) }
            assertNear(selectedWidth, bounds(TABLE_PANE).width)
            runOnIdle { viewportWidth = 900.dp }
            assertSplitGeometry(UiLanguage.ENGLISH)
            assertNear(fraction, leftFraction(), tolerance = 0.005f)
            assertTrue(bounds(TABLE_PANE).width < selectedWidth)
            onNodeWithTag(TABLE_PANE).assertWidthIsAtLeast(280.dp)
            onNodeWithTag(INSPECTOR).assertWidthIsAtLeast(260.dp)

            runOnIdle { viewportWidth = 460.dp }
            assertSplitGeometry(UiLanguage.ENGLISH)
            runOnIdle { viewportWidth = 1280.dp }
            assertSplitGeometry(UiLanguage.ENGLISH)
            assertNear(fraction, leftFraction(), tolerance = 0.005f)
            assertNear(selectedWidth, bounds(TABLE_PANE).width)
        }

    @Test
    fun `infeasible pane minimums clamp within narrow content after dragging in both directions`() {
        listOf(460, 320).forEach { width ->
            runDesktopComposeUiTest(width = width, height = 900) {
                setContent {
                    ViewerTheme(darkTheme = true) {
                        MemoryClassesPane(
                            MemoryProfilerPresenter.present(fixture()),
                            MemoryProfilerActions(),
                            UiLanguage.SIMPLIFIED_CHINESE,
                            Modifier.fillMaxSize(),
                        )
                    }
                }
                assertSplitGeometry(UiLanguage.SIMPLIFIED_CHINESE)
                val initial = bounds(TABLE_PANE).width
                dragDivider(bounds(CONTENT).right - bounds(RESIZER).center.x - 1f)
                assertSplitGeometry(UiLanguage.SIMPLIFIED_CHINESE)
                val maximum = bounds(TABLE_PANE).width
                assertTrue(maximum >= initial)
                dragDivider(bounds(CONTENT).left - bounds(RESIZER).center.x + 1f)
                assertSplitGeometry(UiLanguage.SIMPLIFIED_CHINESE)
                assertTrue(bounds(TABLE_PANE).width <= maximum)
                assertTrue(bounds(TABLE_PANE).width + bounds(INSPECTOR).width < 540f)
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `active and legacy selection inline fields history and pins work across divider drags`() {
        listOf(false, true).forEach { legacy ->
            listOf(1280, 900).forEach { width ->
                runDesktopComposeUiTest(width = width, height = 900) {
                    val language = if (width == 900) UiLanguage.SIMPLIFIED_CHINESE else UiLanguage.ENGLISH
                    var state by mutableStateOf(
                        fixture().copy(
                            selectedInstanceDetail = null,
                            instanceHistory = emptyList(),
                            instanceHistoryIndex = -1,
                        ),
                    )
                    val selected = mutableListOf<Long>()
                    val expanded = mutableListOf<Pair<Long, Int>>()
                    var backwards = 0
                    var forwards = 0
                    val actions =
                        MemoryProfilerActions(
                            onLoadInspectorObject = { id, start -> expanded += id to start },
                            onSelectInstance = { id ->
                                selected += id
                                val history = state.instanceHistory.take(state.instanceHistoryIndex + 1) + id
                                state =
                                    state.copy(
                                        selectedInstanceDetail = detail(id),
                                        instanceHistory = history,
                                        instanceHistoryIndex = history.lastIndex,
                                    )
                            },
                            onNavigateInstanceBack = {
                                backwards++
                                val index = state.instanceHistoryIndex - 1
                                state =
                                    state.copy(
                                        selectedInstanceDetail = detail(state.instanceHistory[index]),
                                        instanceHistoryIndex = index,
                                    )
                            },
                            onNavigateInstanceForward = {
                                forwards++
                                val index = state.instanceHistoryIndex + 1
                                state =
                                    state.copy(
                                        selectedInstanceDetail = detail(state.instanceHistory[index]),
                                        instanceHistoryIndex = index,
                                    )
                            },
                        )
                    setContent {
                        ViewerTheme(darkTheme = legacy) {
                            if (legacy) {
                                MemoryProfilerClassListPane(MemoryProfilerPresenter.present(state), actions, language)
                            } else {
                                MemoryProfilerScreen(state, actions, language)
                            }
                        }
                    }
                    onNodeWithTag(INSPECTOR).assertDoesNotExist()
                    selectVisibleRow(42L)
                    assertSplitGeometry(language, sharedCard = !legacy)
                    dragDivider(-70f)
                    val resizedWidth = bounds(TABLE_PANE).width
                    onNodeWithTag("memory-inspector-title-text").assertIsDisplayed()
                    selectVisibleRow(43L)
                    onNodeWithTag("memory-inspector-title-text").assertIsDisplayed()
                    assertNear(resizedWidth, bounds(TABLE_PANE).width)

                    dragDivider(70f)
                    val navigatedWidth = bounds(TABLE_PANE).width
                    if (!legacy) onNodeWithTag(INSPECTOR).performScrollTo()
                    onNodeWithTag("memory-inspector-row-0").performClick()
                    onNodeWithTag("memory-instance-row-43").assertIsSelected()
                    onNodeWithContentDescription(localizedStringResource(Res.string.back, language))
                        .assertDoesNotExist()
                    onNodeWithContentDescription(localizedStringResource(Res.string.forward, language))
                        .assertDoesNotExist()
                    onNodeWithTag("memory-inspector-identity").assertDoesNotExist()
                    assertNear(navigatedWidth, bounds(TABLE_PANE).width)
                    assertSplitGeometry(language, sharedCard = !legacy)
                    assertEquals(listOf(42L, 43L), selected)
                    assertEquals(listOf(42L to 0), expanded)
                    assertEquals(0, backwards)
                    assertEquals(0, forwards)
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertSplitGeometry(
        language: UiLanguage,
        sharedCard: Boolean = true,
    ) {
        val inContent = hasAnyAncestor(hasTestTag(CONTENT))
        onNodeWithTag(CONTENT).assertIsDisplayed()
        if (sharedCard) {
            val inCard = hasAnyAncestor(hasTestTag(INSTANCE_CARD))
            listOf(CONTENT, TABLE_PANE, RESIZER, INSPECTOR).forEach { tag ->
                onNode(hasTestTag(tag) and inCard).assertExists()
            }
        }
        listOf(TABLE_PANE, RESIZER, INSPECTOR).forEach { tag ->
            onNode(hasTestTag(tag) and inContent).assertIsDisplayed()
        }
        val titleText = hasText(localizedStringResource(Res.string.instance_list, language), substring = true)
        val titleMatcher =
            if (sharedCard) titleText and hasAnyAncestor(hasTestTag(INSTANCE_CARD)) else titleText
        val title =
            onNode(titleMatcher, useUnmergedTree = true).assertIsDisplayed().layoutBounds()
        val content = bounds(CONTENT)
        val table = bounds(TABLE_PANE)
        val divider = bounds(RESIZER)
        val inspector = bounds(INSPECTOR)
        assertTrue(title.bottom <= content.top)
        if (sharedCard) {
            val card = bounds(INSTANCE_CARD)
            assertTrue(content.top > card.top)
            assertNear(card.left, content.left)
            assertNear(card.right, content.right)
            assertNear(card.bottom, content.bottom)
        }
        assertNear(content.left, table.left)
        assertNear(table.right, divider.left)
        assertNear(divider.right, inspector.left)
        assertNear(content.right, inspector.right)
        listOf(table, divider, inspector).forEach { pane ->
            assertNear(content.top, pane.top)
            assertNear(content.bottom, pane.bottom)
            assertTrue(pane.width > 0f)
        }
        assertNear(content.width, table.width + divider.width + inspector.width)
        onNodeWithTag(RESIZER).assertWidthIsEqualTo(7.dp)
        // The 650dp scrollable table footprint reserves 10dp for the scrollbar.
        val tableNode = onNodeWithTag("memory-instance-table").fetchSemanticsNode()
        val horizontalRange = tableNode.config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(tableNode.size.width + horizontalRange.maxValue() >= 640f)
    }

    private fun DesktopComposeUiTest.assertTableScrollsWithinPane() {
        val tablePane = bounds(TABLE_PANE)
        val inspector = bounds(INSPECTOR)
        val scroll =
            onNode(
                hasAnyAncestor(hasTestTag(TABLE_PANE)) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            )
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> assertTrue(scrollBy(120f, 0f)) }
        val after = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue(after > before, "The wide instance table must scroll inside its left viewport")
        assertEquals(tablePane, bounds(TABLE_PANE))
        assertEquals(inspector, bounds(INSPECTOR))
    }

    private fun DesktopComposeUiTest.dragDivider(delta: Float) {
        onNodeWithTag(RESIZER).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(delta, 0f), delayMillis = 100L)
            release()
        }
    }

    private fun DesktopComposeUiTest.selectVisibleRow(objectId: Long) {
        val pane = onNodeWithTag(TABLE_PANE)
        val row = onNodeWithTag("memory-instance-row-$objectId")
        val paneBounds = pane.fetchSemanticsNode().boundsInRoot
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        pane.performMouseInput { click(Offset(16f, rowBounds.center.y - paneBounds.top)) }
        row.assertIsSelected()
    }

    private fun DesktopComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag).layoutBounds()

    private fun SemanticsNodeInteraction.layoutBounds(): Rect {
        val node = fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    private fun DesktopComposeUiTest.leftFraction(): Float {
        val tableWidth = bounds(TABLE_PANE).width
        return tableWidth / (tableWidth + bounds(INSPECTOR).width)
    }

    private fun assertNear(
        expected: Float,
        actual: Float,
        tolerance: Float = 1f,
    ) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected, got $actual (tolerance $tolerance)")
    }

    private fun fixture(): MemoryProfilerState {
        val snapshot =
            HeapSnapshotSummary(
                "instance-split",
                capabilities = setOf(HeapCapability.HEAP_GRAPH, HeapCapability.GC_ROOTS),
            )
        val classes = listOf(ClassStats("sample.Target", instanceCount = 2, shallowSize = 64L, retainedSize = 96L))
        return MemoryProfilerState(
            activeSnapshotId = snapshot.id,
            snapshotSummary = snapshot,
            snapshotSummaries = listOf(snapshot),
            classes = classes,
            heapBaseClasses = classes,
            viewMode = MemoryProfilerViewMode.ClassList,
            selectedClassName = "sample.Target",
            selectedClassifierId = "class:sample.Target",
            selectedClassifierLabel = "sample.Target",
            selectedClassInstances =
                listOf(
                    MemoryInstanceRow(42L, 1, 32L, 48L, 1, true, className = "sample.Target"),
                    MemoryInstanceRow(43L, 2, 32L, 48L, 1, true, className = "sample.Target"),
                ),
            selectedInstanceDetail = detail(42L),
            instanceHistory = listOf(42L),
            instanceHistoryIndex = 0,
            pinnedInstanceIds = setOf(43L),
        )
    }

    private fun detail(objectId: Long): MemoryInstanceDetail {
        val peerId = if (objectId == 42L) 43L else 42L
        return MemoryInstanceDetail(
            objectId = objectId,
            className = "sample.Target",
            shallowSize = 32L,
            retainedSize = 48L,
            depth = 1,
            isArray = false,
            elementCount = null,
            fields = listOf(MemoryInstanceField("peer", "sample.Target #$peerId", peerId, "sample.Target")),
            referenceChain = emptyList(),
        )
    }

    private companion object {
        const val INSTANCE_CARD = "memory-instances-card"
        const val CONTENT = "memory-instance-list-content"
        const val TABLE_PANE = "memory-instance-list-table-pane"
        const val INSPECTOR = "memory-inspector-card"
        const val RESIZER = "memory-instance-list-resizer"
    }
}
