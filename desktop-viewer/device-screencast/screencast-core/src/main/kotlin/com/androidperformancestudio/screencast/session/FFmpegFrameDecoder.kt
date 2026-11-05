package com.androidperformancestudio.screencast.session

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes scrcpy H.264 packets into AWT images with FFmpeg (via JavaCV).
 *
 * FFmpeg's H.264 decoder parses Annex-B parameter sets inline, so packets are
 * fed straight through — no manual SPS/PPS handling, unlike the jcodec
 * fallback. Decoded YUV frames are converted to TYPE_INT_RGB AWT images with
 * swscale; the color matrix follows the stream's own SPS/PPS markers.
 */
class FFmpegFrameDecoder : ScreencastFrameDecoder {
    private var codecContext: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var swsContext: SwsContext? = null
    private var destinationFrame: AVFrame? = null

    /** Swscale target: tightly packed RGBA bytes, one plane. */
    private var rgbBuffer: BytePointer? = null
    private var convertedWidth = 0
    private var convertedHeight = 0
    private var convertedFormat = -1

    /** Set once the decoder has been opened (first configure/decode). */
    private val opened = AtomicBoolean(false)

    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
    }

    @Synchronized
    override fun configure(payload: ByteArray) {
        // Feed parameter sets through the normal packet path; the H.264
        // decoder registers them before the next key frame arrives.
        decodePacket(payload)
    }

    @Synchronized
    override fun decode(payload: ByteArray): DecodedByteFrame? {
        val image = decodePacket(payload)
        if (image == null) {
            val count = nullDecodeCount.incrementAndGet()
            // Log the first few failures and then a sample, so persistent
            // decode failures are visible without spamming a live session.
            if (count <= 5L || count % 100L == 0L) {
                println("[ffmpeg-decoder] null decode (count=$count, last packet ${payload.size}B)")
            }
        } else if (!recoveryLogged.getAndSet(true) && nullDecodeCount.get() > 0) {
            // First success after failures: proves the pipeline recovered.
            println("[ffmpeg-decoder] decode recovered after ${nullDecodeCount.get()} nulls")
        }
        return image
    }

    private val nullDecodeCount = java.util.concurrent.atomic.AtomicLong(0)
    private val recoveryLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    private val frameSampleCounter = java.util.concurrent.atomic.AtomicLong(0)

    private fun logFailure(
        stage: String,
        code: Int,
        packetSize: Int,
    ) {
        val count = nullDecodeCount.get()
        if (count % 30 == 0L) {
            println("[ffmpeg-decoder] $stage failed code=$code packet=${packetSize}B count=$count")
        }
    }

    /** Reused native input buffer; must outlive avcodec_send_packet, which
     * may queue the data for frame-threading workers (no per-packet free). */
    private var inputBuffer: BytePointer? = null
    private var inputBufferCapacity = 0

    @Suppress("ReturnCount")
    private fun decodePacket(payload: ByteArray): DecodedByteFrame? {
        ensureDecoder()
        if (payload.isEmpty()) return null
        val context = codecContext ?: return null
        val inputPacket = packet ?: return null
        val outputFrame = frame ?: return null

        // Grow-on-demand persistent buffer: with frame threading the decoder
        // may still be reading the previous packet while we send the next, so
        // the backing memory must stay valid across calls.
        val buffer = acquireInputBuffer(payload.size)
        buffer.put(payload, 0, payload.size)

        avcodec.av_packet_unref(inputPacket)
        inputPacket.data(buffer)
        inputPacket.size(payload.size)
        // scrcpy packets are complete access units; a negative send means
        // a corrupt or unparseable packet — skip and wait for a key frame.
        val sendResult = avcodec.avcodec_send_packet(context, inputPacket)
        if (sendResult < 0) {
            logFailure("send", sendResult, payload.size)
            return null
        }
        val receiveResult = avcodec.avcodec_receive_frame(context, outputFrame)
        if (receiveResult < 0) {
            // EAGAIN: needs more input; other negatives: decode error.
            logFailure("receive", receiveResult, payload.size)
            return null
        }
        return convertToImage(outputFrame)
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

    /** Converts a decoded YUV frame to an AWT TYPE_INT_RGB image via swscale. */
    @Suppress("MagicNumber")
    private fun convertToImage(source: AVFrame): DecodedByteFrame {
        val width = source.width()
        val height = source.height()
        val format = source.format()
        ensureSwsContext(width, height, format)

        // Frame-to-frame conversion: the destination AVFrame is wired straight
        // to the BGRA buffer, eliminating manual pointer/stride plumbing.
        val dstFrame = destinationFrame ?: error("destination frame missing")
        dstFrame.width(width)
        dstFrame.height(height)
        dstFrame.format(avutil.AV_PIX_FMT_BGRA)
        dstFrame.data(0, rgbBuffer)
        dstFrame.linesize(0, width * RGBA_BYTES_PER_PIXEL)
        val convertStart = System.nanoTime()
        val scaledRows = swscale.sws_scale_frame(swsContext, dstFrame, source)
        val swsNanos = System.nanoTime() - convertStart

        val buffer = rgbBuffer!!
        if (frameSampleCounter.incrementAndGet() % 100L == 1L) {
            // Read the raw swscale output bytes directly, bypassing AWT.
            buffer.position(0)
            val probe = ByteArray(12)
            buffer.get(probe, 0, 12)
            println(
                "[ffmpeg-decoder] sws rows=$scaledRows srcFormat=$format " +
                    "lines0=${source.linesize(0)} firstBytes=${probe.joinToString(" ") { "%02x".format(it) }}",
            )
        }
        // One bulk read of the BGRA raster (sws writes BGRA; see
        // ensureSwsContext) instead of a JNI call per pixel.
        buffer.position(0)
        val bytes = ByteArray(width * height * RGBA_BYTES_PER_PIXEL)
        buffer.get(bytes, 0, bytes.size)

        val totalNanos = System.nanoTime() - convertStart
        val sample = frameSampleCounter.get()
        if (sample % 100L == 0L) {
            println(
                "[ffmpeg-decoder] perf: sws=${swsNanos / 1_000_000.0}ms " +
                    "total=${totalNanos / 1_000_0.0}ms",
            )
        }
        return DecodedByteFrame(bytes, width, height)
    }

    @Suppress("MagicNumber")
    private fun ensureSwsContext(
        width: Int,
        height: Int,
        sourceFormat: Int,
    ) {
        val geometryUnchanged = convertedWidth == width &&
            convertedHeight == height &&
            convertedFormat == sourceFormat
        if (swsContext != null && geometryUnchanged) {
            return
        }
        swsContext?.let { swscale.sws_freeContext(it) }
        swsContext = swscale.sws_getContext(
            width, height, sourceFormat,
            width, height, avutil.AV_PIX_FMT_BGRA,
            swscale.SWS_BILINEAR,
            null, null, null as org.bytedeco.javacpp.DoublePointer?,
        )
        convertedWidth = width
        convertedHeight = height
        convertedFormat = sourceFormat

        // A persistent destination frame bound to the BGRA buffer.
        destinationFrame?.let { avutil.av_frame_free(it) }
        destinationFrame = avutil.av_frame_alloc()

        val requiredBytes = width.toLong() * height * RGBA_BYTES_PER_PIXEL
        val existing = rgbBuffer
        if (existing == null || existing.capacity() < requiredBytes) {
            existing?.deallocate()
            rgbBuffer = BytePointer(requiredBytes)
        }
    }

    @Synchronized
    override fun reset() {
        codecContext?.let { avcodec.avcodec_free_context(it) }
        codecContext = null
        packet?.let { avcodec.av_packet_free(it) }
        packet = null
        frame?.let { avutil.av_frame_free(it) }
        frame = null
        swsContext?.let { swscale.sws_freeContext(it) }
        swsContext = null
        destinationFrame?.let { avutil.av_frame_free(it) }
        destinationFrame = null
        rgbBuffer?.deallocate()
        rgbBuffer = null
        inputBuffer?.deallocate()
        inputBuffer = null
        inputBufferCapacity = 0
        nullDecodeCount.set(0)
        recoveryLogged.set(false)
        convertedWidth = 0
        convertedHeight = 0
        convertedFormat = -1
        opened.set(false)
    }

    /** Lazily opens the H.264 decoder once per session. */
    @Suppress("MagicNumber")
    private fun ensureDecoder() {
        if (opened.get()) return
        synchronized(opened) {
            if (opened.get()) return
            val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
                ?: error("FFmpeg has no H.264 decoder")
            val context = avcodec.avcodec_alloc_context3(codec)
            checkNotNull(context) { "Failed to allocate AVCodecContext" }
            // thread_count=0 lets FFmpeg pick (frame+slice multithreading).
            context.thread_count(0)
            context.thread_type(AVCodecContext.FF_THREAD_FRAME or AVCodecContext.FF_THREAD_SLICE)
            val openResult = avcodec.avcodec_open2(context, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
            check(openResult == 0) { "avcodec_open2 failed: $openResult" }
            codecContext = context
            packet = avcodec.av_packet_alloc()
            frame = avutil.av_frame_alloc()
            opened.set(true)
        }
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
        const val MIN_INPUT_BUFFER_BYTES = 64 shl 10
    }
}


