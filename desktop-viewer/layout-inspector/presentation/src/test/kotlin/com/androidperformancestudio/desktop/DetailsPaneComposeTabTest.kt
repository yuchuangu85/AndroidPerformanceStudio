package com.androidperformancestudio.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.androidperformancestudio.application.InspectorState
import com.androidperformancestudio.application.InspectorStore
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.compose.inspection.ComposeInspectionProjection
import com.androidperformancestudio.compose.inspection.ComposeSourceLocation
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.WindowSnapshot
import com.androidperformancestudio.protocol.WindowType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives the real details pane through the real store, projection and presenter, so the assertions
 * cover the tab strip, the section filtering and the selected category together: a COMPOSE section is
 * only rendered while the Compose tab is the active one, which is what makes these assertions prove
 * the automatic tab switch rather than just the presence of sections.
 */
@OptIn(ExperimentalTestApi::class)
class DetailsPaneComposeTabTest {
    @Test
    fun `selecting a composable opens the Compose tab`() {
        runComposeUiTest {
            setContent { DetailsPane(state = inspectorState(), modifier = Modifier) }

            onNodeWithText("Compose").assertIsDisplayed()
            val widths = (0..2).map {
                onNodeWithTag("segmented-selector-item-$it").fetchSemanticsNode().boundsInRoot.width
            }
            assertTrue(
                widths.max() - widths.min() <= 1f,
                "Expected visible Compose categories to be equal width: $widths",
            )
            onNodeWithText("MODIFIERS", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `the Compose summary lives on the overview tab`() {
        runComposeUiTest {
            setContent { DetailsPane(state = inspectorState(), modifier = Modifier) }

            // A composable opens on Compose, so the overview is one click away and has to carry the
            // same key facts rather than being the only place they exist.
            onNodeWithText("Overview").performClick()

            onNodeWithText("COMPOSE SUMMARY", substring = true).assertIsDisplayed()
            onAllNodesWithText("AnrDemoScreen.kt:112", substring = true).onFirst().assertExists()
        }
    }

    @Test
    fun `selecting the container view keeps the Compose sections hidden`() {
        runComposeUiTest {
            setContent { DetailsPane(state = inspectorState(selectedNodeId = "view:1"), modifier = Modifier) }

            onNodeWithText("Compose").assertDoesNotExist()
            onNodeWithText("MODIFIERS", substring = true).assertDoesNotExist()
            onNodeWithText("COMPOSE SUMMARY", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `compatible view refresh keeps the Compose failure visible`() {
        runComposeUiTest {
            setContent {
                DetailsPane(
                    state = inspectorState(selectedNodeId = "view:1", composeWarning = "Timed out waiting for Compose inspector agent socket"),
                    modifier = Modifier,
                )
            }

            onNodeWithText("Compose inspection unavailable", substring = true).assertIsDisplayed()
            onNodeWithText("Timed out waiting for Compose inspector agent socket", substring = true).assertDoesNotExist()
            onNodeWithContentDescription("Details").performClick()
            onNodeWithText("Timed out waiting for Compose inspector agent socket", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `selected Compose node does not expose raw failure through property rows`() {
        runComposeUiTest {
            val reason = "Agent attach failed (result=1): lib_ui_inspector_agent.so not found"
            setContent {
                DetailsPane(state = inspectorState(composeWarning = reason), modifier = Modifier)
            }

            onNodeWithText("result=1", substring = true).assertDoesNotExist()
            onNodeWithContentDescription("Details").performClick()
            onNodeWithText(reason).assertIsDisplayed()
        }
    }

    private fun inspectorState(
        selectedNodeId: String = "compose-inspection:" + COMPOSABLE_ID,
        composeWarning: String? = null,
    ): InspectorState {
        val composable = ComposableNode(
            id = COMPOSABLE_ID,
            anchorHash = 7,
            name = "DiagnosticsPanel",
            bounds = Bounds(0, 0, 100, 200),
            source = ComposeSourceLocation(packageHash = 0, fileName = "AnrDemoScreen.kt", lineNumber = 112, offset = 0),
        )
        val frame = ComposeInspectionFrame(
            frameId = "frame",
            generation = 1,
            mode = ComposeInspectionMode.FULL,
            capabilities = emptyList(),
            roots = listOf(ComposableRoot(viewId = COMPOSE_VIEW_ID, nodes = listOf(composable))),
            details = mapOf(
                COMPOSABLE_ID to ComposableDetail(
                    nodeId = COMPOSABLE_ID,
                    anchorHash = 7,
                    parameters = listOf(ComposeValue("onClick", LAMBDA_PARAMETER_TYPE, "AnrDemoScreen.kt:112")),
                    modifiers = listOf(ComposeValue("modifier", "STRING", "")),
                ),
            ),
        )
        val composeView = ViewNode(
            id = "view:" + COMPOSE_VIEW_ID,
            className = "androidx.compose.ui.platform.AndroidComposeView",
            bounds = Bounds(0, 0, 100, 200),
        )
        val root = ViewNode(
            id = "view:1",
            className = "android.widget.FrameLayout",
            bounds = Bounds(0, 0, 100, 200),
            children = listOf(composeView),
        )
        val snapshot = LayoutSnapshot(
            protocolVersion = CURRENT_PROTOCOL_VERSION,
            packageName = "example.app",
            capturedAtEpochMillis = 1,
            display = DisplayInfo(100, 200, 1f),
            capabilities = AgentCapabilities(viewHierarchy = true),
            root = root,
            windows = listOf(
                WindowSnapshot(
                    id = "window:view:1",
                    title = "MainActivity",
                    type = WindowType.OTHER,
                    bounds = Bounds(0, 0, 100, 200),
                    root = root,
                ),
            ),
            defaultWindowId = "window:view:1",
        )
        val store = InspectorStore()
        store.loadCapture(
            snapshot = ComposeInspectionProjection.mergeInto(snapshot, frame),
            screenshotPng = ByteArray(0),
            composeInspection = ComposeInspectionDocument(
                packageName = "example.app",
                capturedAtEpochMillis = 1,
                frame = frame,
            ),
        )
        composeWarning?.let(store::noteComposeInspectionUnavailable)
        check(store.selectNode(selectedNodeId)) { "selection was rejected: " + selectedNodeId }
        return store.state
    }

    private companion object {
        const val COMPOSABLE_ID = 42L
        const val COMPOSE_VIEW_ID = 7L
    }
}
