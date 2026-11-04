package com.androidperformancestudio.application

import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeCaptureEvidenceTest {
    @Test
    fun `compatible capture publishes explicit Compose warning alongside native evidence`() {
        val snapshot = SampleSnapshots.dashboard
        val screenshot = byteArrayOf(1, 2, 3)
        val warning = "Full Compose inspection is unavailable for this target"
        val store =
            InspectorStore().apply {
                connectionFailed("private diagnostic")
                fallbackToCompatibleInspection(warning)
            }

        store.loadCapture(snapshot, screenshot, composeInspectionWarning = warning)

        assertSame(snapshot, store.state.snapshot)
        assertArrayEquals(screenshot, store.state.screenshotPng)
        assertEquals(warning, store.state.composeInspectionWarning)
        assertNull(store.state.composeInspection)
        assertNull(store.state.connectionError)
        assertEquals(ConnectionStatus.CONNECTED, store.state.connectionStatus)
        assertEquals(snapshot.root.id, store.state.selectedNodeId)
        assertTrue(store.state.analysis.metrics.nodeCount > 1)
    }

    @Test
    fun `native fallback capture does not retain the previous full Compose document`() {
        val previous = SampleSnapshots.dashboard
        val inspection = fullInspection(previous)
        val store = InspectorStore().apply { loadCapture(previous, byteArrayOf(1), inspection) }
        assertSame(inspection, store.state.composeInspection)
        val native = previous.copy(capturedAtEpochMillis = previous.capturedAtEpochMillis + 1)
        val warning = "Compose inspector is unavailable for the new capture"

        store.loadCapture(native, byteArrayOf(2), composeInspectionWarning = warning)

        assertSame(native, store.state.snapshot)
        assertNull(store.state.composeInspection)
        assertEquals(warning, store.state.composeInspectionWarning)
    }

    @Test
    fun `successful full Compose capture clears the previous fallback warning`() {
        val previous = SampleSnapshots.dashboard
        val store =
            InspectorStore().apply {
                loadCapture(previous, byteArrayOf(1), composeInspectionWarning = "Compose unavailable")
            }
        val snapshot = previous.copy(capturedAtEpochMillis = previous.capturedAtEpochMillis + 1)
        val inspection = fullInspection(snapshot)

        store.loadCapture(snapshot, byteArrayOf(2), inspection)

        assertSame(snapshot, store.state.snapshot)
        assertSame(inspection, store.state.composeInspection)
        assertNull(store.state.composeInspectionWarning)
        assertEquals(ConnectionStatus.CONNECTED, store.state.connectionStatus)
    }

    @Test
    fun `native captures without Compose warnings retain their existing publication behavior`() {
        val snapshot = SampleSnapshots.dashboard
        val screenshot = byteArrayOf(1, 2, 3)
        val store =
            InspectorStore().apply {
                load(snapshot)
                selectNode("title")
            }

        store.loadCapture(snapshot, screenshot)

        assertSame(snapshot, store.state.snapshot)
        assertArrayEquals(screenshot, store.state.screenshotPng)
        assertEquals("title", store.state.selectedNodeId)
        assertEquals(ConnectionStatus.CONNECTED, store.state.connectionStatus)
        assertNull(store.state.composeInspection)
        assertNull(store.state.composeInspectionWarning)
    }

    @Test
    fun `default capture callers clear warnings instead of inheriting an earlier reason`() {
        val previous = SampleSnapshots.dashboard
        val store =
            InspectorStore().apply {
                loadCapture(previous, byteArrayOf(1), composeInspectionWarning = "Old target unavailable")
            }
        val snapshot = previous.copy(capturedAtEpochMillis = previous.capturedAtEpochMillis + 1)

        store.loadCapture(snapshot, byteArrayOf(2))

        assertSame(snapshot, store.state.snapshot)
        assertNull(store.state.composeInspection)
        assertNull(store.state.composeInspectionWarning)
    }

    @Test
    fun `warning publication preserves timeline history selection and latest capture diff`() {
        val first = SampleSnapshots.dashboard
        val second =
            first.copy(
                capturedAtEpochMillis = first.capturedAtEpochMillis + 1,
                root =
                    (first.root as ViewNode).copy(
                        children = first.root.children + ViewNode("new", "View", Bounds(0, 0, 1, 1)),
                    ),
            )
        val firstScreenshot = byteArrayOf(1)
        val secondScreenshot = byteArrayOf(2)
        val warning = "Compose inspection unavailable"
        val store =
            InspectorStore().apply {
                loadCapture(first, firstScreenshot)
                loadCapture(second, secondScreenshot, composeInspectionWarning = warning)
            }

        assertEquals(listOf(0, 1), store.state.timelineFrames.map { it.index })
        assertEquals(1, store.state.selectedTimelineFrameIndex)
        assertEquals(diffSnapshots(first, second), store.state.timelineDiff)
        assertEquals(warning, store.state.composeInspectionWarning)
        val history = store.state.timelineFrames
        assertSame(first, history[0].snapshot)
        assertSame(second, history[1].snapshot)
        assertArrayEquals(firstScreenshot, history[0].screenshotPng)
        assertArrayEquals(secondScreenshot, history[1].screenshotPng)
        assertEquals(store.state.timelineDiff, history[1].diffFromPrevious)

        assertTrue(store.selectTimelineFrame(0))

        assertSame(first, store.state.snapshot)
        assertArrayEquals(firstScreenshot, store.state.screenshotPng)
        assertSame(history, store.state.timelineFrames)
        assertEquals(0, store.state.selectedTimelineFrameIndex)
        assertNull(store.state.timelineDiff)
        assertNull(store.state.composeInspectionWarning)

        val third = second.copy(capturedAtEpochMillis = second.capturedAtEpochMillis + 1)
        store.loadCapture(third, byteArrayOf(3), composeInspectionWarning = warning)

        assertEquals(listOf(0, 1, 2), store.state.timelineFrames.map { it.index })
        assertEquals(2, store.state.selectedTimelineFrameIndex)
        assertSame(third, store.state.snapshot)
        assertEquals(diffSnapshots(second, third), store.state.timelineDiff)
        assertEquals(warning, store.state.composeInspectionWarning)
    }

    private fun fullInspection(snapshot: LayoutSnapshot) =
        ComposeInspectionDocument(
            packageName = snapshot.packageName,
            capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
            frame =
                ComposeInspectionFrame(
                    frameId = "frame-${snapshot.capturedAtEpochMillis}",
                    generation = 1,
                    mode = ComposeInspectionMode.FULL,
                    capabilities = emptyList(),
                    roots = emptyList(),
                ),
        )
}
