package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CanvasBorderColorsTest {
    @Test
    fun `parses rgb and argb hex values`() {
        assertEquals(0xFF7DD3FC, CanvasArgb.parse("#7DD3FC")?.value)
        assertEquals(0x807DD3FC, CanvasArgb.parse("#807DD3FC")?.value)
        assertNull(CanvasArgb.parse("#GG0000"))
    }

    @Test
    fun `persists three independent border colors`() {
        val values = mutableMapOf<String, String>()
        val store = CanvasBorderColorStore(values::get) { key, value -> values[key] = value }
        val expected = CanvasBorderColors(
            normal = CanvasArgb(0xFF010203),
            hovered = CanvasArgb(0xFF040506),
            selected = CanvasArgb(0xFF070809),
        )

        store.save(expected)

        assertEquals(expected, store.load())
    }
}
