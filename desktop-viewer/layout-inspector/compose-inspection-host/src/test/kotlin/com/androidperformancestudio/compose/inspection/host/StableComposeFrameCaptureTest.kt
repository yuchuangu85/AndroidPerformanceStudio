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
import com.androidperformancestudio.protocol.WindowSnapshot
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertEquals(listOf(false, true, false, true), client.detailRequests)
    }

    @Test
    fun `fails instead of merging continuously changing view and compose trees`() {
        var nextView = 0
        var nextCompose = 0L
        val client = object : ComposeFrameCaptureClient {
            override fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture =
                viewCapture(rootId = 1, childId = "view:${nextView++}")

            override fun captureTree(rootViewIds: List<Long>, includeDetails: Boolean): ComposeInspectionFrame =
                composeFrame(rootId = 1, nodeId = nextCompose++)
        }

        val error = assertThrows(IllegalStateException::class.java) {
            StableComposeFrameCapture(client, maxAttempts = 2).capture("example.app")
        }

        assertTrue(error.message!!.startsWith("Target changed during Compose frame capture; try again"))
        assertTrue(error.message!!.contains("view hierarchy kept changing"))
    }

    @Test
    fun `rejects compose roots that are not views of the captured window`() {
        val client =
            object : ComposeFrameCaptureClient {
                override fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture =
                    viewCapture(rootId = 1, childId = "view:stable")

                override fun captureTree(rootViewIds: List<Long>, includeDetails: Boolean): ComposeInspectionFrame =
                    composeFrame(rootId = 999, nodeId = 1)
            }

        val error = assertThrows(IllegalStateException::class.java) {
            StableComposeFrameCapture(client, maxAttempts = 1).capture("example.app")
        }

        assertTrue(error.message!!.contains("are not views of the captured window"))
    }

    @Test
    fun `structural probe skips details and accepts observation counter changes`() {
        val first = composeFrame(1, 10)
        val second = first.copy(
            roots = first.roots.map { root ->
                root.copy(nodes = root.nodes.map { it.copy(recomposeCount = 123, skipCount = 456) })
            },
        )
        val client = FakeClient(
            views = ArrayDeque(List(3) { viewCapture(1, "view:stable") }),
            frames = ArrayDeque(listOf(first, second)),
        )

        val capture = StableComposeFrameCapture(client, maxAttempts = 1).capture("example.app")

        assertSame(second, capture.compose)
        assertEquals(listOf(false, true), client.detailRequests)
    }

    @Test
    fun `structural comparison still rejects changed bounds and child membership`() {
        val first = composeFrame(1, 10)
        for (changed in listOf(
            first.roots.single().nodes.single().copy(bounds = Bounds(1, 2, 3, 4)),
            first.roots.single().nodes.single().copy(children = first.roots.single().nodes),
        )) {
            val second = first.copy(roots = listOf(first.roots.single().copy(nodes = listOf(changed))))
            val client = FakeClient(
                views = ArrayDeque(List(3) { viewCapture(1, "view:stable") }),
                frames = ArrayDeque(listOf(first, second)),
            )
            val failure = assertThrows(IllegalStateException::class.java) {
                StableComposeFrameCapture(client, maxAttempts = 1).capture("example.app")
            }
            assertTrue(failure.message!!.contains("compose tree kept changing"))
        }
    }

    @Test
    fun `empty roots and roots without nodes exhaust bounded attempts instead of succeeding`() {
        for (empty in emptyFrames()) {
            val client = FakeClient(
                views = ArrayDeque(List(6) { viewCapture(1, "view:stable") }),
                frames = ArrayDeque(List(4) { empty }),
            )

            val failure = assertThrows(IllegalStateException::class.java) {
                StableComposeFrameCapture(client, maxAttempts = 2).capture("example.app")
            }

            assertTrue(failure.message!!.contains("Compose tree unavailable"))
            assertTrue(failure.message!!.contains("visible"))
            assertTrue(failure.message!!.contains("retry"))
            assertTrue(failure.message!!.contains("attempt 2 of 2"))
            assertEquals(6, client.viewCaptures)
            assertEquals(4, client.composeCaptures)
            assertEquals(listOf(false, true, false, true), client.detailRequests)
        }
    }

    @Test
    fun `empty responses retry to a nonempty stable frame`() {
        for (empty in emptyFrames()) {
            val full = composeFrame(1, 10)
            val client = FakeClient(
                views = ArrayDeque(List(6) { viewCapture(1, "view:stable") }),
                frames = ArrayDeque(listOf(empty, empty, full, full)),
            )

            val capture = StableComposeFrameCapture(client, maxAttempts = 2).capture("example.app")

            assertSame(full, capture.compose)
            assertEquals(6, client.viewCaptures)
            assertEquals(4, client.composeCaptures)
        }
    }

    @Test
    fun `both structural and detailed Compose reads must contain nodes`() {
        val full = composeFrame(1, 10)
        for (empty in emptyFrames()) {
            for (frames in listOf(listOf(empty, full), listOf(full, empty))) {
                val client = FakeClient(
                    views = ArrayDeque(List(3) { viewCapture(1, "view:stable") }),
                    frames = ArrayDeque(frames),
                )

                assertThrows(IllegalStateException::class.java) {
                    StableComposeFrameCapture(client, maxAttempts = 1).capture("example.app")
                }
            }
        }
    }

    @Test
    fun `rejects missing or empty roots for an expected Compose host`() {
        val views = viewCaptureWithComposeHosts(10, 20)
        val partial = composeFrame(10, 100)
        val emptyHost = partial.copy(roots = partial.roots + ComposableRoot(20, emptyList()))
        for (frame in listOf(partial, emptyHost)) {
            val client = FakeClient(
                views = ArrayDeque(List(3) { views }),
                frames = ArrayDeque(List(2) { frame }),
            )

            val failure = assertThrows(IllegalStateException::class.java) {
                StableComposeFrameCapture(client, maxAttempts = 1).capture("example.app")
            }

            assertTrue(failure.message!!.contains("Compose tree unavailable"))
            assertTrue(failure.message!!.contains("AndroidComposeView"))
            assertTrue(failure.message!!.contains("20"))
        }
    }

    @Test
    fun `retries partial hosts and accepts roots anchored below the requested window root`() {
        val views = viewCaptureWithComposeHosts(10, 20)
        val partial = composeFrame(10, 100)
        val full = partial.copy(roots = partial.roots + composeFrame(20, 200).roots)
        val client = FakeClient(
            views = ArrayDeque(List(6) { views }),
            frames = ArrayDeque(listOf(partial, partial, full, full)),
        )

        val capture = StableComposeFrameCapture(client, maxAttempts = 2).capture("example.app")

        assertSame(full, capture.compose)
        assertEquals(listOf(1L), capture.views.rootViewIds)
        assertEquals(4, client.composeCaptures)
    }

    @Test
    fun `validates Compose hosts in all effective windows`() {
        val first = viewCaptureWithComposeHosts(10)
        val second = viewCaptureWithComposeHosts(20)
        val views = first.copy(
            snapshot = first.snapshot.copy(
                windows = listOf(
                    WindowSnapshot("first", "First", bounds = first.snapshot.root.bounds, root = first.snapshot.root),
                    WindowSnapshot("second", "Second", bounds = second.snapshot.root.bounds, root = second.snapshot.root),
                ),
            ),
        )
        val partial = composeFrame(10, 100)
        val full = partial.copy(roots = partial.roots + composeFrame(20, 200).roots)
        val client = FakeClient(
            views = ArrayDeque(List(6) { views }),
            frames = ArrayDeque(listOf(partial, partial, full, full)),
        )

        assertSame(full, StableComposeFrameCapture(client, maxAttempts = 2).capture("example.app").compose)
        assertEquals(4, client.composeCaptures)
    }

    @Test
    fun `rejects anchors found only in the legacy root when explicit windows exist`() {
        val legacy = viewCaptureWithComposeHosts(10)
        val window = viewCaptureWithComposeHosts(20).snapshot.root
        val views = legacy.copy(
            snapshot = legacy.snapshot.copy(
                windows = listOf(WindowSnapshot("window", "Window", bounds = window.bounds, root = window)),
            ),
        )
        val client = FakeClient(
            views = ArrayDeque(List(3) { views }),
            frames = ArrayDeque(List(2) { composeFrame(10, 100) }),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            StableComposeFrameCapture(client, maxAttempts = 1).capture("example.app")
        }

        assertTrue(failure.message!!.contains("are not views of the captured window"))
    }

    @Test
    fun `a valid root cannot mask another root with a mismatched host anchor`() {
        val valid = composeFrame(10, 100)
        val frame = valid.copy(roots = valid.roots + composeFrame(999, 200).roots)
        val client = FakeClient(
            views = ArrayDeque(List(3) { viewCaptureWithComposeHosts(10) }),
            frames = ArrayDeque(List(2) { frame }),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            StableComposeFrameCapture(client, maxAttempts = 1).capture("example.app")
        }

        assertTrue(failure.message!!.contains("are not views of the captured window"))
        assertTrue(failure.message!!.contains("999"))
    }

    @Test
    fun `structural comparison still rejects changed node anchors and skipped views`() {
        val first = composeFrame(1, 10)
        val root = first.roots.single()
        for (changed in listOf(
            root.copy(nodes = root.nodes.map { it.copy(anchorHash = 2) }),
            root.copy(viewsToSkip = listOf(123)),
        )) {
            val client = FakeClient(
                views = ArrayDeque(List(3) { viewCapture(1, "view:stable") }),
                frames = ArrayDeque(listOf(first, first.copy(roots = listOf(changed)))),
            )

            val failure = assertThrows(IllegalStateException::class.java) {
                StableComposeFrameCapture(client, maxAttempts = 1).capture("example.app")
            }

            assertTrue(failure.message!!.contains("compose tree kept changing"))
        }
    }

    private class FakeClient(
        private val views: ArrayDeque<ViewInspectionCapture>,
        private val frames: ArrayDeque<ComposeInspectionFrame>,
    ) : ComposeFrameCaptureClient {
        var viewCaptures = 0
        var composeCaptures = 0
        val detailRequests = mutableListOf<Boolean>()

        override fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture {
            viewCaptures += 1
            return views.removeFirst()
        }

        override fun captureTree(rootViewIds: List<Long>, includeDetails: Boolean): ComposeInspectionFrame {
            detailRequests += includeDetails
            composeCaptures += 1
            return frames.removeFirst()
        }
    }

    private companion object {
        fun emptyFrames(): List<ComposeInspectionFrame> = listOf(
            composeFrame(1, 10).copy(roots = emptyList()),
            composeFrame(1, 10).copy(roots = listOf(ComposableRoot(1, emptyList()))),
        )

        fun viewCaptureWithComposeHosts(vararg hostIds: Long): ViewInspectionCapture {
            val capture = viewCapture(1, "view:stable")
            return capture.copy(
                snapshot = capture.snapshot.copy(
                    root = (capture.snapshot.root as ViewNode).copy(
                        children = hostIds.map { id ->
                            ViewNode("view:$id", "androidx.compose.ui.platform.AndroidComposeView", Bounds(0, 0, 10, 10))
                        },
                    ),
                ),
            )
        }

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
