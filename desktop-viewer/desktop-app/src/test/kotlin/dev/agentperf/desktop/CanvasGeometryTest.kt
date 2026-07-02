package dev.agentperf.desktop

import dev.agentperf.protocol.Bounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasGeometryTest {
    @Test
    fun `portrait image is contained and centered in a square canvas`() {
        val destination = CanvasGeometry.contain(
            sourceWidth = 100,
            sourceHeight = 200,
            canvasWidth = 300f,
            canvasHeight = 300f,
        )

        assertEquals(FloatRect(left = 75f, top = 0f, width = 150f, height = 300f), destination)
    }

    @Test
    fun `view bounds use the screenshot destination scale and offset`() {
        val destination = FloatRect(left = 75f, top = 0f, width = 150f, height = 300f)

        val mapped = CanvasGeometry.mapBounds(
            bounds = Bounds(left = 10, top = 20, right = 50, bottom = 100),
            sourceWidth = 100,
            sourceHeight = 200,
            destination = destination,
        )

        assertEquals(FloatRect(left = 90f, top = 30f, width = 60f, height = 120f), mapped)
    }
}
