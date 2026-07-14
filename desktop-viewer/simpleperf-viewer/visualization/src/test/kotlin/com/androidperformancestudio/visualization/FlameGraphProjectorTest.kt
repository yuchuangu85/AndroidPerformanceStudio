package com.androidperformancestudio.visualization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlameGraphProjectorTest {
    @Test
    fun `drops subpixel nodes while preserving visible flame rectangles`() {
        val nodes =
            listOf(
                FlameGraphNode("root", depth = 0, startWeight = 0, endWeight = 1_000),
                FlameGraphNode("visible", depth = 1, startWeight = 100, endWeight = 400),
                FlameGraphNode("subpixel", depth = 1, startWeight = 401, endWeight = 402),
                FlameGraphNode("outside", depth = 1, startWeight = 900, endWeight = 1_000),
            )

        val rectangles =
            FlameGraphProjector.project(
                nodes = nodes,
                viewport = WeightViewport(0, 800),
                widthPixels = 100,
                rowHeightPixels = 18f,
                minimumNodeWidthPixels = 1f,
            )

        assertEquals(listOf("root", "visible"), rectangles.map { it.label })
    }

    @Test
    fun `preserves node identity highlight and hit testing for interaction`() {
        val nodes =
            listOf(
                FlameGraphNode("root", id = 1, depth = 0, startWeight = 0, endWeight = 100),
                FlameGraphNode(
                    "renderFrame",
                    id = 2,
                    parentId = 1,
                    depth = 1,
                    startWeight = 10,
                    endWeight = 60,
                    highlighted = true,
                    path = listOf("root", "renderFrame"),
                ),
            )
        val rectangles =
            FlameGraphProjector.project(nodes, WeightViewport(0, 100), 100, 20f, 1f)

        val hit = FlameGraphProjector.hitTest(rectangles, x = 20f, y = 25f)

        assertEquals(2L, hit?.nodeId)
        assertEquals(true, hit?.highlighted)
        assertEquals(listOf("root", "renderFrame"), hit?.path)
        assertEquals(WeightViewport(10, 60), FlameGraphProjector.focus(nodes.last()))
    }

    @Test
    fun `projects large flame graphs in bounded progressive pages`() {
        val nodes =
            (0 until 10).map { index ->
                FlameGraphNode(
                    label = "node-$index",
                    id = index.toLong(),
                    depth = 0,
                    startWeight = index * 10L,
                    endWeight = (index + 1) * 10L,
                )
            }

        val first =
            FlameGraphProjector.projectPage(
                nodes = nodes,
                parameters = projectionParameters(),
                startNodeIndex = 0,
                maximumRectangles = 3,
            )
        val second =
            FlameGraphProjector.projectPage(
                nodes = nodes,
                parameters = projectionParameters(),
                startNodeIndex = first.nextNodeIndex!!,
                maximumRectangles = 20,
            )

        assertEquals(listOf("node-0", "node-1", "node-2"), first.rectangles.map { it.label })
        assertEquals(3, first.nextNodeIndex)
        assertEquals((3 until 10).map { "node-$it" }, second.rectangles.map { it.label })
        assertNull(second.nextNodeIndex)
    }

    private fun projectionParameters(): FlameProjectionParameters =
        FlameProjectionParameters(
            viewport = WeightViewport(0, 100),
            widthPixels = 100,
            rowHeightPixels = 18f,
            minimumNodeWidthPixels = 1f,
        )
}
