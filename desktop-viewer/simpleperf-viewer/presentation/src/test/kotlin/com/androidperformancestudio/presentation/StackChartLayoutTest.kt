package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackChartLayoutTest {
    @Test
    fun `stack paint follows upstream floor ceil and translucent right edge geometry`() {
        val painted = firefoxStackPaintRect(Rect(10.75f, 16.3f, 30.2f, 32.3f), 16f, 1f)
        assertEquals(Rect(10f, 16f, 29.4f, 31f), painted)
        assertEquals(17f, firefoxStackPaintRect(Rect(0f, 16.5f, 20f, 33f), 16.5f, 1f).top)
    }

    @Test
    fun `layout clips both dimensions and retains original block identities`() {
        val blocks =
            listOf(
                block("before", 0, 10, 1),
                block("clipped", 10, 40, 2),
                block("below", 20, 40, 4),
                block("after", 50, 60, 2),
            )
        val index = StackChartIndex(blocks)
        val layout = index.layout(StackChartViewport(20, 50), 300f, 32f, 16f, 2)

        assertEquals(listOf(StackChartBlockId("clipped")), layout.map { it.block.id })
        assertEquals(0f, layout.single().rect.left)
        assertEquals(200f, layout.single().rect.right)
        assertEquals(0f, layout.single().rect.top)
        assertEquals(StackChartBlockId("clipped"), index.hitTest(20, 2)?.id)
        assertNull(index.hitTest(40, 2))
    }

    @Test
    fun `dense samples have a pixel bounded display without deleting source blocks`() {
        val blocks = (0 until 100_000).map { block("sample-$it", it.toLong(), it + 1L, 0) }
        val index = StackChartIndex(blocks)
        val layout = index.layout(StackChartViewport(0, 100_000), 500f, 32f, 16f, 0)

        assertTrue(layout.isNotEmpty())
        assertTrue(layout.size <= 501, "Rendered nodes must be bounded by horizontal pixels")
        assertEquals(StackChartBlockId("sample-76543"), index.hitTest(76_543, 0)?.id)
        assertEquals(100_000, blocks.size)
        val zoomed = index.layout(StackChartViewport(76_540, 76_550), 500f, 32f, 16f, 0)
        assertTrue(zoomed.any { it.block.id == StackChartBlockId("sample-76543") })
    }

    @Test
    fun `indexed hit testing preserves topmost overlap and half open boundaries`() {
        val index =
            StackChartIndex(
                listOf(block("long", 0, 100, 0), block("short", 20, 30, 0), block("next", 30, 40, 0)),
            )

        assertEquals(StackChartBlockId("long"), index.hitTest(10, 0)?.id)
        assertEquals(StackChartBlockId("short"), index.hitTest(29, 0)?.id)
        assertEquals(StackChartBlockId("next"), index.hitTest(30, 0)?.id)
        assertEquals(StackChartBlockId("long"), index.hitTest(40, 0)?.id)
        assertNull(index.hitTest(100, 0))
        val layout = index.layout(StackChartViewport(0, 100), 100f, 16f, 16f, 0)
        assertEquals(StackChartBlockId("short"), layout.single { 25f >= it.rect.left && 25f < it.rect.right }.block.id)
        assertEquals(StackChartBlockId("next"), layout.single { 35f >= it.rect.left && 35f < it.rect.right }.block.id)
    }

    @Test
    fun `navigation follows time siblings and enclosing parents without crossing gaps`() {
        val root = block("root", 0, 100, 0)
        val left = block("left", 10, 30, 1)
        val right = block("right", 40, 60, 1)
        val index = StackChartIndex(listOf(root, left, right))

        assertEquals(root, index.parent(left))
        assertEquals(left, index.child(root))
        assertEquals(right, index.sibling(left, 1))
        assertEquals(left, index.sibling(right, -1))
        assertNull(index.hitTest(35, 1))
        assertNull(index.hitTest(Offset(-1f, 4f), StackChartViewport(0, 100), 100f, 16f, 0))
    }

    @Test
    fun `equal width ticks preserve genuine timestamps for hit testing and ranges`() {
        val blocks = listOf(block("short", 0, 1, 0), block("long", 1, 100, 0))
        val scale = StackChartSameWidths(blocks)
        val viewport = StackChartViewport(0, 100)
        val layout = StackChartIndex(blocks).layout(viewport, 200f, 16f, 16f, 0, scale)

        assertEquals(listOf(100f, 100f), layout.map { it.rect.width })
        assertEquals(1L, scale.timeAtX(100f, viewport, 200f))
        assertEquals(100L, scale.timeAtX(200f, viewport, 200f))
        assertEquals(0L, blocks.first().startNanos)
        assertEquals(1L, blocks.first().endNanosExclusive)
    }

    @Test
    fun `equal width mapping round trips partial stack boundaries without flat time regions`() {
        val scale = StackChartSameWidths(listOf(block("one", 0, 10, 0), block("two", 10, 100, 0)))
        val viewport = StackChartViewport(5, 50)
        assertEquals(5L, scale.timeAtX(0f, viewport, 200f))
        assertEquals(50L, scale.timeAtX(200f, viewport, 200f))
        assertTrue(scale.timeAtX(20f, viewport, 200f) > 5L)
        assertEquals(10L, scale.timeAtX(scale.xAtTime(10, viewport, 200f), viewport, 200f))
    }

    @Test
    fun `viewport navigation preserves the mouse anchor and clamps to committed bounds`() {
        val bounds = StackChartViewport(0, 1000)
        val zoom = navigateStackChartViewport(bounds, bounds, zoomFactor = 0.5, anchorFraction = 0.25)
        assertEquals(StackChartViewport(125, 625), zoom)
        assertEquals(StackChartViewport(0, 500), navigateStackChartViewport(zoom, bounds, panFraction = -1.0))
        assertEquals(bounds, navigateStackChartViewport(zoom, bounds, zoomFactor = 10.0))
    }
}
