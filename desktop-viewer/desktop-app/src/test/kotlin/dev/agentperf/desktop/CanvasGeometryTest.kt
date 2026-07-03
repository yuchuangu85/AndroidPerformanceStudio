package dev.agentperf.desktop

import dev.agentperf.protocol.Bounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `app-only source uses clamped root bounds`() {
        assertEquals(
            CropRect(left = 1508, top = 300, width = 824, height = 1464),
            CanvasGeometry.sourceRect(
                appBounds = Bounds(left = 1508, top = 300, right = 2332, bottom = 1764),
                displayWidth = 3840,
                displayHeight = 2160,
                appOnly = true,
            ),
        )
        assertEquals(
            CropRect(left = 0, top = 100, width = 3840, height = 2060),
            CanvasGeometry.sourceRect(
                appBounds = Bounds(left = -50, top = 100, right = 4000, bottom = 2300),
                displayWidth = 3840,
                displayHeight = 2160,
                appOnly = true,
            ),
        )
    }

    @Test
    fun `invalid app bounds and full-device mode use the complete display`() {
        val fullDisplay = CropRect(left = 0, top = 0, width = 3840, height = 2160)

        assertEquals(
            fullDisplay,
            CanvasGeometry.sourceRect(
                appBounds = Bounds(left = 4000, top = 100, right = 4200, bottom = 500),
                displayWidth = 3840,
                displayHeight = 2160,
                appOnly = true,
            ),
        )
        assertEquals(
            fullDisplay,
            CanvasGeometry.sourceRect(
                appBounds = Bounds(left = 1508, top = 300, right = 2332, bottom = 1764),
                displayWidth = 3840,
                displayHeight = 2160,
                appOnly = false,
            ),
        )
    }

    @Test
    fun `cropped bounds subtract source origin before scaling`() {
        val mapped = CanvasGeometry.mapBounds(
            bounds = Bounds(left = 1600, top = 400, right = 1800, bottom = 500),
            source = CropRect(left = 1500, top = 300, width = 800, height = 1600),
            destination = FloatRect(left = 0f, top = 0f, width = 400f, height = 800f),
        )

        assertEquals(FloatRect(left = 50f, top = 50f, width = 100f, height = 50f), mapped)
    }

    @Test
    fun `cropped bounds clip partial nodes and hide outside nodes`() {
        val source = CropRect(left = 1500, top = 300, width = 800, height = 1600)
        val destination = FloatRect(left = 10f, top = 20f, width = 400f, height = 800f)

        assertEquals(
            FloatRect(left = 10f, top = 20f, width = 50f, height = 50f),
            CanvasGeometry.mapBounds(
                bounds = Bounds(left = 1400, top = 200, right = 1600, bottom = 400),
                source = source,
                destination = destination,
            ),
        )
        assertNull(
            CanvasGeometry.mapBounds(
                bounds = Bounds(left = 0, top = 0, right = 100, bottom = 100),
                source = source,
                destination = destination,
            ),
        )
    }

    @Test
    fun `preview sizing preserves portrait and landscape source ratios`() {
        assertEquals(
            FloatSize(width = 390f, height = 780f),
            CanvasGeometry.previewSize(
                source = CropRect(left = 0, top = 0, width = 800, height = 1600),
                maxWidth = 1000f,
                maxHeight = 1000f,
                portraitMaxWidth = 390f,
            ),
        )
        assertEquals(
            FloatSize(width = 1000f, height = 500f),
            CanvasGeometry.previewSize(
                source = CropRect(left = 0, top = 0, width = 1600, height = 800),
                maxWidth = 1000f,
                maxHeight = 1000f,
                portraitMaxWidth = 390f,
            ),
        )
    }
}
