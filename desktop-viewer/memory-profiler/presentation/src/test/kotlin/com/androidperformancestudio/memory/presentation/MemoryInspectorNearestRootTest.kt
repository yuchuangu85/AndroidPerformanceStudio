package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MemoryInspectorNearestRootTest {
    @Test
    fun `root replacement restarts nearest expansion while the snapshot filter remains checked`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val harness = InspectorHarness(detail(42, 2, reference(142, 1)))
            showInspector(harness)
            selectReferences()
            onNodeWithTag(FILTER).assertIsOff().performClick().assertIsOn()
            onNodeWithTag("$ROW-loading").assertIsDisplayed()
            runOnIdle { harness.publish(detail(142, 1, reference(4, 0))) }
            onNodeWithTag("$ROW-child-0").assertIsSelected()

            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142)), harness.requests)
                harness.replaceRoot(detail(43, 2, reference(143, 1)))
            }
            onNodeWithTag(FILTER).assertIsOn()
            onNodeWithTag("memory-inspector-reference-root-name", useUnmergedTree = true)
                .assertTextEquals("Object43@43 (0x2b)")
            onNodeWithTag("$ROW-loading").assertIsDisplayed()
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142), MemoryInspectorObjectKey(143)), harness.requests)
                harness.publish(detail(143, 1, reference(5, 0)))
            }
            onNodeWithTag("$ROW-child-0").assertIsSelected()
            onNodeWithTag("$ROW-child-0-name", useUnmergedTree = true)
                .assertTextEquals("owner5 in Object5@5 (0x5)")
            runOnIdle {
                assertEquals(43L, harness.state.selectedInstanceDetail?.objectId)
                assertEquals("nearest-root-snapshot", harness.state.activeSnapshotId)
                assertEquals(listOf(MemoryInspectorObjectKey(142), MemoryInspectorObjectKey(143)), harness.requests)
            }
        }

    @Test
    fun `nearest expansion waits for each async result and stops at the first terminal root`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val harness = InspectorHarness(detail(42, 3, reference(142, 2), reference(143, 2)))
            showInspector(harness)
            selectReferences()
            onNodeWithTag(FILTER).performClick().assertIsOn()
            onNodeWithTag("$ROW-loading").assertIsDisplayed()
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142)), harness.requests)
                harness.publish(detail(142, 2, reference(242, 1), reference(243, 1)))
            }
            onNodeWithTag("$ROW-loading").assertDoesNotExist()
            onNodeWithTag("$ROW-child-0-loading").assertIsDisplayed()
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142), MemoryInspectorObjectKey(242)), harness.requests)
                harness.publish(detail(242, 1, reference(4, 0)))
            }
            onNodeWithTag("$ROW-child-0-loading").assertDoesNotExist()
            onNodeWithTag("$ROW-child-0-child-0").assertIsSelected()
            onNodeWithTag("$ROW-child-0-child-0-disclosure").assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-1-child-0").assertDoesNotExist()
            onNodeWithTag("$ROW-child-1-child-0").assertDoesNotExist()

            onNodeWithTag("memory-inspector-header-LABEL").performClick().performClick()
            onNodeWithTag("$ROW-child-0-child-0").assertIsSelected()
            onNodeWithTag("memory-inspector-row-1-loading").assertDoesNotExist()
            onNodeWithTag("memory-inspector-row-1-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142), MemoryInspectorObjectKey(242)), harness.requests)
                assertEquals(42L, harness.state.selectedInstanceDetail?.objectId)
            }
        }

    @Test
    fun `manual collapse cancels nearest expansion before a pending async result arrives`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val harness = InspectorHarness(detail(42, 3, reference(142, 2)))
            showInspector(harness)
            selectReferences()
            onNodeWithTag(FILTER).performClick().assertIsOn()
            onNodeWithTag("$ROW-loading").assertIsDisplayed()
            onNodeWithTag(ROW).performClick().assertIsSelected()
            onNodeWithTag("$ROW-loading").assertDoesNotExist()
            runOnIdle { harness.publish(detail(142, 2, reference(242, 1))) }
            onNodeWithTag(FILTER).assertIsOn()
            onNodeWithTag(ROW).assertIsSelected()
            onNodeWithTag("$ROW-child-0").assertDoesNotExist()
            runOnIdle { assertEquals(listOf(MemoryInspectorObjectKey(142)), harness.requests) }

            onNodeWithTag(ROW).performClick().assertIsSelected()
            onNodeWithTag("$ROW-child-0").assertIsDisplayed()
            onNodeWithTag("$ROW-child-0-loading").assertDoesNotExist()
            onNodeWithTag("$ROW-child-0-child-0").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(MemoryInspectorObjectKey(142)), harness.requests)
                assertEquals(42L, harness.state.selectedInstanceDetail?.objectId)
            }
        }

    private fun DesktopComposeUiTest.showInspector(harness: InspectorHarness) {
        setContent {
            ViewerTheme(darkTheme = false) {
                MemoryObjectInspector(harness.state, harness.actions, UiLanguage.ENGLISH, Modifier.fillMaxSize())
            }
        }
    }

    private fun DesktopComposeUiTest.selectReferences() {
        onNode(hasText("References") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performClick()
            .assertIsSelected()
    }

    private fun reference(
        objectId: Long,
        depth: Int,
    ): MemoryInstanceField =
        MemoryInstanceField(
            name = "owner$objectId",
            displayValue = "Object@$objectId",
            targetObjectId = objectId,
            targetClassName = "sample.Object$objectId",
            depth = depth,
        )

    private fun detail(
        objectId: Long,
        depth: Int,
        vararg references: MemoryInstanceField,
    ): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = objectId,
            className = "sample.Object$objectId",
            shallowSize = 16,
            retainedSize = null,
            depth = depth,
            isArray = false,
            elementCount = null,
            fields = listOf(MemoryInstanceField("count", "1", null, null)),
            references = references.toList(),
            referenceChain = emptyList(),
        )

    private class InspectorHarness(
        root: MemoryInstanceDetail,
    ) {
        var state by
            mutableStateOf(
                MemoryProfilerState(activeSnapshotId = "nearest-root-snapshot", selectedInstanceDetail = root),
            )
        val requests = mutableListOf<MemoryInspectorObjectKey>()
        val actions =
            MemoryProfilerActions(
                onLoadInspectorObject = { objectId, start ->
                    val key = MemoryInspectorObjectKey(objectId, start)
                    requests += key
                    val loading = MemoryInspectorObjectState.Loading
                    state = state.copy(inspectorObjects = state.inspectorObjects + (key to loading))
                },
            )

        fun publish(detail: MemoryInstanceDetail) {
            val key = MemoryInspectorObjectKey(detail.objectId, detail.arrayStart)
            val loaded = MemoryInspectorObjectState.Loaded(detail)
            state = state.copy(inspectorObjects = state.inspectorObjects + (key to loaded))
        }

        fun replaceRoot(detail: MemoryInstanceDetail) {
            state = state.copy(selectedInstanceDetail = detail, inspectorObjects = emptyMap())
        }
    }

    private companion object {
        const val FILTER = "memory-show-nearest-gc-root-only"
        const val ROW = "memory-inspector-row-0"
    }
}
