package com.androidperformancestudio.screencast.app

import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.defaultAdbCommandExecutable
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.screencast.presentation.DeviceOption
import com.androidperformancestudio.screencast.presentation.ScreencastPageController
import com.androidperformancestudio.screencast.server.ScreencastQuality
import com.androidperformancestudio.screencast.session.FFmpegFrameDecoder
import com.androidperformancestudio.screencast.session.FFmpegVaapiFrameDecoder
import com.androidperformancestudio.screencast.session.H264FrameDecoder
import com.androidperformancestudio.screencast.session.ScrcpySession
import com.androidperformancestudio.screencast.session.VaapiSupport
import com.androidperformancestudio.screencast.session.ScreencastFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Factory for the screencast page controller bound to a host adb executable.
 * The adb binary is resolved through the shared adb-core locator, which honors
 * an explicitly configured SDK path and falls back to the system adb.
 *
 * The frame decoder prefers FFmpeg (fast, handles native resolutions) and
 * falls back to the pure-Java jcodec decoder when the FFmpeg native library
 * cannot be loaded on this host.
 */
public class ScreencastDependencies(
    private val androidSdkPath: Path?,
) {
    private val adb: AdbClient by lazy {
        val configuration = AdbConfiguration(androidSdkPath = androidSdkPath)
        DefaultAdbClient(defaultAdbCommandExecutable(configuration))
    }

    /** Resolved once per app run; surfaces the fallback to the UI. */
    private val decoderBackend: DecoderBackend by lazy { resolveDecoderBackend() }

    public fun createController(): ScreencastPageController =
        ScreencastPageController(
            sessionFactory = { serial, quality ->
                ScrcpySession(
                    adb,
                    serial,
                    quality = effectiveQuality(quality),
                    frameDecoder = decoderBackend.create(),
                )
            },
            deviceLister = { listDevices(adb) },
            initialQuality = ScreencastQuality.DEFAULT,
            decoderFallbackMessage = decoderBackend.fallbackMessage,
        )

    /**
     * The jcodec fallback cannot keep up above its profiled budget, so a
     * fallback forces the FLUENT quality regardless of the UI selection.
     */
    private fun effectiveQuality(quality: ScreencastQuality): ScreencastQuality =
        if (decoderBackend.isFallback) ScreencastQuality.FLUENT else quality

    /**
     * Three-tier decoder chain: VAAPI hardware -> FFmpeg software -> jcodec.
     * The hardware tier is gated on a runtime probe (render-node access and
     * driver init), not just native-library loading, because open failures
     * are only observable at the first packet.
     */
    private fun resolveDecoderBackend(): DecoderBackend =
        try {
            if (!VaapiSupport.probe()) {
                println("[screencast] VA-API probe negative - trying FFmpeg soft decode")
                throw IllegalStateException("VA-API unavailable")
            }
            FFmpegVaapiFrameDecoder()
            println("[screencast] VAAPI hardware decoder backend active")
            DecoderBackend(
                isFallback = false,
                fallbackMessage = null,
                create = { FFmpegVaapiFrameDecoder() },
            )
        } catch (hwError: Throwable) {
            println("[screencast] VAAPI path unavailable (${hwError.javaClass.simpleName}) - trying FFmpeg soft decode")
            try {
                // Instantiating triggers the JavaCPP native-library load; any
                // UnsatisfiedLinkError/ExceptionInInitializerError surfaces here,
                // before a session depends on it.
                FFmpegFrameDecoder()
                println("[screencast] FFmpeg soft decoder backend active")
                DecoderBackend(
                    isFallback = false,
                    fallbackMessage = null,
                    create = { FFmpegFrameDecoder() },
                )
            } catch (softError: Throwable) {
                println("[screencast] FFmpeg soft path failed ($softError) - falling back to jcodec")
                DecoderBackend(
                    isFallback = true,
                    fallbackMessage = softError.message ?: softError.javaClass.simpleName,
                    create = { H264FrameDecoder() },
                )
            }
        }

    private data class DecoderBackend(
        val isFallback: Boolean,
        val fallbackMessage: String?,
        val create: () -> ScreencastFrameDecoder,
    )

    private suspend fun listDevices(adb: AdbClient): List<DeviceOption> =
        withContext(Dispatchers.IO) {
            adb
                .listDevices()
                .filter { it.state == AdbDeviceState.ONLINE }
                .map {
                    // Prefer the parsed model/product label (already a friendly fallback chain) over
                    // the raw serial so the device dropdown reads like the quality dropdown rather
                    // than a long serial string. The parser guarantees a non-null model by falling
                    // back to the serial, but the field is typed nullable, so guard it.
                    DeviceOption(serial = it.serial, displayName = it.model ?: it.serial)
                }
        }
}
