package dev.agentperf.application

import dev.agentperf.fixtures.SampleSnapshots
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimelineDiffTest {
    @Test
    fun `diff counts added removed and bounds changed nodes`() {
        val previous = SampleSnapshots.dashboard.copy(
            root = ViewNode(
                id = "root",
                className = "Root",
                bounds = Bounds(0, 0, 100, 100),
                children = listOf(
                    ViewNode("stable", "Text", Bounds(0, 0, 10, 10)),
                    ViewNode("moved", "Text", Bounds(10, 10, 20, 20)),
                    ViewNode("removed", "Text", Bounds(20, 20, 30, 30)),
                ),
            ),
        )
        val current = previous.copy(
            capturedAtEpochMillis = previous.capturedAtEpochMillis + 1_000,
            root = ViewNode(
                id = "root",
                className = "Root",
                bounds = Bounds(0, 0, 100, 100),
                children = listOf(
                    ViewNode("stable", "Text", Bounds(0, 0, 10, 10)),
                    ViewNode("moved", "Text", Bounds(12, 10, 22, 20)),
                    ViewNode("added", "Text", Bounds(40, 40, 50, 50)),
                ),
            ),
        )

        val diff = diffSnapshots(previous, current)

        assertEquals(1, diff.addedNodes)
        assertEquals(1, diff.removedNodes)
        assertEquals(1, diff.boundsChangedNodes)
        assertEquals(previous.capturedAtEpochMillis, diff.previousCapturedAtEpochMillis)
        assertEquals(current.capturedAtEpochMillis, diff.currentCapturedAtEpochMillis)
    }

    @Test
    fun `store publishes live timeline diff against previous capture`() {
        val store = InspectorStore()
        val first = SampleSnapshots.dashboard
        val second = first.copy(
            capturedAtEpochMillis = first.capturedAtEpochMillis + 1,
            root = (first.root as ViewNode).copy(
                children = first.root.children + ViewNode("new", "View", Bounds(0, 0, 1, 1)),
            ),
        )

        store.loadCapture(first, byteArrayOf(1))
        assertEquals(null, store.state.timelineDiff)

        store.loadCapture(second, byteArrayOf(2))

        assertEquals(1, store.state.timelineDiff?.addedNodes)
    }
}
