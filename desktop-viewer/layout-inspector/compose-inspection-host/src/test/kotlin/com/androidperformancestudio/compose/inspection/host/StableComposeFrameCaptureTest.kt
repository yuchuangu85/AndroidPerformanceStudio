package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StableComposeFrameCaptureTest {
    @Test
    fun `retries when target changes and returns a verified stable frame`() {
        val client = FakeClient(
            views = ArrayDeque(
                listOf(
                    viewCapture(rootId = 1, childId = "view:a"),
                    viewCapture(rootId = 1, childId = "view:b"),
                    viewCapture(rootId = 1, childId = "view:b"),
                    viewCapture(rootId = 1, childId = "view:stable"),
                    viewCapture(rootId = 1, childId = "view:stable"),
                    viewCapture(rootId = 1, childId = "view:stable"),
                ),
            ),
            frames = ArrayDeque(
                listOf(
                    composeFrame(rootId = 1, nodeId = 10),
                    composeFrame(rootId = 1, nodeId = 11),
                    composeFrame(rootId = 1, nodeId = 20),
                    composeFrame(rootId = 1, nodeId = 20),
                ),
            ),
        )

        val capture = StableComposeFrameCapture(client, maxAttempts = 2).capture("example.app")

        assertEquals("view:stable", capture.views.snapshot.root.children.single().id)
        assertEquals(20, capture.compose.roots.single().nodes.single().id)
        assertEquals(6, client.viewCaptures)
        assertEquals(4, client.composeCaptures)
    }

    @Test
    fun `fails instead of merging continuously changing view and compose trees`() {
        var nextView = 0
        var nextCompose = 0L
        val client = object : ComposeFrameCaptureClient {
            override fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture =
                viewCapture(rootId = 1, childId = "view:${nextView++}")

            override fun captureTree(rootViewIds: List<Long>): ComposeInspectionFrame =
                composeFrame(rootId = 1, nodeId = nextCompose++)
        }

        val error = assertThrows(IllegalStateException::class.java) {
            StableComposeFrameCapture(client, maxAttempts = 2).capture("example.app")
        }

        assertEquals("Target changed during Compose frame capture; try again", error.message)
    }

    private class FakeClient(
        private val views: ArrayDeque<ViewInspectionCapture>,
        private val frames: ArrayDeque<ComposeInspectionFrame>,
    ) : ComposeFrameCaptureClient {
        var viewCaptures = 0
        var composeCaptures = 0

        override fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture {
            viewCaptures += 1
            return views.removeFirst()
        }

        override fun captureTree(rootViewIds: List<Long>): ComposeInspectionFrame {
            composeCaptures += 1
            return frames.removeFirst()
        }
    }

    private companion object {
        fun viewCapture(rootId: Long, childId: String): ViewInspectionCapture {
            val root = ViewNode(
                id = "view:$rootId",
                className = "Root",
                bounds = Bounds(0, 0, 100, 100),
                children = listOf(ViewNode(childId, "Child", Bounds(0, 0, 10, 10))),
            )
            return ViewInspectionCapture(
                snapshot = LayoutSnapshot(
                    protocolVersion = CURRENT_PROTOCOL_VERSION,
                    packageName = "example.app",
                    capturedAtEpochMillis = 1,
                    display = DisplayInfo(100, 100, 1f),
                    capabilities = AgentCapabilities(viewHierarchy = true),
                    root = root,
                ),
                rootViewIds = listOf(rootId),
            )
        }

        fun composeFrame(rootId: Long, nodeId: Long): ComposeInspectionFrame = ComposeInspectionFrame(
            frameId = "frame-$nodeId",
            generation = nodeId.toInt(),
            mode = ComposeInspectionMode.FULL,
            capabilities = emptyList(),
            roots = listOf(
                ComposableRoot(
                    viewId = rootId,
                    nodes = listOf(ComposableNode(nodeId, 1, "Node", Bounds(0, 0, 10, 10))),
                ),
            ),
        )
    }
}
