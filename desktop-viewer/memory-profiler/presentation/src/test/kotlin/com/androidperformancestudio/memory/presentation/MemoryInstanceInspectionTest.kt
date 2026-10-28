package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.ObjectReference
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
class MemoryInstanceInspectionTest {
    @Test
    fun `active instance list stays flat and selects the native details root`() = assertEntrySelection(legacy = false)

    @Test
    fun `legacy instance list shares native selection tab reset and conditional details visibility`() {
        assertEntrySelection(legacy = true)
    }

    @Test
    fun `tabs exist only for available fields arrays and inbound references`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            val harness = InspectorHarness(fixture())
            showInspector(harness, language, dark)
            assertTitle(detail(42L), copy)
            assertTabs(copy, fields = true, references = true, fieldsSelected = true)
            onNodeWithTag(REFERENCE_ROOT).assertDoesNotExist()
            assertRowName(ROW, "child42 = {Child}")

            inspectorTab(copy.references).performClick()
            runOnIdle {
                harness.state = harness.state.copy(selectedInstanceDetail = detail(43L).copy(references = emptyList()))
            }
            assertTabs(copy, fields = true, references = false, fieldsSelected = true)
            rootFilter().assertDoesNotExist()
            assertRowName(ROW, "child43 = {Child}")

            val referencesOnly = detail(44L).copy(className = "sample.Owner", fields = emptyList())
            runOnIdle { harness.state = harness.state.copy(selectedInstanceDetail = referencesOnly) }
            assertTitle(referencesOnly, copy)
            assertTabs(copy, fields = false, references = true, fieldsSelected = false)
            rootFilter().assertIsOff()
            assertRowName(REFERENCE_ROOT, "Owner@44 (0x2c)")
            assertRowName(ROW, "target44 in Root@4 (0x4)")
            assertNoStandaloneRootPath()

            val array = arrayDetail(45L, start = 0, count = 1, loaded = 0)
            runOnIdle { harness.state = harness.state.copy(selectedInstanceDetail = array) }
            assertTitle(array, copy)
            assertTabs(copy, fields = true, references = false, fieldsSelected = true)
            onNodeWithTag(REFERENCE_ROOT).assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-empty").assertTextEquals(copy.noArrayValues)
            rootFilter().assertDoesNotExist()

            runOnIdle {
                harness.state = harness.state.copy(selectedInstanceDetail = array.copy(elementCount = 0))
            }
            onNodeWithTag("memory-object-inspector").assertDoesNotExist()
            runOnIdle {
                harness.state =
                    harness.state.copy(
                        selectedInstanceDetail = detail(42L).copy(fields = emptyList(), references = emptyList()),
                    )
            }
            onNodeWithTag("memory-object-inspector").assertDoesNotExist()
        }

    @Test
    fun `references show an expanded root and nearest mode keeps only strictly smaller known source depths`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            val root =
                detail(42L).copy(
                    depth = 2,
                    references =
                        listOf(
                            reference("root", 4L, "sample.Root", depth = 0),
                            reference("nearer", 5L, "sample.Nearer", depth = 1),
                            reference("equal", 6L, "sample.Equal", depth = 2),
                            reference("farther", 7L, "sample.Farther", depth = 3),
                            reference("unknown", 8L, "sample.Unknown", depth = null),
                        ),
                )
            val harness = InspectorHarness(fixture().copy(selectedInstanceDetail = root))
            showInspector(harness, language, dark)
            inspectorTab(copy.references).performClick()
            rootFilter().assertIsOff()
            assertRowName(REFERENCE_ROOT, "Target@42 (0x2a)")
            assertExpanded(REFERENCE_ROOT, copy, expanded = true)
            assertRowName(ROW, "root in Root@4 (0x4)")
            assertRowName("memory-inspector-row-1", "nearer in Nearer@5 (0x5)")
            assertRowName("memory-inspector-row-2", "equal in Equal@6 (0x6)")
            assertRowName("memory-inspector-row-3", "farther in Farther@7 (0x7)")
            assertRowName("memory-inspector-row-4", "unknown in Unknown@8 (0x8)")
            assertNoStandaloneRootPath()

            visibleTag(REFERENCE_ROOT).performClick()
            assertExpanded(REFERENCE_ROOT, copy, expanded = false)
            onNodeWithTag(ROW).assertDoesNotExist()
            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            assertExpanded(REFERENCE_ROOT, copy, expanded = true)
            assertRowName(ROW, "root in Root@4 (0x4)")
            assertRowName("memory-inspector-row-1", "nearer in Nearer@5 (0x5)")
            listOf(2, 3, 4).forEach { onNodeWithTag("memory-inspector-row-$it").assertDoesNotExist() }
            assertNoStandaloneRootPath()

            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOff()
            assertRowName("memory-inspector-row-4", "unknown in Unknown@8 (0x8)")
            inspectorTab(copy.fields).performClick()
            rootFilter().assertDoesNotExist()
            onNodeWithTag(REFERENCE_ROOT).assertDoesNotExist()
            assertRowName(ROW, "child42 = {Child}")
            runOnIdle { harness.assertNoNavigation() }
        }

    @Test
    fun `nearest mode automatically expands and selects only the first decreasing depth path within References`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            val root =
                detail(42L).copy(
                    depth = 3,
                    references =
                        listOf(
                            reference("target", 242L, "sample.Owner", depth = 2, children = 3),
                            reference("otherTarget", 243L, "sample.OtherOwner", depth = 2, children = 1),
                        ),
                )
            val owner =
                detail(242L).copy(
                    className = "sample.Owner",
                    depth = 2,
                    fields = emptyList(),
                    references =
                        listOf(
                            reference("owner", 4L, "sample.Root", depth = 0),
                            reference("peer", 5L, "sample.Peer", depth = 2),
                            reference("unknown", 6L, "sample.Unknown", depth = null),
                        ),
                )
            val harness = InspectorHarness(fixture().copy(selectedInstanceDetail = root), listOf(owner))
            showInspector(harness, language, dark)
            inspectorTab(copy.references).performClick()
            assertExpanded(REFERENCE_ROOT, copy, expanded = true)
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            runOnIdle { assertTrue(harness.requests.isEmpty()) }

            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            assertExpanded(ROW, copy, expanded = true)
            assertRowName("$ROW-child-0", "owner in Root@4 (0x4)")
            onNodeWithTag("$ROW-child-0").assertIsSelected()
            onNodeWithTag("$ROW-child-1").assertDoesNotExist()
            onNodeWithTag("$ROW-child-2").assertDoesNotExist()
            assertRowName("memory-inspector-row-1", "otherTarget in OtherOwner@243 (0xf3)")
            assertExpanded("memory-inspector-row-1", copy, expanded = false)
            onNodeWithTag("memory-inspector-row-1-child-0").assertDoesNotExist()
            assertTitle(root, copy)
            assertNoStandaloneRootPath()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(242L)), harness.requests)
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `unknown target or source depth is not treated as zero in nearest mode`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            val root =
                detail(42L).copy(
                    depth = null,
                    references =
                        listOf(
                            reference("known", 4L, "sample.Root", depth = 0),
                            reference("unknown", 5L, "sample.Unknown", depth = null),
                        ),
                )
            val harness = InspectorHarness(fixture().copy(selectedInstanceDetail = root))
            showInspector(harness, language, dark)
            inspectorTab(copy.references).performClick()
            assertRowName(ROW, "known in Root@4 (0x4)")
            assertRowName("memory-inspector-row-1", "unknown in Unknown@5 (0x5)")
            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            assertRowName(REFERENCE_ROOT, "Target@42 (0x2a)")
            onNodeWithTag(ROW).assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-1").assertDoesNotExist()
            assertNoStandaloneRootPath()

            runOnIdle { harness.state = harness.state.copy(selectedInstanceDetail = root.copy(depth = 1)) }
            rootFilter().assertIsOn()
            assertRowName(ROW, "known in Root@4 (0x4)")
            onNodeWithTag("memory-inspector-row-1").assertDoesNotExist()
            rootFilterLabel(copy).performClick()
            assertRowName("memory-inspector-row-1", "unknown in Unknown@5 (0x5)")
            runOnIdle { harness.assertNoNavigation() }
        }

    @Test
    fun `snapshot change clears tab filter expansion and read more state even for reused object ids`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            val oldRoot = detail(42L).copy(fields = detail(42L).fields + List(100) { value("old$it", "$it") })
            val oldChild = detail(142L).copy(fields = listOf(value("oldLeaf", "1")), references = emptyList())
            val newRoot = newSnapshotDetail()
            val newChild = oldChild.copy(fields = listOf(value("newLeaf", "2")))
            val childKey = MemoryInspectorObjectKey(142L)
            val harness =
                InspectorHarness(
                    fixture().copy(
                        selectedInstanceDetail = oldRoot,
                        inspectorObjects = mapOf(childKey to MemoryInspectorObjectState.Loaded(oldChild)),
                    ),
                )
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "oldLeaf = 1")
            visibleTag("memory-inspector-row-more").performClick()
            assertRowName("memory-inspector-row-100", "old99 = 99")
            inspectorTab(copy.references).performClick()
            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            visibleTag(REFERENCE_ROOT).performClick()
            assertExpanded(REFERENCE_ROOT, copy, expanded = false)

            runOnIdle {
                harness.state =
                    fixture("inspection-b").copy(
                        selectedInstanceDetail = newRoot,
                        inspectorObjects = mapOf(childKey to MemoryInspectorObjectState.Loaded(newChild)),
                    )
            }
            assertTabs(copy, fields = true, references = true, fieldsSelected = true)
            assertTitle(newRoot, copy)
            rootFilter().assertDoesNotExist()
            onNodeWithTag(REFERENCE_ROOT).assertDoesNotExist()
            assertRowName(ROW, "newChild = {NewChild}")
            assertExpanded(ROW, copy, expanded = false)
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            visibleTag("memory-inspector-row-more").assertIsDisplayed()
            onNodeWithTag("memory-inspector-row-100").assertDoesNotExist()
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "newLeaf = 2")
            onNode(hasText("oldLeaf = 1"), useUnmergedTree = true).assertDoesNotExist()

            inspectorTab(copy.references).performClick()
            rootFilter().assertIsOff()
            assertExpanded(REFERENCE_ROOT, copy, expanded = true)
            assertRowName(ROW, "newTarget in NewRoot@4 (0x4)")
            assertRowName("memory-inspector-row-1", "newOwner in NewOwner@342 (0x156)")
            rootFilterLabel(copy).performClick()
            assertRowName(ROW, "newTarget in NewRoot@4 (0x4)")
            onNodeWithTag("memory-inspector-row-1").assertDoesNotExist()
            assertNoStandaloneRootPath()
            runOnIdle { harness.assertNoNavigation() }
        }

    @Test
    fun `root arrays read more cumulatively in batches of 100 without replacing the selected instance`() =
        runInspectionTest { language, dark ->
            val root = arrayDetail(42L, start = 0, count = 250, loaded = 200)
            val next = arrayDetail(42L, start = 200, count = 250, loaded = 50)
            val harness = InspectorHarness(fixture().copy(selectedInstanceDetail = root), listOf(next))
            showInspector(harness, language, dark)
            assertTabs(labels(language), fields = true, references = false, fieldsSelected = true)
            assertArrayReadMore(harness, "memory-inspector-row", 42L)
            assertTitle(root, labels(language))
            runOnIdle {
                assertEquals(root, harness.state.selectedInstanceDetail)
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `nested arrays use child read more and inspector loading without main selection or root range callbacks`() =
        runInspectionTest { language, dark ->
            val root = detail(42L).copy(fields = listOf(link("items", 142L, "sample.Item[]")))
            val array = arrayDetail(142L, start = 0, count = 250, loaded = 200)
            val next = arrayDetail(142L, start = 200, count = 250, loaded = 50)
            val harness = InspectorHarness(fixture().copy(selectedInstanceDetail = root), listOf(array, next))
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            assertArrayReadMore(harness, "$ROW-child", 142L)
            assertTitle(root, labels(language))
            runOnIdle {
                assertEquals(root, harness.state.selectedInstanceDetail)
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `ancestor cycles stop local expansion without loading or selecting the ancestor`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            val root = detail(42L)
            val child = detail(142L).copy(fields = listOf(link("parent", 42L, "sample.Target")))
            val harness = InspectorHarness(fixture(), listOf(child))
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "parent = {Target}")
            visibleTag("$ROW-child-0").performClick()
            onNodeWithTag("$ROW-child-0-cycle").assertIsDisplayed()
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            visibleTag("$ROW-child-0").performClick()
            onNodeWithTag("$ROW-child-0-cycle").assertDoesNotExist()
            assertTitle(root, copy)
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L)), harness.requests)
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `details capture native fields reference root and nearest reference tree layouts`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryProfilerScreen(fixture(), MemoryProfilerActions(), language)
                }
            }
            assertTitle(detail(42L), copy)
            assertRowName(ROW, "child42 = {Child}")
            onNodeWithTag(REFERENCE_ROOT).assertDoesNotExist()
            val width = onRoot().captureToImage().width
            val locale = if (language == UiLanguage.ENGLISH) "en" else "zh"
            val theme = if (dark) "dark" else "light"
            val name = "memory-instance-details-$width-$locale-$theme"
            saveCapture(onRoot().captureToImage(), "$name-fields")
            inspectorTab(copy.references).performClick()
            assertRowName(REFERENCE_ROOT, "Target@42 (0x2a)")
            assertRowName("memory-inspector-row-1", "target in Owner42@242 (0xf2)")
            saveCapture(onRoot().captureToImage(), "$name-references")
            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            assertRowName(ROW, "target42 in Root@4 (0x4)")
            onNodeWithTag("memory-inspector-row-1").assertDoesNotExist()
            assertNoStandaloneRootPath()
            saveCapture(onRoot().captureToImage(), "$name-nearest-root")
        }

    private fun assertEntrySelection(legacy: Boolean) =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            var state by mutableStateOf(fixture().copy(selectedInstanceDetail = null))
            val selectedIds = mutableListOf<Long>()
            val actions =
                MemoryProfilerActions(
                    onSelectInstance = { id ->
                        selectedIds += id
                        state = state.copy(selectedInstanceDetail = detail(id))
                    },
                    onClearInstanceSelection = { state = state.copy(selectedInstanceDetail = null) },
                )
            setContent {
                ViewerTheme(darkTheme = dark) {
                    if (legacy) {
                        MemoryProfilerClassListPane(MemoryProfilerPresenter.present(state), actions, language)
                    } else {
                        MemoryProfilerScreen(state, actions, language)
                    }
                }
            }
            assertDetailsVisibility(visible = false)
            assertFlatInstanceRows()
            listOf(42L, 43L, 43L, 42L).forEach { id ->
                onNodeWithTag("memory-instance-row-$id").performClick().assertIsSelected()
                onNodeWithTag("memory-instance-row-${if (id == 42L) 43L else 42L}").assertIsNotSelected()
                assertFlatInstanceRows()
                assertDetailsVisibility(visible = true)
                assertTitle(detail(id), copy)
                assertTabs(copy, fields = true, references = true, fieldsSelected = true)
                assertRowName(ROW, "child$id = {Child}")
                onNodeWithTag(REFERENCE_ROOT).assertDoesNotExist()
            }
            inspectorTab(copy.references).performClick()
            rootFilterLabel(copy).performClick()
            onNodeWithTag("memory-instance-row-43").performClick()
            assertTabs(copy, fields = true, references = true, fieldsSelected = true)
            inspectorTab(copy.references).performClick()
            rootFilter().assertIsOn()
            assertRowName(REFERENCE_ROOT, "Target@43 (0x2b)")
            assertRowName(ROW, "target43 in Root@4 (0x4)")
            onNodeWithTag("memory-inspector-row-1").assertDoesNotExist()
            runOnIdle { assertEquals(listOf(42L, 43L, 43L, 42L, 43L), selectedIds) }

            val empty = detail(43L).copy(fields = emptyList(), references = emptyList())
            runOnIdle { state = state.copy(selectedInstanceDetail = empty) }
            onNodeWithTag("memory-instance-row-43").assertIsSelected()
            assertDetailsVisibility(visible = false)
            runOnIdle {
                state = state.copy(selectedInstanceDetail = arrayDetail(43L, start = 0, count = 1, loaded = 0))
            }
            assertDetailsVisibility(visible = true)
            assertTitle(state.selectedInstanceDetail!!, copy)
            assertTabs(copy, fields = true, references = false, fieldsSelected = true)
            onNodeWithTag("memory-inspector-close").performClick()
            assertDetailsVisibility(visible = false)
            onNodeWithTag("memory-instance-row-43").assertIsNotSelected()
        }

    private fun DesktopComposeUiTest.assertArrayReadMore(
        harness: InspectorHarness,
        prefix: String,
        objectId: Long,
    ) {
        assertRowName("$prefix-0", "0 = 0")
        assertRowName("$prefix-99", "99 = 99")
        onNodeWithTag("$prefix-100").assertDoesNotExist()
        visibleTag("$prefix-more").performClick()
        assertRowName("$prefix-199", "199 = 199")
        runOnIdle { assertTrue(harness.requests.none { it.arrayStart != 0 }) }
        visibleTag("$prefix-more").performClick()
        assertRowName("$prefix-249", "249 = 249")
        onNodeWithTag("$prefix-more").assertDoesNotExist()
        assertRowName("$prefix-0", "0 = 0")
        onNodeWithContentDescription("Previous elements").assertDoesNotExist()
        onNodeWithContentDescription("Next elements").assertDoesNotExist()
        runOnIdle {
            val expected =
                if (prefix == "memory-inspector-row") emptyList() else listOf(MemoryInspectorObjectKey(objectId))
            assertEquals(expected + MemoryInspectorObjectKey(objectId, 200), harness.requests)
            harness.assertNoNavigation()
        }
    }

    private fun DesktopComposeUiTest.showInspector(
        harness: InspectorHarness,
        language: UiLanguage,
        dark: Boolean,
    ) {
        setContent {
            ViewerTheme(darkTheme = dark) {
                MemoryObjectInspector(harness.state, harness.actions, language, Modifier.fillMaxSize())
            }
        }
    }

    private fun runInspectionTest(block: DesktopComposeUiTest.(UiLanguage, Boolean) -> Unit) {
        listOf(1280, 900).forEach { width ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = width, height = 800) { block(language, dark) }
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertFlatInstanceRows() {
        val inInstances = hasAnyAncestor(hasTestTag("memory-instance-list-table-pane"))
        onAllNodes(hasClickAction() and inInstances).assertCountEquals(2)
        listOf(42L, 43L).forEach { onNodeWithTag("memory-instance-row-$it").assertHeightIsEqualTo(20.dp) }
        listOf("\u25B6", "\u25BC", "\u25B8", "\u25BE", "GC Root", "sample.Root").forEach { text ->
            onNode(hasText(text, substring = true) and inInstances, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun DesktopComposeUiTest.assertDetailsVisibility(visible: Boolean) {
        listOf("memory-inspector-card", "memory-object-inspector", "memory-instance-list-resizer").forEach { tag ->
            if (visible) onNodeWithTag(tag).assertIsDisplayed() else onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    private fun DesktopComposeUiTest.assertTitle(
        detail: MemoryInstanceDetail,
        copy: InspectorLabels,
    ) {
        val simpleClass = detail.className.substringAfterLast('.')
        val identity = "$simpleClass@${detail.objectId} (0x${detail.objectId.toString(16)})"
        onNodeWithTag("memory-inspector-title").assertHeightIsEqualTo(28.dp)
        onNodeWithTag("memory-inspector-title-text", useUnmergedTree = true)
            .assertTextEquals("${copy.title} - $identity")
            .assertIsDisplayed()
    }

    private fun DesktopComposeUiTest.assertTabs(
        copy: InspectorLabels,
        fields: Boolean,
        references: Boolean,
        fieldsSelected: Boolean,
    ) {
        val tabs =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                hasAnyAncestor(hasTestTag("memory-object-inspector"))
        onAllNodes(tabs).assertCountEquals((if (fields) 1 else 0) + (if (references) 1 else 0))
        listOf(copy.fields to fields, copy.references to references).forEach { (label, available) ->
            val tab = inspectorTab(label)
            when {
                !available -> tab.assertDoesNotExist()
                (label == copy.fields) == fieldsSelected -> tab.assertIsSelected()
                else -> tab.assertIsNotSelected()
            }
        }
    }

    private fun DesktopComposeUiTest.assertNoStandaloneRootPath() {
        listOf("GC Root (JNI_GLOBAL)", "1. sample.Root", "2. sample.Target", "GC Root path").forEach { text ->
            onNode(
                hasText(text, substring = true) and hasAnyAncestor(hasTestTag("memory-object-inspector")),
                useUnmergedTree = true,
            ).assertDoesNotExist()
        }
    }

    private fun DesktopComposeUiTest.assertExpanded(
        tag: String,
        copy: InspectorLabels,
        expanded: Boolean,
    ) {
        visibleTag(tag).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                if (expanded) copy.expanded else copy.collapsed,
            ),
        )
    }

    private fun DesktopComposeUiTest.assertRowName(
        tag: String,
        text: String,
    ) {
        visibleTag(tag).assertIsDisplayed()
        onNodeWithTag("$tag-name", useUnmergedTree = true).assertTextEquals(text)
    }

    private fun DesktopComposeUiTest.visibleTag(tag: String): SemanticsNodeInteraction {
        onNodeWithTag("memory-inspector-content").performScrollToNode(hasTestTag(tag))
        return onNodeWithTag(tag)
    }

    private fun DesktopComposeUiTest.inspectorTab(label: String): SemanticsNodeInteraction =
        onNode(
            hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                hasAnyAncestor(hasTestTag("memory-object-inspector")),
        )

    private fun DesktopComposeUiTest.rootFilter() = onNodeWithTag("memory-show-nearest-gc-root-only")

    private fun DesktopComposeUiTest.rootFilterLabel(copy: InspectorLabels): SemanticsNodeInteraction =
        onNode(hasTestTag("memory-show-nearest-gc-root-only") and hasText(copy.nearestRootOnly))

    private companion object {
        const val ROW = "memory-inspector-row-0"
        const val REFERENCE_ROOT = "memory-inspector-reference-root"
    }
}

private fun fixture(snapshotId: String = "inspection-a"): MemoryProfilerState {
    val snapshot =
        HeapSnapshotSummary(
            snapshotId,
            capabilities = setOf(HeapCapability.HEAP_GRAPH, HeapCapability.GC_ROOTS),
        )
    val classes = listOf(ClassStats("sample.Target", instanceCount = 2, shallowSize = 64, retainedSize = 96))
    return MemoryProfilerState(
        activeSnapshotId = snapshotId,
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
    )
}

private fun detail(objectId: Long): MemoryInstanceDetail =
    MemoryInstanceDetail(
        objectId = objectId,
        className = "sample.Target",
        shallowSize = 32L,
        retainedSize = 48L,
        depth = 1,
        isArray = false,
        elementCount = null,
        fields = listOf(link("child$objectId", objectId + 100L, "sample.Child")),
        references =
            listOf(
                reference("target$objectId", 4L, "sample.Root", depth = 0),
                reference("target", objectId + 200L, "sample.Owner$objectId", depth = 1),
            ),
        referenceChain =
            listOf(
                ObjectReference("GC Root (JNI_GLOBAL)", 4L, "sample.Root"),
                ObjectReference("target$objectId", objectId, "sample.Target"),
            ),
    )

private fun arrayDetail(
    objectId: Long,
    start: Int,
    count: Int,
    loaded: Int,
): MemoryInstanceDetail =
    detail(objectId).copy(
        className = "sample.Item[]",
        isArray = true,
        elementCount = count,
        arrayStart = start,
        fields = List(loaded) { value("[${start + it}]", "${start + it}") },
        references = emptyList(),
        referenceChain = emptyList(),
        valueKind = HeapValueKind.ARRAY,
    )

private fun newSnapshotDetail(): MemoryInstanceDetail =
    detail(42L).copy(
        fields = listOf(link("newChild", 142L, "sample.NewChild")) + List(100) { value("new$it", "$it") },
        references =
            listOf(
                reference("newTarget", 4L, "sample.NewRoot", depth = 0),
                reference("newOwner", 342L, "sample.NewOwner", depth = 1),
            ),
    )

private fun link(
    name: String,
    target: Long,
    className: String,
): MemoryInstanceField =
    MemoryInstanceField(
        name = name,
        displayValue = "$className #$target",
        targetObjectId = target,
        targetClassName = className,
        valueKind = HeapValueKind.OBJECT,
    )

private fun reference(
    name: String,
    target: Long,
    className: String,
    depth: Int?,
    children: Int = 0,
): MemoryInstanceField = link(name, target, className).copy(depth = depth, childCount = children)

private fun value(
    name: String,
    display: String,
): MemoryInstanceField = MemoryInstanceField(name, display, null, null, valueKind = HeapValueKind.PRIMITIVE)

private fun labels(language: UiLanguage): InspectorLabels =
    if (language == UiLanguage.ENGLISH) {
        InspectorLabels(
            "Instance Details",
            "Fields",
            "References",
            "Show nearest GC root only",
            "Expanded",
            "Collapsed",
            "Array values are unavailable in this parsed evidence",
        )
    } else {
        InspectorLabels(
            "\u5B9E\u4F8B\u8BE6\u60C5",
            "\u5B57\u6BB5",
            "\u5F15\u7528",
            "\u4EC5\u663E\u793A\u6700\u8FD1\u7684 GC Root",
            "\u5DF2\u5C55\u5F00",
            "\u5DF2\u6536\u8D77",
            "\u5F53\u524D\u89E3\u6790\u8BC1\u636E\u4E2D\u6CA1\u6709\u53EF\u67E5\u770B\u7684\u6570\u7EC4\u503C",
        )
    }

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

private data class InspectorLabels(
    val title: String,
    val fields: String,
    val references: String,
    val nearestRootOnly: String,
    val expanded: String,
    val collapsed: String,
    val noArrayValues: String,
)

private class InspectorHarness(
    initial: MemoryProfilerState,
    details: List<MemoryInstanceDetail> = emptyList(),
) {
    var state by mutableStateOf(initial)
    val requests = mutableListOf<MemoryInspectorObjectKey>()
    private val selectedIds = mutableListOf<Long>()
    private val rootRanges = mutableListOf<Pair<Long, Int>>()
    private val responses = details.associateBy { MemoryInspectorObjectKey(it.objectId, it.arrayStart) }
    val actions =
        MemoryProfilerActions(
            onSelectInstance = { selectedIds += it },
            onLoadArrayRange = { id, start -> rootRanges += id to start },
            onLoadInspectorObject = { id, start ->
                val key = MemoryInspectorObjectKey(id, start)
                requests += key
                val response =
                    MemoryInspectorObjectState.Loaded(checkNotNull(responses[key]) { "Unexpected load: $key" })
                state = state.copy(inspectorObjects = state.inspectorObjects + (key to response))
            },
        )

    fun assertNoNavigation() {
        assertTrue(selectedIds.isEmpty(), "Ordinary inspector interactions must not select another instance")
        assertTrue(rootRanges.isEmpty(), "Read more must not use the root array range callback")
    }
}
