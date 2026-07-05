package dev.agentperf.desktop

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewBoundsOverlayTest {
    @Test
    fun `maps visible bounds in preorder while excluding selection and off-source nodes`() {
        val root = viewNode(
            id = "root",
            bounds = Bounds(left = 0, top = 0, right = 100, bottom = 100),
            children = listOf(
                viewNode(
                    id = "selected",
                    bounds = Bounds(left = 10, top = 10, right = 30, bottom = 30),
                ),
                viewNode(
                    id = "partial",
                    bounds = Bounds(left = 80, top = 20, right = 120, bottom = 40),
                ),
                viewNode(
                    id = "outside",
                    bounds = Bounds(left = 120, top = 20, right = 140, bottom = 40),
                ),
            ),
        )

        val mapped = ViewBoundsOverlay.mappedVisibleBounds(
            root = root,
            selectedNodeId = "selected",
            source = CropRect(left = 0, top = 0, width = 100, height = 100),
            destination = FloatRect(left = 0f, top = 0f, width = 200f, height = 200f),
        )

        assertEquals(
            listOf(
                FloatRect(left = 0f, top = 0f, width = 200f, height = 200f),
                FloatRect(left = 160f, top = 40f, width = 40f, height = 40f),
            ),
            mapped,
        )
    }

    @Test
    fun `ancestor visibility and alpha prune descendants but invalid bounds do not`() {
        val survivingChild = viewNode(
            id = "surviving-child",
            bounds = Bounds(left = 40, top = 40, right = 60, bottom = 60),
        )
        val root = viewNode(
            id = "root",
            bounds = Bounds(left = 0, top = 0, right = 100, bottom = 100),
            children = listOf(
                viewNode(
                    id = "invisible-parent",
                    bounds = Bounds(left = 0, top = 0, right = 20, bottom = 20),
                    visible = false,
                    children = listOf(
                        viewNode(
                            id = "invisible-child",
                            bounds = Bounds(left = 10, top = 10, right = 30, bottom = 30),
                        ),
                    ),
                ),
                viewNode(
                    id = "transparent-parent",
                    bounds = Bounds(left = 0, top = 0, right = 20, bottom = 20),
                    alpha = 0f,
                    children = listOf(
                        viewNode(
                            id = "transparent-child",
                            bounds = Bounds(left = 10, top = 10, right = 30, bottom = 30),
                        ),
                    ),
                ),
                viewNode(
                    id = "zero-width-parent",
                    bounds = Bounds(left = 20, top = 20, right = 20, bottom = 40),
                    children = listOf(survivingChild),
                ),
            ),
        )

        val mapped = ViewBoundsOverlay.mappedVisibleBounds(
            root = root,
            selectedNodeId = "root",
            source = CropRect(left = 0, top = 0, width = 100, height = 100),
            destination = FloatRect(left = 0f, top = 0f, width = 200f, height = 200f),
        )

        assertEquals(
            listOf(FloatRect(left = 80f, top = 80f, width = 40f, height = 40f)),
            mapped,
        )
    }

    private fun viewNode(
        id: String,
        bounds: Bounds,
        visible: Boolean = true,
        alpha: Float = 1f,
        children: List<UiNode> = emptyList(),
    ): ViewNode = ViewNode(
        id = id,
        className = "android.view.View",
        bounds = bounds,
        visible = visible,
        alpha = alpha,
        children = children,
    )
}
