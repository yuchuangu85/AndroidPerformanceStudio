package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FindingsLayoutTest {
    @Test
    fun `finding rows use compact typography to show more content`() {
        assertEquals(10f, FindingsTypography.TEXT_SIZE_SP)
        assertEquals(12f, FindingsTypography.LINE_HEIGHT_SP)
        assertEquals(2f, FindingsTypography.VERTICAL_PADDING_DP)
    }

    @Test
    fun `default height is half the previous findings height`() {
        assertEquals(89f, FindingsLayout.DEFAULT_HEIGHT_DP)
        assertEquals(89f, FindingsLayout.fit(FindingsLayout.DEFAULT_HEIGHT_DP, 800f))
    }

    @Test
    fun `dragging upward grows findings and dragging downward shrinks it`() {
        assertEquals(129f, FindingsLayout.drag(89f, deltaDp = -40f, availableHeightDp = 800f))
        assertEquals(56f, FindingsLayout.drag(89f, deltaDp = 40f, availableHeightDp = 800f))
    }

    @Test
    fun `height clamps to minimum and half the available content`() {
        assertEquals(56f, FindingsLayout.drag(89f, deltaDp = 1_000f, availableHeightDp = 600f))
        assertEquals(300f, FindingsLayout.drag(89f, deltaDp = -1_000f, availableHeightDp = 600f))
    }

    @Test
    fun `fitting remembered height reacts to window shrink`() {
        assertEquals(200f, FindingsLayout.fit(400f, availableHeightDp = 400f))
        assertEquals(56f, FindingsLayout.fit(89f, availableHeightDp = 100f))
    }
}
