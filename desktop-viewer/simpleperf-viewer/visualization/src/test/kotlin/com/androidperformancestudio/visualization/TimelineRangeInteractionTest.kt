package com.androidperformancestudio.visualization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineRangeInteractionTest {
    @Test
    fun `drag publishes distinct preview and commits the final range`() {
        val interaction = TimelineRangeInteraction(TimeViewport(100, 200), widthPx = 100f)

        assertEquals(TimeViewport(120, 121), interaction.start(20f))
        assertEquals(TimeViewport(120, 180), interaction.drag(80f))
        assertNull(interaction.drag(80f))
        assertEquals(TimeViewport(120, 180), interaction.commit())
        assertNull(interaction.preview)
    }

    @Test
    fun `cancel clears preview without committing`() {
        val interaction = TimelineRangeInteraction(TimeViewport(0, 100), widthPx = 100f)
        interaction.start(10f)
        interaction.drag(70f)

        assertEquals(TimeViewport(10, 70), interaction.preview)
        interaction.cancel()

        assertNull(interaction.preview)
        assertNull(interaction.commit())
    }

    @Test
    fun `invalid geometry never publishes a range`() {
        val interaction = TimelineRangeInteraction(TimeViewport(0, 100), widthPx = 0f)

        assertNull(interaction.start(10f))
        assertNull(interaction.drag(20f))
        assertNull(interaction.commit())
    }
}
