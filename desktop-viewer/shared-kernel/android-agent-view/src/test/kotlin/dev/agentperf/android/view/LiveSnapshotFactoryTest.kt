package dev.agentperf.android.view

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ProtocolVersion
import dev.agentperf.protocol.ViewNode
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
            root = root,
        )

        assertEquals(ProtocolVersion(1, 0), snapshot.protocolVersion)
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
    fun `live snapshot converts screen bounds into screenshot local coordinates`() {
        val child = ViewNode(
            id = "title",
            className = "android.widget.TextView",
            bounds = Bounds(1640, 420, 2050, 500),
        )
        val root = ViewNode(
            id = "root",
            className = "android.view.DecorView",
            bounds = Bounds(1508, 300, 2332, 1764),
            children = listOf(child),
        )

        val snapshot = LiveSnapshotFactory.create(
            packageName = "dev.agentperf.sample",
            widthPx = 824,
            heightPx = 1464,
            density = 2f,
            capturedAtEpochMillis = 42,
            root = root,
        )

        assertEquals(Bounds(0, 0, 824, 1464), snapshot.root.bounds)
        assertEquals(Bounds(132, 120, 542, 200), snapshot.root.children.single().bounds)
    }
}
