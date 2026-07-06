package dev.agentperf.desktop

import androidx.compose.ui.geometry.Offset
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ViewAttributes
import dev.agentperf.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasHitTesterTest {
    @Test
    fun `topmost deepest visible child wins`() {
        val root = ViewNode(
            id = "root",
            className = "Root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                ViewNode("low", "View", Bounds(10, 10, 90, 90)),
                ViewNode(
                    "high",
                    "View",
                    Bounds(10, 10, 90, 90),
                    attributes = ViewAttributes(z = 2f),
                ),
            ),
        )

        assertEquals(
            listOf("high", "root"),
            CanvasHitTester.hitPath(root, Offset(50f, 50f)),
        )
    }
}
