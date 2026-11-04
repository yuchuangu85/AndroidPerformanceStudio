package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.back
import com.androidperformancestudio.memory.presentation.generated.resources.copy_object_id
import com.androidperformancestudio.memory.presentation.generated.resources.fields
import com.androidperformancestudio.memory.presentation.generated.resources.forward
import com.androidperformancestudio.memory.presentation.generated.resources.instance_details
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_go_to_instance
import com.androidperformancestudio.memory.presentation.generated.resources.next_array_page
import com.androidperformancestudio.memory.presentation.generated.resources.pin_object
import com.androidperformancestudio.memory.presentation.generated.resources.previous_array_page
import com.androidperformancestudio.memory.presentation.generated.resources.references
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryObjectInspectorLayoutTest {
    @Test
    fun `title close and dense table replace identity facts pin and history strips`() =
        runLayoutCases { language, dark ->
            var cleared = 0
            var state by mutableStateOf(
                fixture().copy(
                    pinnedInstanceIds = setOf(4L, 142L),
                    instanceHistory = listOf(4L, 42L, 142L),
                    instanceHistoryIndex = 1,
                ),
            )
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        state,
                        MemoryProfilerActions(
                            onClearInstanceSelection = {
                                cleared++
                                state = state.copy(selectedInstanceDetail = null)
                            },
                        ),
                        language,
                        Modifier.fillMaxSize(),
                    )
                }
            }

            onNodeWithTag("memory-inspector-title").assertHeightIsEqualTo(28.dp)
            onNodeWithTag("memory-inspector-title-text", useUnmergedTree = true)
                .assertTextEquals(
                    localizedStringResource(Res.string.instance_details, language) + " - Target@42 (0x2a)",
                )
            onNodeWithTag("memory-inspector-close").assertIsDisplayed()
            onNodeWithTag("memory-instance-detail-tabs").assertHeightIsEqualTo(28.dp)
            onNodeWithTag("memory-inspector-columns").assertHeightIsEqualTo(24.dp)
            listOf(
                "memory-inspector-identity",
                "memory-inspector-facts",
                "memory-inspector-facts-tooltip",
                "memory-inspector-facts-popover",
            ).forEach { onNodeWithTag(it, useUnmergedTree = true).assertDoesNotExist() }
            listOf(Res.string.back, Res.string.forward, Res.string.copy_object_id).forEach {
                onNodeWithContentDescription(localizedStringResource(it, language)).assertDoesNotExist()
            }
            onNodeWithText(localizedStringResource(Res.string.pin_object, language)).assertDoesNotExist()
            onNodeWithText("ID 0x2a", useUnmergedTree = true).assertDoesNotExist()

            val title = onNodeWithTag("memory-inspector-title").getUnclippedBoundsInRoot()
            val tabs = onNodeWithTag("memory-instance-detail-tabs").getUnclippedBoundsInRoot()
            val columns = onNodeWithTag("memory-inspector-columns").getUnclippedBoundsInRoot()
            assertTrue(abs(tabs.top.value - title.bottom.value) <= 1f)
            assertTrue(abs(columns.top.value - tabs.bottom.value) <= 1f)
            val close = onNodeWithTag("memory-inspector-close").fetchSemanticsNode().boundsInRoot
            val pane = onNodeWithTag("memory-object-inspector").fetchSemanticsNode().boundsInRoot
            assertTrue(close.left >= pane.left && close.right <= pane.right)

            onNodeWithTag("memory-inspector-close").performClick()
            runOnIdle { assertEquals(1, cleared) }
            onNodeWithTag("memory-object-inspector").assertDoesNotExist()
        }

    @Test
    fun `tabs exist only for fields nonempty arrays or inbound references`() =
        runLayoutCases { language, dark ->
            var state by mutableStateOf(fixture(detail().copy(references = emptyList())))
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(state, MemoryProfilerActions(), language, Modifier.fillMaxSize())
                }
            }
            inspectorTab(language, references = false).assertIsSelected()
            inspectorTab(language, references = true).assertDoesNotExist()
            onNodeWithTag("memory-inspector-reference-root").assertDoesNotExist()
            onNodeWithTag("memory-show-nearest-gc-root-only").assertDoesNotExist()

            runOnIdle { state = fixture(detail().copy(fields = emptyList())) }
            inspectorTab(language, references = false).assertDoesNotExist()
            inspectorTab(language, references = true).assertIsSelected()
            onNodeWithTag("memory-inspector-reference-root").assertIsDisplayed()
            onNodeWithTag("memory-inspector-row-0").assertIsDisplayed()

            runOnIdle {
                state =
                    fixture(
                        detail().copy(isArray = true, elementCount = 3, fields = emptyList(), references = emptyList()),
                    )
            }
            inspectorTab(language, references = false).assertIsSelected()
            inspectorTab(language, references = true).assertDoesNotExist()
            onNodeWithTag("memory-object-inspector").assertIsDisplayed()

            runOnIdle {
                state =
                    fixture(
                        detail().copy(isArray = true, elementCount = 0, fields = emptyList(), references = emptyList()),
                    )
            }
            onNodeWithTag("memory-object-inspector").assertDoesNotExist()
            runOnIdle { state = fixture(detail().copy(fields = emptyList(), references = emptyList())) }
            onNodeWithTag("memory-object-inspector").assertDoesNotExist()
        }

    @Test
    fun `empty details and cleared selection also hide the parent right pane`() =
        runLayoutCases { language, dark ->
            var state by mutableStateOf(
                fixture().copy(
                    selectedClassName = "sample.Target",
                    selectedClassInstances = listOf(MemoryInstanceRow(42, 1, 32, 48, 1, true)),
                ),
            )
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryClassesPane(state, MemoryProfilerActions(), language, Modifier.fillMaxSize())
                }
            }
            onNodeWithTag("memory-inspector-card").assertExists()
            runOnIdle {
                state =
                    state.copy(selectedInstanceDetail = detail().copy(fields = emptyList(), references = emptyList()))
            }
            onNodeWithTag("memory-object-inspector").assertDoesNotExist()
            onNodeWithTag("memory-inspector-card").assertDoesNotExist()
            runOnIdle { state = state.copy(selectedInstanceDetail = detail().copy(fields = emptyList())) }
            onNodeWithTag("memory-inspector-card").assertExists()
            inspectorTab(language, references = true).assertIsSelected()
            runOnIdle { state = state.copy(selectedInstanceDetail = null) }
            onNodeWithTag("memory-inspector-card").assertDoesNotExist()
        }

    @Test
    fun `headers and every metric cell align for fields and expanded reference root`() =
        runLayoutCases { language, dark ->
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(fixture(), MemoryProfilerActions(), language, Modifier.fillMaxSize())
                }
            }
            assertAlignedRows(listOf("memory-inspector-row-0", "memory-inspector-row-1"))
            inspectorTab(language, references = true).performClick()
            onNodeWithTag("memory-show-nearest-gc-root-only").assertIsOff()
            assertAlignedRows(
                listOf(
                    "memory-inspector-reference-root",
                    "memory-inspector-row-0",
                    "memory-inspector-row-1",
                    "memory-inspector-row-2",
                ),
            )
            val root = onNodeWithTag("memory-inspector-reference-root").getUnclippedBoundsInRoot()
            val firstTop =
                (0..2).minOf {
                    onNodeWithTag("memory-inspector-row-$it").getUnclippedBoundsInRoot().top
                }
            assertEquals(root.bottom, firstTop)
            val rootLabel =
                onNodeWithTag("memory-inspector-reference-root-name", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
            val childLabel =
                onNodeWithTag("memory-inspector-row-0-name", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
            assertTrue(childLabel.left > rootLabel.left, "Inbound references must be children of the selected instance")
        }

    @Test
    fun `label separator resizes the next column and header clicks reorder original row tags`() =
        runLayoutCases { language, dark ->
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(fixture(), MemoryProfilerActions(), language, Modifier.fillMaxSize())
                }
            }
            val depth = onNodeWithTag("memory-inspector-header-DEPTH", useUnmergedTree = true)
            val before = depth.getUnclippedBoundsInRoot().let { it.right.value - it.left.value }
            onNodeWithTag("memory-inspector-column-resizer-LABEL", useUnmergedTree = true)
                .performScrollTo()
                .performMouseInput {
                    moveTo(center)
                    press()
                    moveBy(Offset(-24f, 0f), delayMillis = 100L)
                    release()
                }
            val after = depth.getUnclippedBoundsInRoot().let { it.right.value - it.left.value }
            assertTrue(abs(after - before) > 1f, "Dragging LABEL's separator must change the following column")
            assertAlignedRows(listOf("memory-inspector-row-0", "memory-inspector-row-1"))

            val label = onNodeWithTag("memory-inspector-header-LABEL", useUnmergedTree = true)
            label.performScrollTo().performClick()
            val firstOrder = rowTop(0) < rowTop(1)
            label.performClick()
            assertTrue(firstOrder != (rowTop(0) < rowTop(1)), "Clicking the same header must reverse the sort")
            onNodeWithTag("memory-inspector-row-0-name", useUnmergedTree = true).assertTextEquals("child = {Child}")
            onNodeWithTag("memory-inspector-row-1-name", useUnmergedTree = true).assertTextEquals("count = 7")
        }

    @Test
    fun `ordinary row clicks expand locally and only Go to Instance navigates`() =
        runLayoutCases { language, dark ->
            val selected = mutableListOf<Long>()
            val loaded = mutableListOf<Pair<Long, Int>>()
            val child = detail().copy(objectId = 142L, className = "sample.Child", fields = listOf(value("leaf", "7")))
            val state =
                fixture().copy(
                    inspectorObjects =
                        mapOf(MemoryInspectorObjectKey(142L) to MemoryInspectorObjectState.Loaded(child)),
                )
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        state,
                        MemoryProfilerActions(
                            onSelectInstance = { selected += it },
                            onLoadInspectorObject = { id, start -> loaded += id to start },
                        ),
                        language,
                        Modifier.fillMaxSize(),
                    )
                }
            }
            onNodeWithTag("memory-inspector-row-0").performClick()
            onNodeWithTag("memory-inspector-row-0-child-0-name", useUnmergedTree = true).assertTextEquals("leaf = 7")
            onNodeWithTag("memory-inspector-row-0").performClick()
            onNodeWithTag("memory-inspector-row-0-child-0").assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-1").performClick()
            runOnIdle { assertTrue(selected.isEmpty(), "Expansion and collapse must not navigate") }

            onNodeWithTag("memory-inspector-row-0").performMouseInput { click(button = MouseButton.Secondary) }
            runOnIdle { assertTrue(selected.isEmpty(), "Opening the context menu must not navigate") }
            onNodeWithText(localizedStringResource(Res.string.memory_inspector_go_to_instance, language)).performClick()
            runOnIdle {
                assertEquals(listOf(142L), selected)
                assertTrue(loaded.isEmpty(), "A cached child must not be loaded again")
                assertEquals(42L, state.selectedInstanceDetail?.objectId)
            }
        }

    @Test
    fun `references root starts expanded and nearest filter has no separate GC root path`() =
        runLayoutCases { language, dark ->
            val selected = mutableListOf<Long>()
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        fixture(),
                        MemoryProfilerActions(onSelectInstance = { selected += it }),
                        language,
                        Modifier.fillMaxSize(),
                    )
                }
            }
            onNodeWithTag("memory-show-nearest-gc-root-only").assertDoesNotExist()
            inspectorTab(language, references = true).performClick()
            onNodeWithTag("memory-show-nearest-gc-root-only").assertIsOff()
            onNodeWithTag("memory-inspector-reference-root").assertIsDisplayed()
            (0..2).forEach { onNodeWithTag("memory-inspector-row-$it").assertExists() }
            onNodeWithTag("memory-inspector-reference-root").performClick()
            onNodeWithTag("memory-inspector-row-0").assertDoesNotExist()
            onNodeWithTag("memory-inspector-reference-root").performClick()
            onNodeWithTag("memory-inspector-row-0").assertExists()
            onNodeWithTag("memory-show-nearest-gc-root-only").performClick().assertIsOn()
            onNodeWithTag("memory-inspector-row-0").assertExists()
            onNodeWithTag("memory-inspector-row-1").assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-2").assertDoesNotExist()
            onNodeWithTag("memory-inspector-root-title").assertDoesNotExist()
            onNodeWithTag("memory-inspector-root-row-0").assertDoesNotExist()
            onNodeWithTag("memory-inspector-reference-root-name", useUnmergedTree = true)
                .assertTextEquals("Target@42 (0x2a)")
            onNodeWithTag("memory-inspector-row-0").performClick()
            runOnIdle { assertTrue(selected.isEmpty(), "Reference row expansion must not navigate") }
            inspectorTab(language, references = false).performClick()
            onNodeWithTag("memory-show-nearest-gc-root-only").assertDoesNotExist()
            runOnIdle { assertTrue(selected.isEmpty()) }
        }

    @Test
    fun `snapshot reset selects Fields and arrays append the next batch inline`() =
        runLayoutCases { language, dark ->
            var state by mutableStateOf(fixture())
            val ranges = mutableListOf<Pair<Long, Int>>()
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        state,
                        MemoryProfilerActions(onLoadInspectorObject = { id, start -> ranges += id to start }),
                        language,
                        Modifier.fillMaxSize(),
                    )
                }
            }
            inspectorTab(language, references = true).performClick()
            onNodeWithTag("memory-show-nearest-gc-root-only").performClick()
            runOnIdle {
                state =
                    fixture(
                        detail().copy(
                            isArray = true,
                            elementCount = 150,
                            fields = List(100) { value("[$it]", "0") },
                        ),
                    ).copy(activeSnapshotId = "array-snapshot")
            }
            inspectorTab(language, references = false).assertIsSelected()
            onNodeWithTag("memory-show-nearest-gc-root-only").assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-0").assertHeightIsEqualTo(20.dp)
            onNodeWithContentDescription(localizedStringResource(Res.string.previous_array_page, language))
                .assertDoesNotExist()
            onNodeWithContentDescription(localizedStringResource(Res.string.next_array_page, language))
                .assertDoesNotExist()
            onNodeWithTag("memory-inspector-content")
                .performScrollToNode(hasTestTag("memory-inspector-row-more"))
            onNodeWithTag("memory-inspector-row-more").performClick()
            runOnIdle { assertEquals(listOf(42L to 100), ranges) }
            inspectorTab(language, references = true).performClick()
            onNodeWithTag("memory-show-nearest-gc-root-only").assertIsOff()
        }

    private fun DesktopComposeUiTest.assertAlignedRows(tags: List<String>) {
        val columns = listOf("LABEL", "DEPTH", "SHALLOW_SIZE", "RETAINED_SIZE")
        columns.forEach { onNodeWithTag("memory-inspector-header-$it", useUnmergedTree = true).assertExists() }
        tags.forEach { tag ->
            onNodeWithTag(tag).assertHeightIsEqualTo(20.dp)
            columns.drop(1).forEach { column ->
                val header =
                    onNodeWithTag("memory-inspector-header-$column", useUnmergedTree = true)
                        .getUnclippedBoundsInRoot()
                val cell = onNodeWithTag("$tag-$column", useUnmergedTree = true).getUnclippedBoundsInRoot()
                val headerCenter = (header.left.value + header.right.value) / 2f
                val cellCenter = (cell.left.value + cell.right.value) / 2f
                assertTrue(abs(headerCenter - cellCenter) <= 1f, "$tag $column must align with its header")
                assertTrue(cell.right > cell.left && cell.left >= header.left && cell.right <= header.right)
            }
        }
        val rows = tags.map { onNodeWithTag(it).getUnclippedBoundsInRoot() }.sortedBy { it.top.value }
        rows.zipWithNext().forEach { (firstBounds, secondBounds) ->
            assertEquals(firstBounds.bottom, secondBounds.top)
        }
    }

    private fun DesktopComposeUiTest.rowTop(index: Int): Float =
        onNodeWithTag("memory-inspector-row-$index").getUnclippedBoundsInRoot().top.value

    private fun DesktopComposeUiTest.inspectorTab(
        language: UiLanguage,
        references: Boolean,
    ): SemanticsNodeInteraction =
        onNode(
            hasText(localizedStringResource(if (references) Res.string.references else Res.string.fields, language)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        )

    private fun runLayoutCases(block: DesktopComposeUiTest.(UiLanguage, Boolean) -> Unit) {
        listOf(260, 600).forEach { width ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = width, height = 520) { block(language, dark) }
                }
            }
        }
    }

    private fun fixture(detail: MemoryInstanceDetail = detail()): MemoryProfilerState =
        MemoryProfilerState(
            activeSnapshotId = "layout-snapshot",
            snapshotSummary = HeapSnapshotSummary("layout-snapshot", capabilities = setOf(HeapCapability.GC_ROOTS)),
            selectedInstanceDetail = detail,
        )

    private fun detail(): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = 42L,
            className = "sample.Target",
            shallowSize = 32L,
            retainedSize = 48L,
            nativeSize = 4096L,
            depth = 1,
            isArray = false,
            elementCount = null,
            valueKind = HeapValueKind.OBJECT,
            fields = listOf(link("child", 142L, "sample.Child", 2), value("count", "7")),
            references =
                listOf(
                    link("sample.Root.target", 4L, "sample.Root", 0),
                    link("equalDepth", 5L, "sample.Owner", 1),
                    link("unknownDepth", 6L, "sample.Owner", null),
                ),
            referenceChain = listOf(ObjectReference("GC Root (JNI_GLOBAL)", 4L, "sample.Root")),
        )

    private fun link(
        name: String,
        id: Long,
        className: String,
        depth: Int?,
    ): MemoryInstanceField =
        MemoryInstanceField(
            name,
            "$className #$id",
            id,
            className,
            depth = depth,
            valueKind = HeapValueKind.OBJECT,
            shallowSize = 32,
            retainedSize = 48,
            childCount = 1,
        )

    private fun value(
        name: String,
        display: String,
    ): MemoryInstanceField = MemoryInstanceField(name, display, null, null, valueKind = HeapValueKind.PRIMITIVE)
}
