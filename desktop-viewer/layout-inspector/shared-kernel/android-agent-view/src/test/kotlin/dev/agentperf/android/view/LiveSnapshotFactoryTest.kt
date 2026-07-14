package dev.agentperf.android.view

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ComposeNode
import dev.agentperf.protocol.ProtocolVersion
import dev.agentperf.protocol.ViewNode
import dev.agentperf.protocol.WindowSnapshot
import dev.agentperf.protocol.WindowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveSnapshotFactoryTest {
    @Test
    fun `live snapshot describes the synchronized screenshot coordinate space`() {
        val root = ViewNode(
            id = "root",
            className = "android.view.DecorView",
            bounds = Bounds(0, 0, 1240, 2772),
        )

        val snapshot = LiveSnapshotFactory.create(
            packageName = "dev.agentperf.sample",
            widthPx = 1240,
            heightPx = 2772,
            density = 3.5f,
            capturedAtEpochMillis = 42,
            windows = listOf(
                WindowSnapshot(
                    id = "window:main",
                    title = "Main",
                    type = WindowType.ACTIVITY,
                    bounds = root.bounds,
                    root = root,
                ),
            ),
            defaultWindowId = "window:main",
        )

        assertEquals(ProtocolVersion(1, 1), snapshot.protocolVersion)
        assertEquals("dev.agentperf.sample", snapshot.packageName)
        assertEquals(1240, snapshot.display.widthPx)
        assertEquals(2772, snapshot.display.heightPx)
        assertEquals(3.5f, snapshot.display.density)
        assertTrue(snapshot.capabilities.viewHierarchy)
        assertTrue(snapshot.capabilities.screenshots)
        assertFalse(snapshot.capabilities.composeSemantics)
        assertEquals(root, snapshot.root)
    }

    @Test
    fun `live snapshot advertises compose semantics when windows contain compose nodes`() {
        val root = ViewNode(
            id = "root",
            className = "androidx.compose.ui.platform.AndroidComposeView",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                ComposeNode(
                    id = "compose:1",
                    className = "Button",
                    bounds = Bounds(0, 0, 100, 50),
                    semanticsRole = "Button",
                    text = "Save",
                ),
            ),
        )

        val snapshot = LiveSnapshotFactory.create(
            packageName = "dev.agentperf.sample",
            widthPx = 100,
            heightPx = 100,
            density = 1f,
            capturedAtEpochMillis = 42,
            windows = listOf(
                WindowSnapshot(
                    id = "window:main",
                    title = "Main",
                    type = WindowType.ACTIVITY,
                    bounds = root.bounds,
                    root = root,
                ),
            ),
            defaultWindowId = "window:main",
        )

        assertTrue(snapshot.capabilities.composeSemantics)
    }

    @Test
    fun `live snapshot preserves screen coordinates for every window`() {
        val child = ViewNode(
            id = "window:main/root/0",
            className = "android.widget.TextView",
            bounds = Bounds(1640, 420, 2050, 500),
        )
        val root = ViewNode(
            id = "window:main/root",
            className = "android.view.DecorView",
            bounds = Bounds(1508, 300, 2332, 1764),
            children = listOf(child),
        )

        val snapshot = LiveSnapshotFactory.create(
            packageName = "dev.agentperf.sample",
            widthPx = 3840,
            heightPx = 2160,
            density = 2f,
            capturedAtEpochMillis = 42,
            windows = listOf(
                WindowSnapshot(
                    id = "window:main",
                    title = "Main",
                    type = WindowType.ACTIVITY,
                    bounds = root.bounds,
                    root = root,
                ),
            ),
            defaultWindowId = "window:main",
        )

        assertEquals(Bounds(1508, 300, 2332, 1764), snapshot.root.bounds)
        assertEquals(Bounds(1640, 420, 2050, 500), snapshot.root.children.single().bounds)
        assertEquals("window:main", snapshot.defaultWindowId)
    }
}
