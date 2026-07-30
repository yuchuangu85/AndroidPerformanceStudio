package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimelineScrollNavigationTest {
    @Test
    fun `shows both edge controls while timeline overflows`() {
        assertEquals(
            TimelineScrollButtons(
                visible = true,
                leftEnabled = false,
                rightEnabled = true,
            ),
            TimelineScrollNavigation.buttons(
                canScrollBackward = false,
                canScrollForward = true,
            ),
        )
        assertEquals(
            TimelineScrollButtons(
                visible = true,
                leftEnabled = true,
                rightEnabled = false,
            ),
            TimelineScrollNavigation.buttons(
                canScrollBackward = true,
                canScrollForward = false,
            ),
        )
    }

    @Test
    fun `hides edge controls when all timeline frames fit`() {
        assertEquals(
            TimelineScrollButtons(
                visible = false,
                leftEnabled = false,
                rightEnabled = false,
            ),
            TimelineScrollNavigation.buttons(
                canScrollBackward = false,
                canScrollForward = false,
            ),
        )
    }

    @Test
    fun `scroll distance moves most of one viewport in the requested direction`() {
        assertEquals(
            -800f,
            TimelineScrollNavigation.scrollDistance(
                direction = TimelineScrollDirection.LEFT,
                viewportWidthPx = 1_000,
            ),
        )
        assertEquals(
            800f,
            TimelineScrollNavigation.scrollDistance(
                direction = TimelineScrollDirection.RIGHT,
                viewportWidthPx = 1_000,
            ),
        )
    }
}
