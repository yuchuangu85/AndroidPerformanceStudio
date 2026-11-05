package com.androidperformancestudio.screencast.session

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.avutil.AVHWFramesContext
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VA-API accelerated H.264 decoder with an automatic soft-decode fallback.
 *
 * Pipeline: avcodec h264 decoder bound to a VAAPI hwdevice/hwframes context.
 * Decoded frames stay on the GPU; each frame is downloaded to system memory
 * as NV12, then converted to a TYPE_INT_RGB AWT image with swscale.
 *
 * Opening fails when VA-API is unavailable (no render-node access, driver
 * mismatch); the assembly layer then falls back to [FFmpegFrameDecoder]
 * (soft) and finally [H264FrameDecoder] (jcodec).
 */
class FFmpegVaapiFrameDecoder : ScreencastFrameDecoder {
    private var codecContext: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null

    /** GPU-side surface pool backing the decoder. */
    private var hwFramesContext: AVBufferRef? = null

    /** System-memory staging frame for av_hwframe_transfer_data. */
    private var downloadFrame: AVFrame? = null
    private var swsContext: SwsContext? = null
    private var destinationFrame: AVFrame? = null

    /** Swscale target: tightly packed BGRA bytes, one plane. */
    private var rgbBuffer: BytePointer? = null
    private var convertedWidth = 0
    private var convertedHeight = 0




    /** Reused native input buffer (see FFmpegFrameDecoder). */
    private var inputBuffer: BytePointer? = null
    private var inputBufferCapacity = 0

    private val opened = AtomicBoolean(false)
    private val nullDecodeCount = java.util.concurrent.atomic.AtomicLong(0)
    private val frameSampleCounter = java.util.concurrent.atomic.AtomicLong(0)

    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
    }

    @Synchronized
    override fun configure(payload: ByteArray) {
        decodePacket(payload)
    }

    @Synchronized
    override fun decode(payload: ByteArray): DecodedByteFrame? {
        val image = decodePacket(payload)
        if (image == null) {
            val count = nullDecodeCount.incrementAndGet()
            if (count <= 5L || count % 100L == 0L) {
                println("[vaapi-decoder] null decode (count=$count, packet ${payload.size}B)")
            }
        }
        return image
    }

    @Suppress("ReturnCount")
    private fun decodePacket(payload: ByteArray): DecodedByteFrame? {
        if (!ensureDecoder()) return null
        if (payload.isEmpty()) return null
        val context = codecContext ?: return null
        val inputPacket = packet ?: return null
        val outputFrame = frame ?: return null

        val buffer = acquireInputBuffer(payload.size)
        buffer.put(payload, 0, payload.size)

        avcodec.av_packet_unref(inputPacket)
        inputPacket.data(buffer)
        inputPacket.size(payload.size)
        val sendResult = avcodec.avcodec_send_packet(context, inputPacket)
        if (sendResult < 0) {
            logFailure("send", sendResult, payload.size)
            return null
        }
        val receiveResult = avcodec.avcodec_receive_frame(context, outputFrame)
        if (receiveResult < 0) {
            logFailure("receive", receiveResult, payload.size)
            return null
        }
        return convertToImage(outputFrame)
    }

    /** Opens the hw-bound H.264 decoder; returns false when VA-API unusable. */
    @Suppress("MagicNumber")
    private fun ensureDecoder(): Boolean {
        if (opened.get()) return true
        synchronized(opened) {
            if (opened.get()) return true
            // The probe also performs the system-libva preload.
            if (!VaapiSupport.probe()) {
                println("[vaapi-decoder] VA-API unavailable at open — hardware path disabled")
                return false
            }
            val deviceRef = AVBufferRef()
            val deviceErr =
                avutil.av_hwdevice_ctx_create(
                    deviceRef,
                    avutil.AV_HWDEVICE_TYPE_VAAPI,
                    BytePointer(RENDER_NODE),
                    null,
                    0,
                )
            if (deviceErr != 0) {
                println("[vaapi-decoder] hwdevice create failed err=$deviceErr")
                return false
            }
            val framesRef = avutil.av_hwframe_ctx_alloc(deviceRef)
            if (framesRef == null || framesRef.isNull) {
                avutil.av_buffer_unref(deviceRef)
                println("[vaapi-decoder] hwframes alloc failed")
                return false
            }
            // The buffer ref wraps the context; the context itself lives at
            // the buffer's data pointer.
            val framesCtx = AVHWFramesContext(framesRef.data())
            framesCtx.format(avutil.AV_PIX_FMT_VAAPI)
            framesCtx.sw_format(avutil.AV_PIX_FMT_NV12)
            framesCtx.width(DECODER_WIDTH)
            framesCtx.height(DECODER_HEIGHT)
            framesCtx.initial_pool_size(HW_POOL_SIZE)
            if (avutil.av_hwframe_ctx_init(framesRef) != 0) {
                avutil.av_buffer_unref(framesRef)
                avutil.av_buffer_unref(deviceRef)
                println("[vaapi-decoder] hwframes init failed")
                return false
            }
            hwFramesContext = framesRef

            val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
                ?: error("FFmpeg has no H.264 decoder")
            val context = avcodec.avcodec_alloc_context3(codec)
            checkNotNull(context) { "Failed to allocate AVCodecContext" }
            // GPU decode is serialized on the device; software frame/slice
            // threading does not apply and only wastes workers.
            context.thread_count(1)
            context.hw_device_ctx(avutil.av_buffer_ref(deviceRef))
            val openResult = avcodec.avcodec_open2(context, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
            if (openResult != 0) {
                avutil.av_buffer_unref(deviceRef)
                println("[vaapi-decoder] avcodec_open2 failed: $openResult")
                return false
            }
            context.hw_frames_ctx(avutil.av_buffer_ref(framesRef))
            codecContext = context
            packet = avcodec.av_packet_alloc()
            frame = avutil.av_frame_alloc()
            downloadFrame = avutil.av_frame_alloc()
            opened.set(true)
            println("[vaapi-decoder] VA-API decoder opened (pool=$HW_POOL_SIZE)")
            return true
        }
    }
    @Suppress("MagicNumber")
    private fun convertToImage(source: AVFrame): DecodedByteFrame {
        val download = downloadFrame ?: error("download frame missing")
        avutil.av_frame_unref(download)
        download.width(source.width())
        download.height(source.height())
        download.format(avutil.AV_PIX_FMT_NV12)
        val transferErr = avutil.av_hwframe_transfer_data(download, source, 0)
        if (transferErr < 0) {
            error("av_hwframe_transfer_data failed: ")
        }
        val width = download.width()
        val height = download.height()
        ensureSwsContext(width, height)

        // Wire the destination frame straight to the BGRA buffer and let
        // swscale do the NV12 -> BGRA conversion in one pass.
        val dstFrame = destinationFrame ?: error("destination frame missing")
        dstFrame.width(width)
        dstFrame.height(height)
        dstFrame.format(avutil.AV_PIX_FMT_BGRA)
        dstFrame.data(0, rgbBuffer)
        dstFrame.linesize(0, width * RGBA_BYTES_PER_PIXEL)
        val convertStart = System.nanoTime()
        swscale.sws_scale_frame(swsContext, dstFrame, download)
        val convertNanos = System.nanoTime() - convertStart

        val buffer = rgbBuffer!!
        buffer.position(0)
        val bytes = ByteArray(width * height * RGBA_BYTES_PER_PIXEL)
        buffer.get(bytes, 0, bytes.size)
        if (frameSampleCounter.incrementAndGet() % 100L == 1L) {
            println(
                "[vaapi-decoder] perf: sws=${convertNanos / 1_000_000.0}ms " +
                    "copy=${(System.nanoTime() - convertStart - convertNanos) / 1_000_000.0}ms",
            )
        }
        return DecodedByteFrame(bytes, width, height)
    }

    private fun acquireInputBuffer(required: Int): BytePointer {
        val existing = inputBuffer
        if (existing != null && inputBufferCapacity >= required) {
            return existing
        }
        existing?.deallocate()
        val capacity = maxOf(required, MIN_INPUT_BUFFER_BYTES)
        val created = BytePointer(capacity.toLong())
        inputBuffer = created
        inputBufferCapacity = capacity
        return created
    }

    private fun ensureSwsContext(
        width: Int,
        height: Int,
    ) {
        if (swsContext != null && convertedWidth == width && convertedHeight == height) {
            return
        }
        swsContext?.let { swscale.sws_freeContext(it) }
        swsContext = swscale.sws_getContext(
            width, height, avutil.AV_PIX_FMT_NV12,
            width, height, avutil.AV_PIX_FMT_BGRA,
            swscale.SWS_BILINEAR,
            null, null, null as org.bytedeco.javacpp.DoublePointer?,
        )
        convertedWidth = width
        convertedHeight = height

        destinationFrame?.let { avutil.av_frame_free(it) }
        destinationFrame = avutil.av_frame_alloc()

        val requiredBytes = width.toLong() * height * RGBA_BYTES_PER_PIXEL
        val existing = rgbBuffer
       if (existing == null || existing.capacity() < requiredBytes) {
            existing?.deallocate()
            rgbBuffer = BytePointer(requiredBytes)
        }
    }

    private fun logFailure(
        stage: String,
        code: Int,
        packetSize: Int,
    ) {
        val count = nullDecodeCount.get()
        if (count % 30 == 0L) {
            println("[vaapi-decoder] $stage failed code=$code packet=${packetSize}B count=$count")
        }
    }

    @Synchronized
    override fun reset() {
        codecContext?.let { avcodec.avcodec_free_context(it) }
        codecContext = null
        packet?.let { avcodec.av_packet_free(it) }
        packet =  null
        frame?.let { avutil.av_frame_free(it) }
        frame = null
        downloadFrame?.let { avutil.av_frame_free(it) }
        downloadFrame = null
        swsContext?.let { swscale.sws_freeContext(it) }
        swsContext = null
        destinationFrame?.let { avutil.av_frame_free(it) }
        destinationFrame = null
        rgbBuffer?.deallocate()
        rgbBuffer = null
        inputBuffer?.deallocate()
        inputBuffer = null
        inputBufferCapacity = 0
        hwFramesContext?.let { avutil.av_buffer_unref(it) }
        hwFramesContext = null
        nullDecodeCount.set(0)
        convertedWidth = 0
        convertedHeight = 0
        opened.set(false)
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
        const val MIN_INPUT_BUFFER_BYTES = 64 shl 10
        const val DECODER_WIDTH = 2560
        const val DECODER_HEIGHT = 1600
        const val HW_POOL_SIZE = 8
        const val RENDER_NODE = "/dev/dri/renderD128"
    }
}

