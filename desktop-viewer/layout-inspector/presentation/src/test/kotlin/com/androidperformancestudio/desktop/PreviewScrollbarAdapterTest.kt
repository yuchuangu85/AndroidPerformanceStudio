package com.androidperformancestudio.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewScrollbarAdapterTest {
    @Test
    fun `horizontal scrollbar maps centered pan across the full overflow`() = runBlocking {
        val pan = mutableStateOf(Offset.Zero)
        val adapter = previewAdapter(PreviewScrollbarAxis.HORIZONTAL, pan)

        assertEquals(200.0, adapter.scrollOffset)
        assertEquals(800.0, adapter.contentSize)
        assertEquals(400.0, adapter.viewportSize)

        adapter.scrollTo(0.0)
        assertEquals(Offset(200f, 0f), pan.value)

        adapter.scrollTo(400.0)
        assertEquals(Offset(-200f, 0f), pan.value)
    }

    @Test
    fun `vertical scrollbar updates only vertical pan and clamps its offset`() = runBlocking {
        val pan = mutableStateOf(Offset(40f, 0f))
        val adapter = previewAdapter(PreviewScrollbarAxis.VERTICAL, pan)

        adapter.scrollTo(10_000.0)

        assertEquals(Offset(40f, -250f), pan.value)
        assertEquals(500.0, adapter.scrollOffset)
    }

    private fun previewAdapter(
        axis: PreviewScrollbarAxis,
        pan: androidx.compose.runtime.MutableState<Offset>,
    ) = PreviewScrollbarAdapter(
        axis = axis,
        pan = pan,
        contentWidthPx = 800f,
        contentHeightPx = 1_000f,
        viewportWidthPx = 400f,
        viewportHeightPx = 500f,
    )
}
