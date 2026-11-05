package com.androidperformancestudio.screencast.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreencastQualityTest {
    @Test
    fun `default quality is HD`() {
        assertEquals(ScreencastQuality.HD, ScreencastQuality.DEFAULT)
    }

    @Test
    fun `fluent caps stream to the decoder budget`() {
        val options = ScreencastQuality.FLUENT.toServerOptions()
        assertTrue("max_size=1280" in options)
        assertTrue("max_fps=10" in options)
        assertTrue("video_bit_rate=2000000" in options)
    }

    @Test
    fun `native omits the max_size cap`() {
        val options = ScreencastQuality.NATIVE.toServerOptions()
        assertFalse(options.any { it.startsWith("max_size=") })
        assertTrue("max_fps=30" in options)
        assertTrue("video_bit_rate=20000000" in options)
    }

    @Test
    fun `hd keeps a resolution cap`() {
        val options = ScreencastQuality.HD.toServerOptions()
        assertTrue("max_size=1600" in options)
    }
}
