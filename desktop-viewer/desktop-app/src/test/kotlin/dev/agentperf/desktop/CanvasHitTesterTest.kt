package dev.agentperf.desktop

import androidx.compose.ui.geometry.Offset
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ViewAttributes
import dev.agentperf.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanvasHitTesterTest {
    @Test
    fun `small area candidates prefer the most specific node`() {
        val root = ViewNode(
            id = "root",
            className = "Root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                ViewNode("large", "View", Bounds(0, 0, 100, 100), attributes = ViewAttributes(z = 3f)),
                ViewNode("small", "View", Bounds(45, 45, 55, 55)),
            ),
        )

        assertEquals(
            listOf("small", "large", "root"),
            CanvasHitTester.hitCandidates(
                root = root,
                point = Offset(50f, 50f),
                order = CanvasHitTestOrder.SMALL_AREA_FIRST,
            ),
        )
    }

    @Test
    fun `z order candidates prefer the painted top node`() {
        val root = ViewNode(
            id = "root",
            className = "Root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                ViewNode("low", "View", Bounds(0, 0, 100, 100)),
                ViewNode("high", "View", Bounds(0, 0, 100, 100), attributes = ViewAttributes(z = 2f)),
            ),
        )

        assertEquals(
            listOf("high", "low", "root"),
            CanvasHitTester.hitCandidates(
                root = root,
                point = Offset(50f, 50f),
                order = CanvasHitTestOrder.Z_ORDER,
            ),
        )
    }

    @Test
    fun `hidden layer is skipped so lower layers can receive focus`() {
        val root = ViewNode(
            id = "root",
            className = "Root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                ViewNode("under", "View", Bounds(10, 10, 90, 90)),
                ViewNode("cover", "View", Bounds(10, 10, 90, 90), attributes = ViewAttributes(z = 5f)),
            ),
        )

        val candidates = CanvasHitTester.hitCandidates(
            root = root,
            point = Offset(50f, 50f),
            hiddenNodeIds = setOf("cover"),
            order = CanvasHitTestOrder.Z_ORDER,
        )

        assertEquals(listOf("under", "root"), candidates)
        assertTrue("cover" !in candidates)
    }

    @Test
    fun `hidden parent prunes descendants from hit candidates`() {
        val root = ViewNode(
            id = "root",
            className = "Root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                ViewNode(
                    id = "hidden-parent",
                    className = "View",
                    bounds = Bounds(0, 0, 100, 100),
                    children = listOf(ViewNode("hidden-child", "View", Bounds(40, 40, 60, 60))),
                ),
                ViewNode("under", "View", Bounds(0, 0, 100, 100)),
            ),
        )

        assertEquals(
            listOf("under", "root"),
            CanvasHitTester.hitCandidates(
                root = root,
                point = Offset(50f, 50f),
                hiddenNodeIds = setOf("hidden-parent"),
                order = CanvasHitTestOrder.SMALL_AREA_FIRST,
            ),
        )
    }

    @Test
    fun `topmost deepest visible child wins`() {
        val root = ViewNode(
            id = "root",
            className = "Root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                ViewNode("low", "View", Bounds(10, 10, 90, 90)),
                ViewNode(
                    "high",
                    "View",
                    Bounds(10, 10, 90, 90),
                    attributes = ViewAttributes(z = 2f),
                ),
            ),
        )

        assertEquals(
            listOf("high", "root"),
            CanvasHitTester.hitPath(root, Offset(50f, 50f)),
        )
    }
}
