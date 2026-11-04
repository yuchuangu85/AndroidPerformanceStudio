package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.WindowSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeCaptureProjectionTest {
    @Test
    fun `projects real Compose nodes without changing the source frame`() {
        val capture = capture()

        val snapshot = capture.projectSnapshot(hideSystemComposables = true)

        assertEquals(
            "compose-inspection:100",
            snapshot.root.children
                .single()
                .id,
        )
        assertTrue(snapshot.root.children.single() is ComposeNode)
        assertTrue(snapshot.capabilities.composeSemantics)
        assertEquals(
            100L,
            capture.compose.roots
                .single()
                .nodes
                .single()
                .id,
        )
    }

    @Test
    fun `rejects a nonempty frame whose anchors produce no projected Compose nodes`() {
        val capture = capture()
        val mismatched =
            capture.copy(
                compose =
                    capture.compose.copy(
                        roots =
                            listOf(
                                ComposableRoot(
                                    999,
                                    capture.compose.roots
                                        .single()
                                        .nodes,
                                ),
                            ),
                    ),
            )

        val failure =
            assertThrows(IllegalStateException::class.java) {
                mismatched.projectSnapshot(hideSystemComposables = false)
            }

        assertTrue(failure.message!!.contains("Compose tree unavailable"))
        assertTrue(failure.message!!.contains("projection"))
        assertTrue(failure.message!!.contains("retry"))
    }

    @Test
    fun `system filtering cannot silently publish a native-only Compose capture`() {
        val capture = capture(systemCreated = true)

        val failure =
            assertThrows(IllegalStateException::class.java) {
                capture.projectSnapshot(hideSystemComposables = true)
            }

        assertTrue(failure.message!!.contains("system composables"))
        val snapshot = capture.projectSnapshot(hideSystemComposables = false)
        assertTrue(snapshot.root.children.single() is ComposeNode)
    }

    @Test
    fun `hiding a system wrapper retains its application Compose descendants`() {
        val capture = capture(systemCreated = true)
        val root = capture.compose.roots.single()
        val wrapper = root.nodes.single()
        val parent = wrapper.copy(children = listOf(wrapper.copy(id = 101, systemCreated = false)))
        val frame = capture.compose.copy(roots = listOf(root.copy(nodes = listOf(parent))))

        val snapshot = capture.copy(compose = frame).projectSnapshot(hideSystemComposables = true)

        assertEquals(
            "compose-inspection:101",
            snapshot.root.children
                .single()
                .id,
        )
        assertTrue(snapshot.root.children.single() is ComposeNode)
    }

    @Test
    fun `finds projected Compose nodes outside the default window`() {
        val capture = capture()
        val nativeRoot = ViewNode("view:2", "NativeWindow", Bounds(0, 0, 100, 100))
        val views =
            capture.views.copy(
                snapshot =
                    capture.views.snapshot.copy(
                        root = nativeRoot,
                        defaultWindowId = "native",
                        windows =
                            listOf(
                                WindowSnapshot("native", "Native", bounds = nativeRoot.bounds, root = nativeRoot),
                                WindowSnapshot("compose", "Compose", bounds = nativeRoot.bounds, root = capture.views.snapshot.root),
                            ),
                    ),
            )

        val snapshot = capture.copy(views = views).projectSnapshot(hideSystemComposables = true)

        assertEquals(nativeRoot, snapshot.root)
        assertTrue(
            snapshot.windows
                .last()
                .root.children
                .single() is ComposeNode,
        )
    }

    @Test
    fun `a projected outer root cannot mask another dropped Compose root`() {
        val capture = capture()
        val root = capture.compose.roots.single()
        val partial =
            capture.copy(
                compose =
                    capture.compose.copy(
                        roots = listOf(root, ComposableRoot(999, listOf(root.nodes.single().copy(id = 200)))),
                    ),
            )

        val failure =
            assertThrows(IllegalStateException::class.java) {
                partial.projectSnapshot(hideSystemComposables = false)
            }

        assertTrue(failure.message!!.contains("projection"))
        assertTrue(failure.message!!.contains("200"))
    }

    private fun capture(systemCreated: Boolean = false): StableComposeCapture {
        val bounds = Bounds(0, 0, 100, 100)
        return StableComposeCapture(
            views =
                ViewInspectionCapture(
                    snapshot =
                        LayoutSnapshot(
                            protocolVersion = CURRENT_PROTOCOL_VERSION,
                            packageName = "example.app",
                            capturedAtEpochMillis = 1,
                            display = DisplayInfo(100, 100, 1f),
                            capabilities = AgentCapabilities(viewHierarchy = true),
                            root = ViewNode("view:1", "androidx.compose.ui.platform.AndroidComposeView", bounds),
                        ),
                    rootViewIds = listOf(1),
                ),
            compose =
                ComposeInspectionFrame(
                    frameId = "frame",
                    generation = 1,
                    mode = ComposeInspectionMode.FULL,
                    capabilities = emptyList(),
                    roots =
                        listOf(
                            ComposableRoot(1, listOf(ComposableNode(100, 1, "Content", bounds, systemCreated = systemCreated))),
                        ),
                ),
        )
    }
}
