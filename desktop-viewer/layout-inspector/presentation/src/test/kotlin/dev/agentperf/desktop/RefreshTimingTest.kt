package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RefreshTimingTest {
    @Test
    fun `timer records elapsed milliseconds for a refresh stage`() {
        val events = mutableListOf<RefreshTimingEvent>()
        val ticks = mutableListOf(1_000_000_000L, 1_125_000_000L)
        val timer = RefreshTimer(
            refreshKind = "manual",
            sink = RefreshTimingSink(events::add),
            nanoTime = { ticks.removeFirst() },
        )

        val result = timer.measure("capture") { "frame" }

        assertEquals("frame", result)
        assertEquals(
            listOf(RefreshTimingEvent(refreshKind = "manual", stage = "capture", elapsedMillis = 125)),
            events,
        )
    }

    @Test
    fun `timer records elapsed milliseconds when a refresh stage fails`() {
        val events = mutableListOf<RefreshTimingEvent>()
        val ticks = mutableListOf(2_000_000_000L, 2_010_000_000L)
        val timer = RefreshTimer(
            refreshKind = "auto",
            sink = RefreshTimingSink(events::add),
            nanoTime = { ticks.removeFirst() },
        )

        assertThrows(IllegalStateException::class.java) {
            timer.measure("connect") { error("boom") }
        }

        assertEquals(
            listOf(RefreshTimingEvent(refreshKind = "auto", stage = "connect", elapsedMillis = 10)),
            events,
        )
    }
}
