package com.androidperformancestudio.screencast.session

import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.global.avutil
import kotlin.test.Test

/**
 * Diagnostic probe (not a regression test): checks whether this host can
 * create a VA-API hardware device context with the bytedeco FFmpeg build.
 * Run manually: gradlew :screencast-core:test --tests '*VaapiProbe*'
 */
class VaapiProbeTest {
    @Test
    fun `probe vaapi hwdevice`() {
        // Preload system libva (1.20, matches iHD driver) so libavcodec's
        // DT_NEEDED "libva.so.2" resolves to it instead of javacpp's bundled copy.
        try {
            System.load("/usr/lib/x86_64-linux-gnu/libva.so.2")
            System.load("/usr/lib/x86_64-linux-gnu/libva-drm.so.2")
            println("[probe] system libva preloaded")
        } catch (e: UnsatisfiedLinkError) {
            println("[probe] system libva preload failed: ${e.message}")
        }
        avutil.av_log_set_level(avutil.AV_LOG_DEBUG)
        // Which libva is loaded in this JVM?
        val maps = java.io.File("/proc/self/maps").readText()
        maps.lines().filter { it.contains("libva") }.map { it.trim().substringAfterLast(' ') }.distinct().forEach {
            println("[probe] mapped: $it")
        }
        println("[probe] LIBVA_DRIVERS_PATH=${System.getenv("LIBVA_DRIVERS_PATH")}")
        val deviceRef = AVBufferRef()
        val err =
            avutil.av_hwdevice_ctx_create(
                deviceRef,
                avutil.AV_HWDEVICE_TYPE_VAAPI,
                org.bytedeco.javacpp.BytePointer("/dev/dri/renderD128"),
                null,
                0,
            )
        println("[probe] vaapi create err=$err (0=ok)")
        if (err == 0) {
            println("[probe] VAAPI device created OK — hardware decode usable")
        } else {
            println("[probe] VAAPI unavailable (err=$err) — fall back to soft decode")
        }
    }
}
