package dev.agentperf.desktop

import dev.agentperf.application.InspectorStore
import dev.agentperf.fixtures.SampleSnapshots
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ViewNode
import dev.agentperf.protocol.WindowSnapshot
import dev.agentperf.protocol.WindowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasWindowSourceTest {
    @Test
    fun `window switching changes the app-only screenshot crop to the active window bounds`() {
        val mainRoot = ViewNode(
            id = "window:main/root",
            className = "DecorView",
            bounds = Bounds(0, 0, 1080, 2400),
        )
        val dialogRoot = ViewNode(
            id = "window:dialog/root",
            className = "DialogDecorView",
            bounds = Bounds(0, 0, 1080, 2400),
        )
        val snapshot = SampleSnapshots.dashboard.copy(
            root = mainRoot,
            windows = listOf(
                WindowSnapshot(
                    id = "window:main",
                    title = "MainActivity",
                    type = WindowType.ACTIVITY,
                    bounds = Bounds(0, 0, 1080, 2400),
                    root = mainRoot,
                ),
                WindowSnapshot(
                    id = "window:dialog",
                    title = "Confirm",
                    type = WindowType.DIALOG,
                    bounds = Bounds(220, 640, 860, 1280),
                    root = dialogRoot,
                ),
            ),
            defaultWindowId = "window:main",
        )
        val store = InspectorStore().apply {
            loadCapture(snapshot, byteArrayOf(1))
        }

        val mainSource = CanvasWindowSource.sourceRect(store.state, appOnly = true)
        store.selectWindow("window:dialog")
        val dialogSource = CanvasWindowSource.sourceRect(store.state, appOnly = true)

        assertEquals(CropRect(0, 0, 1080, 2400), mainSource)
        assertEquals(CropRect(220, 640, 640, 640), dialogSource)
    }
}
