package com.androidperformancestudio.desktop

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewPanStateTest {
    @Test
    fun `pan stays centered when scaled preview fits viewport`() {
        assertEquals(
            Offset.Zero,
            PreviewPanState.clamp(
                pan = Offset(40f, -40f),
                contentWidthPx = 300f,
                contentHeightPx = 500f,
                viewportWidthPx = 400f,
                viewportHeightPx = 600f,
            ),
        )
    }

    @Test
    fun `pan clamps overflow independently per axis`() {
        assertEquals(
            Offset(100f, -200f),
            PreviewPanState.clamp(
                pan = Offset(250f, -500f),
                contentWidthPx = 600f,
                contentHeightPx = 1000f,
                viewportWidthPx = 400f,
                viewportHeightPx = 600f,
            ),
        )
    }

    @Test
    fun `pan allows only the axis that overflows viewport`() {
        assertEquals(
            Offset.Zero.copy(y = -150f),
            PreviewPanState.clamp(
                pan = Offset(80f, -150f),
                contentWidthPx = 300f,
                contentHeightPx = 900f,
                viewportWidthPx = 400f,
                viewportHeightPx = 600f,
            ),
        )
    }

    @Test
    fun `scrolling a zoomed preview pans on both axes`() {
        assertEquals(
            Offset(-80f, -120f),
            PreviewPanState.scroll(
                pan = Offset.Zero,
                scrollDelta = Offset(80f, 120f),
                contentWidthPx = 800f,
                contentHeightPx = 900f,
                viewportWidthPx = 400f,
                viewportHeightPx = 500f,
            ),
        )
    }

    @Test
    fun `scrolling a zoomed preview clamps at canvas edges`() {
        assertEquals(
            Offset(-200f, 0f),
            PreviewPanState.scroll(
                pan = Offset(-190f, 60f),
                scrollDelta = Offset(80f, -120f),
                contentWidthPx = 800f,
                contentHeightPx = 300f,
                viewportWidthPx = 400f,
                viewportHeightPx = 500f,
            ),
        )
    }
}
