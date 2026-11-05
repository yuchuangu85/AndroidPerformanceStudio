package com.androidperformancestudio.screencast.server

/**
 * User-selectable streaming quality for a screencast session. Values map to
 * scrcpy server startup options (`max_size`, `max_fps`, `video_bit_rate`);
 * the device encoder produces within these caps so the host decoder keeps up.
 */
@Suppress("MagicNumber")
enum class ScreencastQuality(
    val label: String,
    val maxSize: Int?,
    val maxFps: Int,
    val videoBitRate: Int,
) {
    /** Lowest latency, lightest stream; also the jcodec-fallback profile. */
    FLUENT("流畅", 1280, 10, 2_000_000),

    /** Default: readable text and smooth scrolling on capable hardware. */
    HD("高清", 1600, 60, 12_000_000),

    /**
     * Device's native resolution; capped at 30fps — at 60fps the device-side
     * H.264 encoder queues up and adds 20ms+ of end-to-end latency (measured
     * on TB355FU: touch response degrades noticeably vs. HD).
     */
    NATIVE("原生", null, 30, 20_000_000),
    ;

    /** Server option fragments for this quality (empty for unset caps). */
    fun toServerOptions(): List<String> =
        buildList {
            maxSize?.let { add("max_size=$it") }
            add("max_fps=$maxFps")
            add("video_bit_rate=$videoBitRate")
        }

    companion object {
        /** Default selection for the screencast page. */
        val DEFAULT: ScreencastQuality = HD
    }
}
