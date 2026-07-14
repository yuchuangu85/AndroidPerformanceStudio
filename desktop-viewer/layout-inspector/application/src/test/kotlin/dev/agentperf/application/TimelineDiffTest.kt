package dev.agentperf.application

import dev.agentperf.fixtures.SampleSnapshots
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ComposeNode
import dev.agentperf.protocol.ViewAttributes
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
    fun `diff records concrete node changes and changed properties`() {
        val previous = SampleSnapshots.dashboard.copy(
            root = ViewNode(
                id = "root",
                className = "Root",
                bounds = Bounds(0, 0, 100, 100),
                children = listOf(
                    ViewNode(
                        id = "changed",
                        className = "TextView",
                        bounds = Bounds(0, 0, 10, 10),
                        visible = true,
                        alpha = 1f,
                        text = "Before",
                        attributes = ViewAttributes(contentDescription = "old"),
                    ),
                    ComposeNode(
                        id = "compose",
                        className = "ComposeSemantics",
                        bounds = Bounds(20, 20, 30, 30),
                        semanticsRole = "Button",
                        text = "Save",
                        semanticProperties = mapOf("TestTag" to "save"),
                    ),
                    ViewNode("removed", "View", Bounds(40, 40, 50, 50)),
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
                    ViewNode(
                        id = "changed",
                        className = "Button",
                        bounds = Bounds(1, 0, 11, 10),
                        visible = false,
                        alpha = 0.5f,
                        text = "After",
                        attributes = ViewAttributes(contentDescription = "new"),
                    ),
                    ComposeNode(
                        id = "compose",
                        className = "ComposeSemantics",
                        bounds = Bounds(20, 20, 30, 30),
                        semanticsRole = "Checkbox",
                        text = "Save",
                        semanticProperties = mapOf("TestTag" to "save_changed"),
                    ),
                    ViewNode("added", "View", Bounds(60, 60, 70, 70)),
                ),
            ),
        )

        val diff = diffSnapshots(previous, current)

        assertEquals(1, diff.addedNodes)
        assertEquals(1, diff.removedNodes)
        assertEquals(1, diff.boundsChangedNodes)
        assertEquals(4, diff.changes.size)
        assertEquals(TimelineChangeType.ADDED, diff.changes.single { it.nodeId == "added" }.type)
        assertEquals(TimelineChangeType.REMOVED, diff.changes.single { it.nodeId == "removed" }.type)
        assertEquals(
            listOf("alpha", "bounds", "className", "contentDescription", "text", "visible"),
            diff.changes.single { it.nodeId == "changed" }.changedProperties,
        )
        assertEquals(
            listOf("semanticProperties", "semanticsRole"),
            diff.changes.single { it.nodeId == "compose" }.changedProperties,
        )
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
    @Test
    fun `store keeps capped live timeline history and can select a historical frame`() {
        val store = InspectorStore()
        val first = SampleSnapshots.dashboard.copy(capturedAtEpochMillis = 1)
        val second = first.copy(
            capturedAtEpochMillis = 2,
            root = (first.root as ViewNode).copy(
                children = first.root.children + ViewNode("new", "View", Bounds(0, 0, 1, 1)),
            ),
        )

        store.loadCapture(first, byteArrayOf(1))
        store.loadCapture(second, byteArrayOf(2))

        assertEquals(2, store.state.timelineFrames.size)
        assertEquals(0, store.state.timelineFrames.first().index)
        assertEquals(1, store.state.timelineFrames.last().index)
        assertEquals(1, store.state.timelineFrames.last().diffFromPrevious?.addedNodes)
        assertEquals(1, store.state.selectedTimelineFrameIndex)

        store.selectTimelineFrame(0)

        assertEquals(0, store.state.selectedTimelineFrameIndex)
        assertEquals(1, store.state.snapshot?.capturedAtEpochMillis)
        assertEquals(byteArrayOf(1).toList(), store.state.screenshotPng?.toList())
    }

    @Test
    fun `store caps timeline history to the most recent fifty frames`() {
        val store = InspectorStore()

        repeat(55) { index ->
            store.loadCapture(
                SampleSnapshots.dashboard.copy(capturedAtEpochMillis = index.toLong()),
                byteArrayOf(index.toByte()),
            )
        }

        assertEquals(50, store.state.timelineFrames.size)
        assertEquals(5, store.state.timelineFrames.first().index)
        assertEquals(54, store.state.timelineFrames.last().index)
        assertEquals(54, store.state.selectedTimelineFrameIndex)
    }

    @Test
    fun `loading archive restores lightweight timeline history and anchors current frame`() {
        val store = InspectorStore()
        val snapshot = SampleSnapshots.dashboard.copy(capturedAtEpochMillis = 2_000)
        val frame = TimelineFrame(
            index = 7,
            capturedAtEpochMillis = 2_000,
            diffFromPrevious = TimelineDiff(
                previousCapturedAtEpochMillis = 1_000,
                currentCapturedAtEpochMillis = 2_000,
                addedNodes = 1,
                removedNodes = 0,
                boundsChangedNodes = 0,
            ),
        )

        store.loadArchive(snapshot, byteArrayOf(9), timelineFrames = listOf(frame))

        assertEquals(1, store.state.timelineFrames.size)
        assertEquals(7, store.state.selectedTimelineFrameIndex)
        assertEquals(1, store.state.timelineDiff?.addedNodes)
        assertEquals(true, store.selectTimelineFrame(7))
        assertEquals(2_000, store.state.snapshot?.capturedAtEpochMillis)
    }

}
