package com.androidperformancestudio.screencast.session

import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.global.avutil

/**
 * VA-API availability gate for the hardware decode path.
 *
 * Two host-side pitfalls are handled here:
 *
 * 1. JavaCPP ships its own (older) libva, which JavaCPP's loader preloads
 *    from its cache directory. That copy fails to initialize the system iHD
 *    driver (EIO), while the distro libva (1.20) works. Preloading the
 *    distro libva with [System.load] before any FFmpeg class initializes
 *    makes the dynamic linker resolve libavcodec's DT_NEEDED `libva.so.2`
 *    to the already-loaded distro copy.
 * 2. The render node (`/dev/dri/renderD128`) requires `render` group
 *    membership; without it libva init fails with EIO.
 *
 * The probe is cheap and runs once per app run from the assembly layer.
 */
object VaapiSupport {
    private val probed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val available = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Renders on Intel Gen graphics; the only hwaccel target we attempt. */
    private const val RENDER_NODE = "/dev/dri/renderD128"

    /**
     * Loads the distro libva before JavaCPP's bundled one, when present.
     * Harmless on hosts without the distro library (load error is swallowed:
     * the probe below will simply report unavailable).
     */
    fun preloadSystemLibva() {
        for (library in listOf("/usr/lib/x86_64-linux-gnu/libva.so.2", "/usr/lib/x86_64-linux-gnu/libva-drm.so.2")) {
            try {
                System.load(library)
            } catch (_: UnsatisfiedLinkError) {
                // Distro library missing or wrong arch; bundled libva will be
                // used instead and the probe decides availability.
            }
        }
    }

    /** Returns whether VA-API hardware decode can be initialized on this host. */
    fun probe(): Boolean {
        if (probed.get()) return available.get()
        synchronized(this) {
            if (probed.get()) return available.get()
            preloadSystemLibva()
            val ok = try {
                val deviceRef = AVBufferRef()
                val err =
                    avutil.av_hwdevice_ctx_create(
                        deviceRef,
                        avutil.AV_HWDEVICE_TYPE_VAAPI,
                        org.bytedeco.javacpp.BytePointer(RENDER_NODE),
                        null,
                        0,
                    )
                if (err == 0) {
                    avutil.av_buffer_unref(deviceRef)
                }
                err == 0
            } catch (_: Throwable) {
                false
            }
            available.set(ok)
            probed.set(true)
            return ok
        }
    }
}
