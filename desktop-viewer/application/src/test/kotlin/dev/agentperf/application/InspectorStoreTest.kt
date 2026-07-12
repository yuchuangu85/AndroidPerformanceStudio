package dev.agentperf.application

import dev.agentperf.analysis.AnalysisReport
import dev.agentperf.analysis.Finding
import dev.agentperf.analysis.LayoutMetrics
import dev.agentperf.analysis.Severity
import dev.agentperf.fixtures.SampleSnapshots
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.DisplayInfo
import dev.agentperf.protocol.ViewNode
import dev.agentperf.protocol.WindowSnapshot
import dev.agentperf.protocol.WindowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectorStoreTest {
    @Test
    fun `loading a snapshot selects its root and publishes analysis`() {
        val store = InspectorStore()

        store.load(SampleSnapshots.dashboard)

        assertEquals("root", store.state.selectedNodeId)
        assertEquals(SampleSnapshots.dashboard, store.state.snapshot)
        assertTrue(store.state.analysis.metrics.nodeCount > 1)
    }

    @Test
    fun `selecting an existing node updates details`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }

        val changed = store.selectNode("title")

        assertTrue(changed)
        assertEquals("title", store.state.selectedNodeId)
        assertEquals("Dashboard", store.state.selectedNode?.textContent)
    }

    @Test
    fun `selecting a missing node leaves current selection intact`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }
        val previous = store.state

        val changed = store.selectNode("missing")

        assertFalse(changed)
        assertEquals(previous, store.state)
        assertNotNull(store.state.selectedNode)
    }

    @Test
    fun `loading a live capture publishes its screenshot and preserves a valid selection`() {
        val store = InspectorStore().apply {
            load(SampleSnapshots.dashboard)
            selectNode("title")
        }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

        store.loadCapture(SampleSnapshots.dashboard, png)

        assertEquals("title", store.state.selectedNodeId)
        assertArrayEquals(png, store.state.screenshotPng)
        assertEquals(ConnectionStatus.CONNECTED, store.state.connectionStatus)
    }

    @Test
    fun `manual screenshot import fills a layout-only capture and preserves selection`() {
        val store = InspectorStore().apply {
            loadCapture(SampleSnapshots.dashboard, byteArrayOf())
            selectNode("title")
        }
        val screenshot = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

        val changed = store.loadManualScreenshot(
            screenshotPng = screenshot,
            display = DisplayInfo(widthPx = 1080, heightPx = 2400, density = 2f),
        )

        assertTrue(changed)
        assertEquals("title", store.state.selectedNodeId)
        assertArrayEquals(screenshot, store.state.screenshotPng)
        assertEquals(DisplayInfo(widthPx = 1080, heightPx = 2400, density = 2f), store.state.snapshot?.display)
        assertEquals(true, store.state.snapshot?.capabilities?.screenshots)
        assertEquals(ConnectionStatus.CONNECTED, store.state.connectionStatus)
        val timelineFrame = store.state.timelineFrames.single()
        assertEquals(DisplayInfo(widthPx = 1080, heightPx = 2400, density = 2f), timelineFrame.snapshot?.display)
        assertArrayEquals(screenshot, timelineFrame.screenshotPng)
    }

    @Test
    fun `manual screenshot import requires an existing layout snapshot`() {
        val store = InspectorStore()

        val changed = store.loadManualScreenshot(byteArrayOf(1, 2, 3))

        assertFalse(changed)
        assertNull(store.state.screenshotPng)
    }

    @Test
    fun `connection lifecycle publishes recoverable status`() {
        val store = InspectorStore()

        store.connecting()
        assertEquals(ConnectionStatus.CONNECTING, store.state.connectionStatus)
        assertEquals(null, store.state.connectionError)

        store.connectionFailed("Agent unavailable")
        assertEquals(ConnectionStatus.ERROR, store.state.connectionStatus)
        assertEquals("Agent unavailable", store.state.connectionError)
    }

    @Test
    fun `reconnecting keeps the previous application capture visible while status updates`() {
        val png = byteArrayOf(1, 2, 3)
        val store = InspectorStore().apply {
            loadCapture(
                snapshot = SampleSnapshots.dashboard,
                screenshotPng = png,
            )
            selectNode("title")
        }
        val previousAnalysis = store.state.analysis

        store.connecting()

        assertEquals(SampleSnapshots.dashboard, store.state.snapshot)
        assertArrayEquals(png, store.state.screenshotPng)
        assertEquals("title", store.state.selectedNodeId)
        assertSame(previousAnalysis, store.state.analysis)
        assertEquals(ConnectionStatus.CONNECTING, store.state.connectionStatus)
        assertNull(store.state.connectionError)
    }

    @Test
    fun `stopping automatic scanning publishes disconnected status and keeps the last capture`() {
        val store = InspectorStore().apply {
            loadCapture(
                snapshot = SampleSnapshots.dashboard,
                screenshotPng = byteArrayOf(1, 2, 3),
            )
            connectionFailed("Device unavailable")
        }

        store.disconnected()

        assertEquals(ConnectionStatus.DISCONNECTED, store.state.connectionStatus)
        assertNull(store.state.connectionError)
        assertEquals(SampleSnapshots.dashboard, store.state.snapshot)
    }

    @Test
    fun `loading an archive publishes offline state and repairs selection`() {
        val store = InspectorStore().apply {
            load(SampleSnapshots.dashboard)
            selectNode("title")
        }
        val importedSnapshot = SampleSnapshots.dashboard.copy(
            root = SampleSnapshots.dashboard.root.children.last(),
        )

        store.loadArchive(importedSnapshot, byteArrayOf(1, 2, 3))

        assertEquals(ConnectionStatus.ARCHIVE, store.state.connectionStatus)
        assertEquals(importedSnapshot.root.id, store.state.selectedNodeId)
        assertTrue(store.state.analysis.metrics.nodeCount > 0)
    }

    @Test
    fun `loading an archive can preserve persisted analysis report`() {
        val store = InspectorStore()
        val report = AnalysisReport(
            metrics = LayoutMetrics(nodeCount = 99, maxDepth = 9, widestLevel = 8),
            findings = listOf(Finding("persisted", Severity.ERROR, "root", "from archive")),
        )

        store.loadArchive(SampleSnapshots.dashboard, byteArrayOf(1, 2, 3), analysis = report)

        assertEquals(ConnectionStatus.ARCHIVE, store.state.connectionStatus)
        assertEquals(report, store.state.analysis)
    }

    @Test
    fun `window switching restores each windows last selected node`() {
        val mainRoot = ViewNode(
            id = "window:main/root",
            className = "DecorView",
            bounds = Bounds(0, 0, 100, 200),
            children = listOf(
                ViewNode("window:main/title", "TextView", Bounds(0, 0, 100, 40)),
            ),
        )
        val dialogRoot = ViewNode(
            id = "window:dialog/root",
            className = "Dialog",
            bounds = Bounds(10, 20, 90, 180),
            children = listOf(
                ViewNode("window:dialog/message", "TextView", Bounds(20, 40, 80, 80)),
            ),
        )
        val snapshot = SampleSnapshots.dashboard.copy(
            root = mainRoot,
            windows = listOf(
                WindowSnapshot(
                    "window:main",
                    "MainActivity",
                    WindowType.ACTIVITY,
                    mainRoot.bounds,
                    mainRoot,
                ),
                WindowSnapshot(
                    "window:dialog",
                    "Confirm",
                    WindowType.DIALOG,
                    dialogRoot.bounds,
                    dialogRoot,
                ),
            ),
            defaultWindowId = "window:main",
        )
        val store = InspectorStore()

        store.loadCapture(snapshot, byteArrayOf(1))
        assertEquals("window:main", store.state.selectedWindowId)
        assertEquals(mainRoot, store.state.activeRoot)
        assertTrue(store.selectNode("window:main/title"))
        assertTrue(store.selectWindow("window:dialog"))
        assertEquals("window:dialog/root", store.state.selectedNodeId)
        assertTrue(store.selectNode("window:dialog/message"))
        assertTrue(store.selectWindow("window:main"))
        assertEquals("window:main/title", store.state.selectedNodeId)
        assertTrue(store.selectWindow("window:dialog"))
        assertEquals("window:dialog/message", store.state.selectedNodeId)
    }
}
