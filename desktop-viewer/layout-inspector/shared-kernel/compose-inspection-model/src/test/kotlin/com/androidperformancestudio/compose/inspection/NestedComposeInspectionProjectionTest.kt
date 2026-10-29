package com.androidperformancestudio.compose.inspection

import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.PROTOCOL_VERSION_1_1
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NestedComposeInspectionProjectionTest {
    @Test
    fun `merges nested Compose hosts in retained native children`() {
        val (snapshot, frame) = fixture(hosted = false)

        val merged = ComposeInspectionProjection.mergeInto(snapshot, frame, hideSystemComposables = false)

        assertEquals(listOf("compose-inspection:100", "view:20"), merged.root.children.map { it.id })
        assertEquals(
            listOf("view:30", "view:40"),
            merged.root
                .node("view:20")
                .children
                .map { it.id },
        )
        assertEquals(
            listOf("compose-inspection:200"),
            merged.root
                .node("view:30")
                .children
                .map { it.id },
        )
        assertProjection(snapshot, merged, setOf("compose-inspection:100", "compose-inspection:200"))
        assertTrue(
            snapshot.root
                .node("view:30")
                .children
                .isEmpty(),
        )
    }

    @Test
    fun `merges nested Compose hosts in hosted AndroidView subtrees without duplicating ownership`() {
        val (snapshot, frame) = fixture(hosted = true)

        val merged = ComposeInspectionProjection.mergeInto(snapshot, frame, hideSystemComposables = false)

        val androidView = merged.root.children.single()
        assertEquals("compose-inspection:100", androidView.id)
        assertEquals(listOf("view:20"), androidView.children.map { it.id })
        assertEquals(
            listOf("view:30", "view:40"),
            androidView.children
                .single()
                .children
                .map { it.id },
        )
        assertEquals(
            listOf("compose-inspection:200"),
            merged.root
                .node("view:30")
                .children
                .map { it.id },
        )
        assertProjection(snapshot, merged, setOf("compose-inspection:100", "compose-inspection:200"))
    }

    @Test
    fun `system filtering promotes native subtrees and nested application descendants`() {
        for (hosted in listOf(false, true)) {
            val (snapshot, frame) = fixture(hosted, systemCreated = true)

            val shown = ComposeInspectionProjection.mergeInto(snapshot, frame, hideSystemComposables = false)
            val hidden = ComposeInspectionProjection.mergeInto(snapshot, frame, hideSystemComposables = true)

            assertEquals(
                listOf("compose-inspection:201"),
                hidden.root
                    .node("view:30")
                    .children
                    .map { it.id },
            )
            assertEquals(listOf("view:20"), hidden.root.children.map { it.id })
            assertEquals(
                listOf("view:30", "view:40"),
                hidden.root
                    .node("view:20")
                    .children
                    .map { it.id },
            )
            assertProjection(
                snapshot,
                shown,
                setOf("compose-inspection:100", "compose-inspection:200", "compose-inspection:201"),
            )
            assertProjection(snapshot, hidden, setOf("compose-inspection:201"))
        }
    }

    @Test
    fun `rejects a composable hosting its own Compose host`() {
        val (snapshot, frame) = fixture(hosted = true)
        val outer = frame.roots.first()
        val cyclic =
            frame.copy(
                roots = listOf(outer.copy(nodes = listOf(outer.nodes.single().copy(hostedViewId = 10)))) + frame.roots.drop(1),
            )

        assertCycleRejected(snapshot, cyclic)
    }

    @Test
    fun `rejects a hosted native ancestor even when it is not a Compose host`() {
        val (snapshot, frame) = fixture(hosted = true)
        val ancestor = ViewNode("view:1", "android.widget.FrameLayout", BOUNDS, children = listOf(snapshot.root))
        val outer = frame.roots.first()
        val cyclic =
            frame.copy(
                roots = listOf(outer.copy(nodes = listOf(outer.nodes.single().copy(hostedViewId = 1)))) + frame.roots.drop(1),
            )

        assertCycleRejected(snapshot.copy(root = ancestor), cyclic)
    }

    @Test
    fun `rejects cycles through nested Compose roots including filtered system nodes`() {
        for (systemCreated in listOf(false, true)) {
            val (snapshot, frame) = fixture(hosted = true, systemCreated = systemCreated)
            val inner = frame.roots.last()
            val cyclic =
                frame.copy(
                    roots =
                        frame.roots.dropLast(1) +
                            inner.copy(
                                nodes = listOf(inner.nodes.single().copy(hostedViewId = 10)),
                            ),
                )

            assertCycleRejected(snapshot, cyclic)
        }
    }

    private fun assertCycleRejected(
        snapshot: LayoutSnapshot,
        frame: ComposeInspectionFrame,
    ) {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ComposeInspectionProjection.mergeInto(snapshot, frame)
            }
        assertTrue(failure.message.orEmpty().contains("Cyclic"))
    }

    private fun assertProjection(
        original: LayoutSnapshot,
        merged: LayoutSnapshot,
        composeIds: Set<String>,
    ) {
        val nodes = merged.root.flatten()
        assertEquals(nodes.size, nodes.map { it.id }.toSet().size, "Projected IDs must not be duplicated")
        assertEquals(composeIds, nodes.filterIsInstance<ComposeNode>().map { it.id }.toSet())
        val nativeNodes = original.root.flatten().filterIsInstance<ViewNode>()
        assertEquals(nativeNodes.map { it.id }.toSet(), nodes.filterIsInstance<ViewNode>().map { it.id }.toSet())
        for (native in nativeNodes) {
            val projected = merged.root.node(native.id)
            assertEquals(native.copy(children = projected.children), projected)
        }
        for (compose in nodes.filterIsInstance<ComposeNode>()) {
            assertEquals(BOUNDS, compose.bounds)
        }
    }

    @Suppress("LongMethod")
    private fun fixture(
        hosted: Boolean,
        systemCreated: Boolean = false,
    ): Pair<LayoutSnapshot, ComposeInspectionFrame> {
        val innerHost = ViewNode("view:30", "androidx.compose.ui.platform.AndroidComposeView", BOUNDS)
        val retained = ViewNode("view:40", "android.widget.Button", Bounds(5, 6, 20, 30), text = "Retained")
        val native =
            ViewNode(
                id = "view:20",
                className = "android.widget.FrameLayout",
                bounds = Bounds(1, 2, 90, 95),
                alpha = 0.75f,
                children = listOf(innerHost, retained),
                resourceName = "sample:id/native_container",
                attributes = ViewAttributes(contentDescription = "Native interop"),
            )
        val outerHost =
            ViewNode(
                "view:10",
                "androidx.compose.ui.platform.AndroidComposeView",
                BOUNDS,
                children = listOf(native),
            )
        val snapshot =
            LayoutSnapshot(
                protocolVersion = PROTOCOL_VERSION_1_1,
                packageName = "sample",
                capturedAtEpochMillis = 10,
                display = DisplayInfo(100, 100, 1f),
                capabilities = AgentCapabilities(viewHierarchy = true),
                root = outerHost,
            )
        val frame =
            ComposeInspectionFrame(
                frameId = "nested",
                generation = 1,
                mode = ComposeInspectionMode.FULL,
                capabilities = emptyList(),
                roots =
                    listOf(
                        ComposableRoot(
                            viewId = 10,
                            nodes =
                                listOf(
                                    ComposableNode(
                                        id = 100,
                                        anchorHash = 100,
                                        name = if (hosted) "AndroidView" else "OuterContent",
                                        bounds = BOUNDS,
                                        hostedViewId = 20L.takeIf { hosted },
                                        systemCreated = systemCreated,
                                    ),
                                ),
                            viewsToSkip = if (hosted) listOf(20) else emptyList(),
                        ),
                        ComposableRoot(
                            viewId = 30,
                            nodes =
                                listOf(
                                    ComposableNode(
                                        id = 200,
                                        anchorHash = 200,
                                        name = "NestedContent",
                                        bounds = BOUNDS,
                                        systemCreated = systemCreated,
                                        children =
                                            if (systemCreated) {
                                                listOf(
                                                    ComposableNode(201, 201, "NestedApp", BOUNDS),
                                                )
                                            } else {
                                                emptyList()
                                            },
                                    ),
                                ),
                        ),
                    ),
            )
        return snapshot to frame
    }

    private fun UiNode.flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun UiNode.node(id: String): UiNode = flatten().single { it.id == id }

    private companion object {
        val BOUNDS = Bounds(0, 0, 100, 100)
    }
}
