package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.presentation.FrameOperationStatus
import com.androidperformancestudio.ui.UiLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameProfilerLocalizationTest {
    @Test
    fun `capture status messages follow the selected Chinese language`() {
        val capturing = FrameOperationStatus.Capturing("dev.example.app", "gfxinfo", 2)

        assertEquals(
            "正在通过 gfxinfo 采集 dev.example.app：2 帧",
            capturing.localizedText(UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "采集已停止：共 2 帧。",
            FrameOperationStatus.CaptureStopped(2).localizedText(UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "采集已停止，未收到帧数据。",
            FrameOperationStatus.CaptureStopped(0).localizedText(UiLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `capture status messages retain English output`() {
        assertEquals(
            "Capture stopped: 2 frames.",
            FrameOperationStatus.CaptureStopped(2).localizedText(UiLanguage.ENGLISH),
        )
    }
}
