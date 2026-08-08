package com.androidperformancestudio.application

import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimelineDiffTest {
    @Test
    fun `diff keeps repeated resource nodes stable when a sibling is inserted at the front`() {
        val previous = snapshotWithRoot(
            children = listOf(
                repeatedRow("root/0", "Alpha", Bounds(0, 0, 100, 20)),
                repeatedRow("root/1", "Beta", Bounds(0, 20, 100, 40)),
            ),
        )
        val current = snapshotWithRoot(
            children = listOf(
                repeatedRow("root/0", "New", Bounds(0, 0, 100, 20)),
                repeatedRow("root/1", "Alpha", Bounds(0, 20, 100, 40)),
                repeatedRow("root/2", "Beta", Bounds(0, 40, 100, 60)),
            ),
            capturedAtEpochMillis = 2,
        )

        val diff = diffSnapshots(previous, current)

        assertEquals(1, diff.addedNodes)
        assertEquals(0, diff.removedNodes)
        assertEquals(2, diff.boundsChangedNodes)
        assertEquals(
            listOf("root/0"),
            diff.changes.filter { it.type == TimelineChangeType.ADDED }.map { it.nodeId },
        )
        assertEquals(
            setOf("root/1", "root/2"),
            diff.changes.filter { it.type == TimelineChangeType.CHANGED }.map { it.nodeId }.toSet(),
        )
    }

    @Test
    fun `diff does not guess between indistinguishable repeated resource nodes after insertion`() {
        val previous = snapshotWithRoot(
            children = listOf(
                repeatedRow("root/0", "Same", Bounds(0, 0, 100, 20)),
                repeatedRow("root/1", "Same", Bounds(0, 20, 100, 40)),
            ),
        )
        val current = snapshotWithRoot(
            children = listOf(
                repeatedRow("root/0", "Same", Bounds(0, 0, 100, 20)),
                repeatedRow("root/1", "Same", Bounds(0, 20, 100, 40)),
                repeatedRow("root/2", "Same", Bounds(0, 40, 100, 60)),
            ),
            capturedAtEpochMillis = 2,
        )

        val diff = diffSnapshots(previous, current)

        assertEquals(3, diff.addedNodes)
        assertEquals(2, diff.removedNodes)
        assertEquals(0, diff.boundsChangedNodes)
    }

    @Test
    fun `diff matches reordered siblings by unique resource identity`() {
        val previous = snapshotWithRoot(
            children = listOf(
                ViewNode(
                    "root/0",
                    "TextView",
                    Bounds(0, 0, 100, 20),
                    resourceName = "sample:id/title",
                ),
                ViewNode(
                    "root/1",
                    "Button",
                    Bounds(0, 20, 100, 40),
                    resourceName = "sample:id/action",
                ),
            ),
        )
        val current = snapshotWithRoot(
            children = listOf(
                ViewNode(
                    "root/0",
                    "Button",
                    Bounds(0, 0, 100, 20),
                    resourceName = "sample:id/action",
                ),
                ViewNode(
                    "root/1",
                    "TextView",
                    Bounds(0, 20, 100, 40),
                    resourceName = "sample:id/title",
                ),
            ),
            capturedAtEpochMillis = 2,
        )

        val diff = diffSnapshots(previous, current)

        assertEquals(0, diff.addedNodes)
        assertEquals(0, diff.removedNodes)
        assertEquals(2, diff.boundsChangedNodes)
        assertEquals(setOf("root/0", "root/1"), diff.changes.map { it.nodeId }.toSet())
    }

    @Test
    fun `diff matches reordered compose siblings by unique test tag`() {
        val previous = snapshotWithRoot(
            children = listOf(
                composeButton("root/compose/1", "save", Bounds(0, 0, 100, 20)),
                composeButton("root/compose/2", "cancel", Bounds(0, 20, 100, 40)),
            ),
        )
        val current = snapshotWithRoot(
            children = listOf(
                composeButton("root/compose/3", "cancel", Bounds(0, 0, 100, 20)),
                composeButton("root/compose/4", "save", Bounds(0, 20, 100, 40)),
            ),
            capturedAtEpochMillis = 2,
        )

        val diff = diffSnapshots(previous, current)

        assertEquals(0, diff.addedNodes)
        assertEquals(0, diff.removedNodes)
        assertEquals(2, diff.boundsChangedNodes)
    }

    @Test
    fun `diff reports a node moved across parents as removed and added`() {
        val moved = ViewNode(
            id = "root/0/0",
            className = "TextView",
            bounds = Bounds(0, 0, 100, 20),
            resourceName = "sample:id/moved",
        )
        val previous = snapshotWithRoot(
            children = listOf(
                ViewNode(
                    "root/0",
                    "LinearLayout",
                    Bounds(0, 0, 100, 50),
                    children = listOf(moved),
                    resourceName = "sample:id/left",
                ),
                ViewNode(
                    "root/1",
                    "LinearLayout",
                    Bounds(100, 0, 200, 50),
                    resourceName = "sample:id/right",
                ),
            ),
        )
        val current = snapshotWithRoot(
            children = listOf(
                ViewNode("root/0", "LinearLayout", Bounds(0, 0, 100, 50), resourceName = "sample:id/left"),
                ViewNode(
                    "root/1",
                    "LinearLayout",
                    Bounds(100, 0, 200, 50),
                    children = listOf(moved.copy(id = "root/1/0", bounds = Bounds(100, 0, 200, 20))),
                    resourceName = "sample:id/right",
                ),
            ),
            capturedAtEpochMillis = 2,
        )

        val diff = diffSnapshots(previous, current)

        assertEquals(1, diff.addedNodes)
        assertEquals(1, diff.removedNodes)
        assertEquals(
            setOf(TimelineChangeType.ADDED, TimelineChangeType.REMOVED),
            diff.changes.filter { it.className == "TextView" }.map { it.type }.toSet(),
        )
    }

    @Test
    fun `diff uses position for weak identity only while sibling structure is stable`() {
        val previous = snapshotWithRoot(
            children = listOf(ViewNode("root/0", "TextView", Bounds(0, 0, 100, 20), text = "Before")),
        )
        val current = snapshotWithRoot(
            children = listOf(ViewNode("root/0", "TextView", Bounds(0, 0, 100, 20), text = "After")),
            capturedAtEpochMillis = 2,
        )

        val diff = diffSnapshots(previous, current)

        assertEquals(0, diff.addedNodes)
        assertEquals(0, diff.removedNodes)
        assertEquals(listOf("text"), diff.changes.single().changedProperties)
    }

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

        Assertions.assertEquals(1, store.state.timelineDiff?.addedNodes)
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
        Assertions.assertEquals(1, store.state.timelineFrames.last().diffFromPrevious?.addedNodes)
        Assertions.assertEquals(1, store.state.selectedTimelineFrameIndex)

        store.selectTimelineFrame(0)

        Assertions.assertEquals(0, store.state.selectedTimelineFrameIndex)
        Assertions.assertEquals(1, store.state.snapshot?.capturedAtEpochMillis)
        assertEquals(byteArrayOf(1).toList(), store.state.screenshotPng?.toList())
    }

    @Test
    fun `removing the selected timeline frame selects the nearest remaining frame`() {
        val store = InspectorStore()
        val first = SampleSnapshots.dashboard.copy(capturedAtEpochMillis = 1)
        val second = first.copy(capturedAtEpochMillis = 2)
        store.loadCapture(first, byteArrayOf(1))
        store.loadCapture(second, byteArrayOf(2))

        assertEquals(true, store.removeTimelineFrame(1))
        assertEquals(listOf(0), store.state.timelineFrames.map { it.index })
        assertEquals(0, store.state.selectedTimelineFrameIndex)
        assertEquals(1, store.state.snapshot?.capturedAtEpochMillis)
    }

    @Test
    fun `removing the only timeline frame clears timeline selection`() {
        val store = InspectorStore()
        store.loadCapture(SampleSnapshots.dashboard, byteArrayOf(1))

        assertEquals(true, store.removeTimelineFrame(0))
        assertEquals(emptyList<TimelineFrame>(), store.state.timelineFrames)
        assertEquals(null, store.state.selectedTimelineFrameIndex)
        assertEquals(null, store.state.timelineDiff)
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
        Assertions.assertEquals(54, store.state.selectedTimelineFrameIndex)
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
        Assertions.assertEquals(7, store.state.selectedTimelineFrameIndex)
        Assertions.assertEquals(1, store.state.timelineDiff?.addedNodes)
        assertEquals(true, store.selectTimelineFrame(7))
        Assertions.assertEquals(2_000, store.state.snapshot?.capturedAtEpochMillis)
    }

    private fun snapshotWithRoot(
        children: List<UiNode>,
        capturedAtEpochMillis: Long = 1,
    ) = SampleSnapshots.dashboard.copy(
        capturedAtEpochMillis = capturedAtEpochMillis,
        root = ViewNode(
            id = "root",
            className = "Root",
            bounds = Bounds(0, 0, 200, 200),
            children = children,
        ),
        windows = emptyList(),
        defaultWindowId = null,
    )

    private fun repeatedRow(id: String, text: String, bounds: Bounds) = ViewNode(
        id = id,
        className = "TextView",
        bounds = bounds,
        resourceName = "sample:id/row_title",
        text = text,
    )

    private fun composeButton(id: String, testTag: String, bounds: Bounds) = ComposeNode(
        id = id,
        className = "Button",
        bounds = bounds,
        semanticsRole = "Button",
        text = testTag,
        semanticProperties = mapOf("TestTag" to testTag),
    )

}
