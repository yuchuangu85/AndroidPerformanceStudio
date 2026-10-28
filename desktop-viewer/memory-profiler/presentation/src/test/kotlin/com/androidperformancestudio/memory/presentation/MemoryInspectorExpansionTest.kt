package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.fields
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_more
import com.androidperformancestudio.memory.presentation.generated.resources.references
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryInspectorExpansionTest {
    @Test
    fun `fields expand multiple levels inline and collapse without selecting another instance`() =
        runInspectorCases { language, dark ->
            val harness =
                ExpansionInspectorHarness(
                    fixture(),
                    listOf(
                        detail(142L, fields = listOf(link("grandchild", 242L), value("childCount", "2"))),
                        detail(242L, fields = listOf(value("leaf", "true"))),
                    ),
                )
            showInspector(harness, language, dark)
            runOnIdle { assertTrue(harness.requests.isEmpty()) }
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            assertRowName(ROW, "child = {Object142}")
            assertRowName("memory-inspector-row-1", "count = 7")
            assertRowName("memory-inspector-row-2", "optional = null")
            assertRowName("memory-inspector-row-3", "zero = null")
            listOf(1, 2, 3).forEach { index ->
                visibleTag("memory-inspector-row-$index").performClick().assertIsSelected()
            }
            runOnIdle {
                assertTrue(harness.requests.isEmpty())
                harness.assertNoNavigation()
            }

            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "grandchild = {Object242}")
            visibleTag("$ROW-child-1").performClick().assertIsSelected()
            visibleTag("$ROW-child-0").performClick()
            assertRowName("$ROW-child-0-child-0", "leaf = true")
            assertInlineOrder(ROW, "$ROW-child-0", "$ROW-child-0-child-0", "memory-inspector-row-1")

            visibleTag("$ROW-child-0").performClick()
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            visibleTag("$ROW-child-0").performClick()
            assertRowName("$ROW-child-0-child-0", "leaf = true")
            visibleTag(ROW).performClick()
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "grandchild = {Object242}")
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L), MemoryInspectorObjectKey(242L)), harness.requests)
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `lazy loading can collapse and receive results without reopening the branch`() =
        runInspectorCases { language, dark ->
            val harness = ExpansionInspectorHarness(fixture(), missingState = MemoryInspectorObjectState.Loading)
            showInspector(harness, language, dark)
            onNodeWithTag("$ROW-loading").assertDoesNotExist()
            visibleTag(ROW).performClick()
            visibleTag("$ROW-loading").assertIsDisplayed().assertHasNoClickAction()
            runOnIdle { harness.state = harness.state.copy(inspectorObjects = emptyMap()) }
            visibleTag("$ROW-loading").assertIsDisplayed()
            runOnIdle { assertEquals(listOf(MemoryInspectorObjectKey(142L)), harness.requests) }
            visibleTag(ROW).performClick()
            onNodeWithTag("$ROW-loading").assertDoesNotExist()
            runOnIdle { harness.publish(142L, MemoryInspectorObjectState.Unavailable) }
            onNodeWithTag("$ROW-unavailable").assertDoesNotExist()
            runOnIdle {
                harness.publish(
                    142L,
                    MemoryInspectorObjectState.Loaded(detail(142L, fields = listOf(value("resolved", "7")))),
                )
            }
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "resolved = 7")
            onNodeWithTag("$ROW-unavailable").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L)), harness.requests)
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `unavailable and empty objects show distinct inline hints and collapse independently`() =
        runInspectorCases { language, dark ->
            val root = detail(42L, fields = listOf(link("missing", 143L), link("empty", 144L)))
            val harness = ExpansionInspectorHarness(fixture(root), listOf(detail(144L)))
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            visibleTag("$ROW-unavailable").assertIsDisplayed().assertHasNoClickAction()
            visibleTag("memory-inspector-row-1").performClick()
            visibleTag("memory-inspector-row-1-empty").assertIsDisplayed().assertHasNoClickAction()
            onNodeWithTag("memory-inspector-row-1-unavailable").assertDoesNotExist()
            visibleTag(ROW).performClick()
            onNodeWithTag("$ROW-unavailable").assertDoesNotExist()
            visibleTag("memory-inspector-row-1-empty").assertIsDisplayed()
            visibleTag("memory-inspector-row-1").performClick()
            onNodeWithTag("memory-inspector-row-1-empty").assertDoesNotExist()
            runOnIdle { harness.assertNoNavigation() }
        }

    @Test
    fun `fields sharing a target retain separate expansion paths at every level`() =
        runInspectorCases { language, dark ->
            val root = detail(42L, fields = listOf(link("left", 142L), link("right", 142L)))
            val harness =
                ExpansionInspectorHarness(
                    fixture(root),
                    listOf(
                        detail(142L, fields = listOf(link("sharedChild", 242L))),
                        detail(242L, fields = listOf(value("leaf", "1"))),
                    ),
                )
            val other = "memory-inspector-row-1"
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "sharedChild = {Object242}")
            onNodeWithTag("$other-child-0").assertDoesNotExist()
            visibleTag(other).performClick()
            assertRowName("$other-child-0", "sharedChild = {Object242}")
            visibleTag("$ROW-child-0").performClick()
            assertRowName("$ROW-child-0-child-0", "leaf = 1")
            onNodeWithTag("$other-child-0-child-0").assertDoesNotExist()
            visibleTag("$other-child-0").performClick()
            assertRowName("$other-child-0-child-0", "leaf = 1")
            visibleTag(ROW).performClick()
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            assertRowName("$other-child-0-child-0", "leaf = 1")
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L), MemoryInspectorObjectKey(242L)), harness.requests)
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `self and ancestor cycles expand hints without loading or recursing`() =
        runInspectorCases { language, dark ->
            val root = detail(42L, fields = listOf(link("self", 42L), link("child", 142L)))
            val harness =
                ExpansionInspectorHarness(
                    fixture(root),
                    listOf(detail(142L, fields = listOf(link("parent", 42L), link("self", 142L)))),
                )
            val branch = "memory-inspector-row-1"
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            visibleTag("$ROW-cycle").assertIsDisplayed().assertHasNoClickAction()
            onNodeWithTag("$ROW-loading").assertDoesNotExist()
            runOnIdle { assertTrue(harness.requests.isEmpty()) }
            visibleTag(ROW).performClick()
            onNodeWithTag("$ROW-cycle").assertDoesNotExist()
            visibleTag(branch).performClick()
            visibleTag("$branch-child-0").performClick()
            visibleTag("$branch-child-0-cycle").assertIsDisplayed()
            visibleTag("$branch-child-1").performClick()
            visibleTag("$branch-child-1-cycle").assertIsDisplayed()
            onNodeWithTag("$branch-child-0-child-0").assertDoesNotExist()
            onNodeWithTag("$branch-child-1-child-0").assertDoesNotExist()
            visibleTag("$branch-child-0").performClick()
            onNodeWithTag("$branch-child-0-cycle").assertDoesNotExist()
            visibleTag("$branch-child-1-cycle").assertIsDisplayed()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L)), harness.requests)
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `references recursively expand inbound edges instead of fields with tab local paths`() =
        runInspectorCases { language, dark ->
            val root = detail(42L, fields = listOf(link("outbound", 142L)), references = listOf(link("owner", 142L)))
            val harness =
                ExpansionInspectorHarness(
                    fixture(root),
                    listOf(
                        detail(
                            142L,
                            fields = listOf(value("fieldOnly", "1")),
                            references = listOf(link("outerOwner", 242L)),
                        ),
                        detail(
                            242L,
                            fields = listOf(value("otherFieldOnly", "2")),
                            references = listOf(link("backToRoot", 42L)),
                        ),
                    ),
                )
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "fieldOnly = 1")
            selectReferences(language)
            assertRowName(REFERENCE_ROOT, "Target@42 (0x2a)")
            assertRowName(ROW, "owner in Object142@142 (0x8e)")
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "outerOwner in Object242@242 (0xf2)")
            visibleTag("$ROW-child-0").performClick()
            assertRowName("$ROW-child-0-child-0", "backToRoot in Target@42 (0x2a)")
            visibleTag("$ROW-child-0-child-0").performClick()
            visibleTag("$ROW-child-0-child-0-cycle").assertIsDisplayed()
            inspectorTab(localizedStringResource(Res.string.fields, language)).performClick()
            assertRowName("$ROW-child-0", "fieldOnly = 1")
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            selectReferences(language)
            assertRowName("$ROW-child-0-child-0", "backToRoot in Target@42 (0x2a)")
            visibleTag(ROW).performClick()
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L), MemoryInspectorObjectKey(242L)), harness.requests)
                harness.assertNoNavigation()
            }
        }

    @Test
    @Suppress("LongMethod")
    fun `nearest references autoexpand the first decreasing path and preserve original child indices`() =
        runInspectorCases { language, dark ->
            val root =
                detail(
                    42L,
                    depth = 3,
                    references =
                        listOf(
                            link("nearer", 142L, 2),
                            link("equal", 143L, 3),
                            link("unknown", 144L),
                            link("ownerWithUnknownDetailDepth", 146L, 2),
                        ),
                )
            val harness =
                ExpansionInspectorHarness(
                    fixture(root),
                    listOf(
                        detail(
                            142L,
                            depth = 2,
                            references =
                                listOf(
                                    link("equalChild", 243L, 2),
                                    link("nearerChild", 242L, 1),
                                    link("unknownChild", 244L),
                                    link("fartherChild", 245L, 4),
                                ),
                        ),
                        detail(
                            242L,
                            depth = 1,
                            references =
                                listOf(
                                    link("equalGrandchild", 342L, 1),
                                    link("gcRoot", 4L, 0).copy(childCount = 0),
                                ),
                        ),
                        detail(146L, depth = null, references = listOf(link("knownRoot", 4L, 0))),
                    ),
                )
            showInspector(harness, language, dark)
            selectReferences(language)
            onNodeWithTag("memory-show-nearest-gc-root-only").performClick().assertIsOn()
            onNodeWithTag("memory-inspector-row-1").assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-2").assertDoesNotExist()
            assertRowName("$ROW-child-1", "nearerChild in Object242@242 (0xf2)")
            listOf(0, 2, 3).forEach { index ->
                onNodeWithTag("$ROW-child-$index").assertDoesNotExist()
            }
            assertRowName("$ROW-child-1-child-1", "gcRoot in Object4@4 (0x4)")
            onNodeWithTag("$ROW-child-1-child-1").assertIsSelected()
            onNodeWithTag("$ROW-child-1-child-0").assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-3-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L), MemoryInspectorObjectKey(242L)), harness.requests)
            }
            visibleTag("memory-inspector-row-3").performClick()
            onNodeWithTag("memory-inspector-row-3").assertIsSelected()
            onNodeWithTag("memory-inspector-row-3-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(
                    listOf(
                        MemoryInspectorObjectKey(142L),
                        MemoryInspectorObjectKey(242L),
                        MemoryInspectorObjectKey(146L),
                    ),
                    harness.requests,
                )
                harness.assertNoNavigation()
            }
        }

    @Test
    @Suppress("LongMethod")
    fun `reference root collapses without a standalone GC path and tabs follow available content`() =
        runInspectorCases { language, dark ->
            val root =
                detail(42L, references = listOf(link("owner", 142L), link("backToRoot", 42L))).copy(
                    referenceChain =
                        listOf(
                            ObjectReference("GC Root (JNI_GLOBAL)", 4L, "sample.GcRoot"),
                            ObjectReference("target", 42L, "sample.Target"),
                        ),
                )
            val harness = ExpansionInspectorHarness(fixture(root), listOf(detail(142L)))
            showInspector(harness, language, dark)
            selectReferences(language)
            inspectorTab(localizedStringResource(Res.string.fields, language)).assertDoesNotExist()
            assertRowName(REFERENCE_ROOT, "Target@42 (0x2a)")
            onNodeWithTag("memory-inspector-root-row-0").assertDoesNotExist()
            onNodeWithTag("memory-inspector-root-row-1").assertDoesNotExist()
            visibleTag(ROW).performClick()
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            visibleTag("memory-inspector-row-1").performClick()
            visibleTag("memory-inspector-row-1-cycle").assertIsDisplayed()
            visibleTag(REFERENCE_ROOT).performClick()
            onNodeWithTag(ROW).assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-1-cycle").assertDoesNotExist()
            visibleTag(REFERENCE_ROOT).performClick()
            visibleTag("memory-inspector-row-1-cycle").assertIsDisplayed()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L)), harness.requests)
                harness.assertNoNavigation()
            }

            val noContent = harness.initial.copy(selectedInstanceDetail = root.copy(references = emptyList()))
            runOnIdle { harness.state = noContent.copy(inspectorObjects = harness.state.inspectorObjects) }
            onNodeWithTag("memory-object-inspector").assertDoesNotExist()
            val fieldsOnly = noContent.copy(selectedInstanceDetail = detail(42L, fields = listOf(value("count", "1"))))
            runOnIdle { harness.state = fieldsOnly.copy(inspectorObjects = harness.state.inspectorObjects) }
            inspectorTab(localizedStringResource(Res.string.fields, language)).assertIsSelected()
            inspectorTab(localizedStringResource(Res.string.references, language)).assertDoesNotExist()
            assertRowName(ROW, "count = 1")
            onNodeWithTag(REFERENCE_ROOT).assertDoesNotExist()
            val emptyArray = noContent.copy(selectedInstanceDetail = arrayDetail(42L, 0, emptyList(), 0))
            runOnIdle { harness.state = emptyArray.copy(inspectorObjects = harness.state.inspectorObjects) }
            onNodeWithTag("memory-object-inspector").assertDoesNotExist()
            val nonemptyArray = noContent.copy(selectedInstanceDetail = arrayDetail(42L, 0, emptyList(), 1))
            runOnIdle { harness.state = nonemptyArray.copy(inspectorObjects = harness.state.inspectorObjects) }
            inspectorTab(localizedStringResource(Res.string.fields, language)).assertIsSelected()
            inspectorTab(localizedStringResource(Res.string.references, language)).assertDoesNotExist()
            visibleTag("memory-inspector-row-empty").assertIsDisplayed().assertHasNoClickAction()
            runOnIdle { harness.assertNoNavigation(nonemptyArray) }
        }

    @Test
    fun `root and snapshot identity changes clear expansion even when cached target ids are reused`() =
        runInspectorCases { language, dark ->
            val harness =
                ExpansionInspectorHarness(
                    fixture(),
                    listOf(
                        detail(142L, fields = listOf(link("grandchild", 242L))),
                        detail(242L, fields = listOf(value("leaf", "1"))),
                    ),
                )
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            visibleTag("$ROW-child-0").performClick()
            assertRowName("$ROW-child-0-child-0", "leaf = 1")
            val otherRoot =
                harness.initial.copy(
                    selectedInstanceDetail = harness.initial.selectedInstanceDetail!!.copy(objectId = 43L),
                )
            runOnIdle { harness.state = otherRoot.copy(inspectorObjects = harness.state.inspectorObjects) }
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "grandchild = {Object242}")
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            runOnIdle {
                harness.assertNoNavigation(otherRoot)
                harness.state = harness.initial.copy(inspectorObjects = harness.state.inspectorObjects)
            }
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "grandchild = {Object242}")
            val nextSnapshot =
                harness.initial.copy(
                    activeSnapshotId = "expansion-next",
                    snapshotSummary = harness.initial.snapshotSummary!!.copy(id = "expansion-next"),
                )
            runOnIdle { harness.state = nextSnapshot.copy(inspectorObjects = harness.state.inspectorObjects) }
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            visibleTag(ROW).performClick()
            assertRowName("$ROW-child-0", "grandchild = {Object242}")
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142L), MemoryInspectorObjectKey(242L)), harness.requests)
                harness.assertNoNavigation(nextSnapshot)
            }
        }

    @Test
    @Suppress("LongMethod")
    fun `root and nested arrays append batches and load only beyond cached fields without replacing siblings`() =
        runInspectorCases { language, dark ->
            val root =
                arrayDetail(
                    42L,
                    0,
                    listOf(
                        link("[0]", 500L).copy(valueKind = HeapValueKind.ARRAY),
                        link("[1]", 501L).copy(valueKind = HeapValueKind.ARRAY),
                    ) + arrayValues(2, 148),
                )
            val harness =
                ExpansionInspectorHarness(
                    fixture(root),
                    listOf(
                        arrayDetail(500L, 0, listOf(link("[0]", 600L)) + arrayValues(1, 149)),
                        arrayDetail(500L, 150, arrayValues(150, 55)),
                        arrayDetail(
                            501L,
                            0,
                            listOf(value("[0]", "siblingZero"), value("[1]", "siblingOne")),
                            elementCount = 2,
                        ),
                        arrayDetail(42L, 150, arrayValues(150, 55)),
                        detail(600L, fields = listOf(value("arrayChild", "true"))),
                    ),
                )
            val other = "memory-inspector-row-1"
            val more = "$ROW-child-more"
            val initialRequests =
                listOf(MemoryInspectorObjectKey(500L), MemoryInspectorObjectKey(600L), MemoryInspectorObjectKey(501L))
            showInspector(harness, language, dark)
            visibleTag(ROW).performClick()
            visibleTag("$ROW-child-0").performClick()
            assertRowName("$ROW-child-0-child-0", "arrayChild = true")
            visibleTag(other).performClick()
            assertRowName("$other-child-0", "0 = siblingZero")
            assertRowName("$ROW-child-99", "99 = 99")
            visibleTag(more).assertTextEquals(localizedStringResource(Res.string.memory_inspector_more, language, 100))
            visibleTag(more).performClick()
            assertRowName("$ROW-child-149", "149 = 149")
            runOnIdle { assertEquals(initialRequests, harness.requests) }
            visibleTag(more).assertTextEquals(localizedStringResource(Res.string.memory_inspector_more, language, 55))
            visibleTag(more).performClick()
            assertRowName("$ROW-child-204", "204 = 204")
            onNodeWithTag(more).assertDoesNotExist()
            assertRowName("$ROW-child-0-child-0", "arrayChild = true")
            assertRowName("$other-child-0", "0 = siblingZero")
            runOnIdle { assertEquals(initialRequests + MemoryInspectorObjectKey(500L, 150), harness.requests) }

            assertRowName("memory-inspector-row-99", "99 = 99")
            visibleTag("memory-inspector-row-more").performClick()
            assertRowName("memory-inspector-row-149", "149 = 149")
            runOnIdle { assertEquals(initialRequests + MemoryInspectorObjectKey(500L, 150), harness.requests) }
            visibleTag("memory-inspector-row-more").performClick()
            assertRowName("memory-inspector-row-204", "204 = 204")
            onNodeWithTag("memory-inspector-row-more").assertDoesNotExist()
            assertRowName("$ROW-child-0-child-0", "arrayChild = true")
            assertRowName("$other-child-0", "0 = siblingZero")
            onNodeWithTag("$ROW-array-page").assertDoesNotExist()
            runOnIdle {
                assertEquals(
                    initialRequests + listOf(MemoryInspectorObjectKey(500L, 150), MemoryInspectorObjectKey(42L, 150)),
                    harness.requests,
                )
                harness.assertNoNavigation()
            }
        }

    @Test
    fun `class and instance tables stay selected and flat while inspector branches expand`() {
        listOf(900, 1280).forEach { width ->
            runInspectorCases(widths = listOf(width), height = 900) { language, dark ->
                val harness =
                    ExpansionInspectorHarness(
                        fixture(),
                        listOf(detail(142L, fields = listOf(value("childOnly", "1")))),
                    )
                setContent {
                    ViewerTheme(darkTheme = dark) {
                        MemoryProfilerScreen(harness.state, harness.actions, language)
                    }
                }
                onNodeWithTag("memory-instance-row-42").assertIsSelected()
                onNodeWithTag("memory-instance-row-43").assertIsNotSelected()
                onNodeWithTag("memory-classifier-row-class:sample.Target").assertIsDisplayed()
                visibleTag(ROW).performClick()
                assertRowName("$ROW-child-0", "childOnly = 1")
                onNodeWithTag("memory-instance-row-42").assertIsSelected()
                onNodeWithTag("memory-instance-row-43").assertIsNotSelected()
                onNodeWithTag("memory-classifier-row-class:sample.Target").assertIsDisplayed()
                val inList = hasAnyAncestor(hasTestTag("memory-instance-list-table-pane"))
                onAllNodes(hasClickAction() and inList).assertCountEquals(2)
                onNode(hasText("childOnly", substring = true) and inList, useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("memory-instance-row-142").assertDoesNotExist()
                if (width == 1280 && language == UiLanguage.ENGLISH && !dark) {
                    saveExpandedWorkspaceCapture()
                }
                visibleTag(ROW).performClick()
                onNodeWithTag("$ROW-child-0").assertDoesNotExist()
                runOnIdle { harness.assertNoNavigation() }
            }
        }
    }

    private fun fixture(root: MemoryInstanceDetail = rootDetail()): MemoryProfilerState {
        val snapshot =
            HeapSnapshotSummary(
                "expansion-snapshot",
                capabilities = setOf(HeapCapability.HEAP_GRAPH, HeapCapability.GC_ROOTS),
            )
        val classes = listOf(ClassStats("sample.Target", instanceCount = 2, shallowSize = 64L, retainedSize = 96L))
        return MemoryProfilerState(
            activeSnapshotId = snapshot.id,
            snapshotSummary = snapshot,
            snapshotSummaries = listOf(snapshot),
            classes = classes,
            heapBaseClasses = classes,
            selectedClassName = "sample.Target",
            selectedClassifierId = "class:sample.Target",
            selectedClassifierLabel = "sample.Target",
            selectedClassInstances =
                listOf(
                    MemoryInstanceRow(42L, 1, 32L, 48L, 3, true, className = "sample.Target"),
                    MemoryInstanceRow(43L, 2, 32L, 48L, 3, true, className = "sample.Target"),
                ),
            selectedInstanceDetail = root,
            instanceHistory = listOf(7L, 42L, 43L),
            instanceHistoryIndex = 1,
        )
    }

    private fun rootDetail(): MemoryInstanceDetail =
        detail(
            42L,
            fields = listOf(link("child", 142L), value("count", "7"), value("optional", "null"), link("zero", 0L)),
        )

    private fun detail(
        objectId: Long,
        fields: List<MemoryInstanceField> = emptyList(),
        references: List<MemoryInstanceField> = emptyList(),
        depth: Int? = 3,
    ): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = objectId,
            className = if (objectId == 42L) "sample.Target" else "sample.Object$objectId",
            shallowSize = 32L,
            retainedSize = 48L,
            depth = depth,
            isArray = false,
            elementCount = null,
            fields = fields,
            referenceChain = emptyList(),
            references = references,
            valueKind = HeapValueKind.OBJECT,
        )

    private fun arrayDetail(
        objectId: Long,
        start: Int,
        fields: List<MemoryInstanceField>,
        elementCount: Int = 205,
    ): MemoryInstanceDetail =
        detail(objectId, fields).copy(
            isArray = true,
            elementCount = elementCount,
            arrayStart = start,
            arrayPageSize = 100,
            valueKind = HeapValueKind.ARRAY,
        )

    private fun arrayValues(
        start: Int,
        count: Int,
    ): List<MemoryInstanceField> = List(count) { offset -> value("[${start + offset}]", "${start + offset}") }

    private fun link(
        name: String,
        target: Long,
        depth: Int? = null,
    ): MemoryInstanceField =
        MemoryInstanceField(
            name = name,
            displayValue = "sample.Object$target #$target",
            targetObjectId = target,
            targetClassName = if (target == 42L) "sample.Target" else "sample.Object$target",
            depth = depth,
            valueKind = if (target == 0L) HeapValueKind.NULL else HeapValueKind.OBJECT,
            retainedSize = if (target == 0L) 0L else 48L,
        )

    private fun value(
        name: String,
        display: String,
    ): MemoryInstanceField =
        MemoryInstanceField(
            name = name,
            displayValue = display,
            targetObjectId = null,
            targetClassName = null,
            valueKind = if (display == "null") HeapValueKind.NULL else HeapValueKind.PRIMITIVE,
            retainedSize = 0L,
        )

    private companion object {
        const val ROW = "memory-inspector-row-0"
        const val REFERENCE_ROOT = "memory-inspector-reference-root"
    }
}

@OptIn(ExperimentalTestApi::class)
private fun runInspectorCases(
    widths: List<Int> = listOf(260, 520),
    height: Int = 640,
    block: DesktopComposeUiTest.(UiLanguage, Boolean) -> Unit,
) {
    widths.forEach { width ->
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = width, height = height) { block(language, dark) }
            }
        }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.showInspector(
    harness: ExpansionInspectorHarness,
    language: UiLanguage,
    dark: Boolean,
) {
    setContent {
        ViewerTheme(darkTheme = dark) {
            MemoryObjectInspector(harness.state, harness.actions, language, Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.visibleTag(tag: String): SemanticsNodeInteraction {
    onNodeWithTag("memory-inspector-content").performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag)
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.assertRowName(
    tag: String,
    name: String,
) {
    visibleTag(tag).assertIsDisplayed()
    onNodeWithTag("$tag-name", useUnmergedTree = true).assertTextEquals(name)
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.assertInlineOrder(vararg tags: String) {
    val pane = onNodeWithTag("memory-inspector-content").fetchSemanticsNode().boundsInRoot
    val rows = tags.map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
    rows.forEach { row ->
        assertTrue(row.width > 0f && row.left >= pane.left - 1f && row.right <= pane.right + 1f)
    }
    rows.zipWithNext().forEach { (before, after) -> assertTrue(before.bottom <= after.top + 1f) }
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.inspectorTab(label: String): SemanticsNodeInteraction =
    onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            hasAnyAncestor(hasTestTag("memory-object-inspector")),
    )

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.selectReferences(language: UiLanguage) {
    inspectorTab(localizedStringResource(Res.string.references, language)).performClick().assertIsSelected()
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.saveExpandedWorkspaceCapture() {
    val image = onRoot().captureToImage()
    val output = Path.of("build/screenshots/memory-inspector-expanded-workspace-1280-en-light.png")
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

private class ExpansionInspectorHarness(
    val initial: MemoryProfilerState,
    details: List<MemoryInstanceDetail> = emptyList(),
    missingState: MemoryInspectorObjectState = MemoryInspectorObjectState.Unavailable,
) {
    var state by mutableStateOf(initial)
    val requests = mutableListOf<MemoryInspectorObjectKey>()
    private val selectedInstances = mutableListOf<Long>()
    private val selectedClasses = mutableListOf<String>()
    private val selectedClassifiers = mutableListOf<MemoryClassifierRow>()
    private val rootArrayRanges = mutableListOf<Pair<Long, Int>>()
    private var historyNavigations = 0
    private val responses =
        details.associate { detail ->
            val key = MemoryInspectorObjectKey(detail.objectId, detail.arrayStart)
            key to MemoryInspectorObjectState.Loaded(detail)
        }
    val actions =
        MemoryProfilerActions(
            onSelectInstance = { selectedInstances += it },
            onSelectClass = { selectedClasses += it },
            onSelectClassifier = { selectedClassifiers += it },
            onNavigateInstanceBack = { historyNavigations++ },
            onNavigateInstanceForward = { historyNavigations++ },
            onLoadArrayRange = { id, start -> rootArrayRanges += id to start },
            onLoadInspectorObject = { id, start ->
                val key = MemoryInspectorObjectKey(id, start)
                requests += key
                val result = responses[key] ?: missingState
                state = state.copy(inspectorObjects = state.inspectorObjects + (key to result))
            },
        )

    fun publish(
        objectId: Long,
        result: MemoryInspectorObjectState,
    ) {
        val key = MemoryInspectorObjectKey(objectId)
        state = state.copy(inspectorObjects = state.inspectorObjects + (key to result))
    }

    fun assertNoNavigation(expected: MemoryProfilerState = initial) {
        assertTrue(selectedInstances.isEmpty(), "Inspector content must not call onSelectInstance")
        assertTrue(selectedClasses.isEmpty(), "Inspector content must not change the selected class")
        assertTrue(selectedClassifiers.isEmpty(), "Inspector content must not change the selected classifier")
        assertTrue(rootArrayRanges.isEmpty(), "Nested arrays must not call onLoadArrayRange")
        assertEquals(0, historyNavigations)
        assertEquals(expected.copy(inspectorObjects = state.inspectorObjects), state)
    }
}
