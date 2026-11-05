package com.androidperformancestudio.screencast.session

import org.jcodec.codecs.h264.H264Decoder
import org.jcodec.common.model.Picture
import java.nio.ByteBuffer

/**
 * Decodes scrcpy H.264 packets into AWT images using jcodec's pure-Java decoder.
 *
 * scrcpy sends the codec configuration as an Annex-B stream (start-code
 * delimited SPS/PPS NALs). jcodec registers parameter sets while reading a
 * frame's NALs, so the configuration NALs are prepended to the next key frame
 * and handed to [H264Decoder.decodeFrameFromNals] together.
 */
class H264FrameDecoder(
    videoWidth: Int = 0,
    videoHeight: Int = 0,
) : ScreencastFrameDecoder {
    private val decoder = H264Decoder()
    private val videoWidth = videoWidth.coerceAtLeast(0)
    private val videoHeight = videoHeight.coerceAtLeast(0)

    /** Annex-B codec configuration (SPS/PPS) waiting to be prepended to a key frame. */
    @Volatile
    private var pendingConfig: ByteArray? = null

    /** Reusable decoded-plane buffers handed to jcodec. */
    private var outputBuffer: Array<ByteArray>? = null

    /** Feeds the codec configuration packet (Annex-B SPS/PPS). */
    @Synchronized
    override fun configure(payload: ByteArray) {
        require(payload.isNotEmpty()) { "Empty H.264 configuration packet" }
        pendingConfig = payload
    }

    /**
     * Decodes one H.264 access unit. Returns the decoded image, or `null`
     * when the packet did not complete a decodable picture.
     */
    @Suppress("SwallowedException")
    @Synchronized
    override fun decode(payload: ByteArray): DecodedByteFrame? {
        val packetPayload = if (payload.isNotEmpty() && isKeyFrameNal(payload) && pendingConfig != null) {
            // Parameter sets must precede the key frame's slices for jcodec to
            // (re)register them; scrcpy sends them once as a separate packet.
            val config = pendingConfig
            pendingConfig = null
            config?.let { it + payload } ?: payload
        } else {
            payload
        }
        return try {
            val nals = splitAnnexBNals(packetPayload).map { ByteBuffer.wrap(it) }
            if (nals.isEmpty()) return null
            val outputBuffer = acquireOutputBuffer()
            val picture: Picture? = decoder.decodeFrameFromNals(nals, outputBuffer)
            picture ?: return null
            packBgra(picture)
        } catch (error: RuntimeException) {
            // Corrupt or unsupported slice: skip this packet rather than kill
            // the session; the next key frame will resync.
            if (System.getProperty("scrcpy.debug.decode") == "true") {
                println("[scrcpy-debug] decode failed on ${packetPayload.size}B packet: $error")
            }
            null
        }
    }

    /**
     * jcodec writes decoded pixels into the supplied plane buffers; an empty
     * array makes the slice decoder fail with out-of-bounds writes. Allocate
     * lazily from the announced video size (4:2:0 chroma) and reuse across
     * frames.
     */
    private fun acquireOutputBuffer(): Array<ByteArray> {
        val existing = outputBuffer
        if (existing != null) return existing
        val width = videoWidth.coerceAtLeast(MIN_BUFFER_WIDTH)
        val height = videoHeight.coerceAtLeast(MIN_BUFFER_HEIGHT)
        val chromaWidth = width / CHROMA_SUBSAMPLING
        val chromaHeight = height / CHROMA_SUBSAMPLING
        val buffer = arrayOf(
            ByteArray(width * height),
            ByteArray(chromaWidth * chromaHeight),
            ByteArray(chromaWidth * chromaHeight),
        )
        outputBuffer = buffer
        return buffer
    }

    @Synchronized
    override fun reset() {
        pendingConfig = null
    }

    /** Checks whether the packet's first NAL is an IDR slice (nal type 5). */

    /** Checks whether the packet's first NAL is an IDR slice (nal type 5). */
    private fun isKeyFrameNal(payload: ByteArray): Boolean {
        val firstNal = splitAnnexBNals(payload).firstOrNull() ?: return false
        return nalType(firstNal) == NAL_TYPE_IDR
    }

    /**
     * Packs a jcodec Picture (YUV420p) into tightly packed BGRA bytes.
     * Color conversion via jcodec Yuv420pToRgb, then one byte-level repack
     * RGB -> BGRA with opaque alpha. No AWT intermediates.
     */
    private fun packBgra(picture: Picture): DecodedByteFrame {
        val width = picture.getPlaneWidth(0)
        val height = picture.getPlaneHeight(0)
        val rgbPicture = org.jcodec.common.model.Picture.create(width, height, org.jcodec.common.model.ColorSpace.RGB)
        org.jcodec.scale.Yuv420pToRgb().transform(picture, rgbPicture)
        val rgbData = rgbPicture.getPlaneData(0)
        val bytes = ByteArray(width * height * RGBA_BYTES_PER_PIXEL)
        var src = 0
        var dst = 0
        while (dst < bytes.size) {
            bytes[dst] = rgbData[src + 2]
            bytes[dst + 1] = rgbData[src + 1]
            bytes[dst + 2] = rgbData[src]
            bytes[dst + 3] = 0xFF.toByte()
            src += 3
            dst += 4
        }
        return DecodedByteFrame(bytes, width, height)
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
        const val NAL_TYPE_IDR = 5

        /** 4:2:0 chroma subsampling factor. */
        const val CHROMA_SUBSAMPLING = 2
        const val MIN_BUFFER_WIDTH = 64
        const val MIN_BUFFER_HEIGHT = 64
    }
}

/** Returns the NAL unit type from the first byte of a start-code-stripped NAL. */
private fun nalType(nal: ByteArray): Int = nal[0].toInt() and NAL_TYPE_MASK

private const val NAL_TYPE_MASK = 0x1F

private const val SHORT_START_CODE_SIZE = 3

/** Splits an Annex-B byte stream (00 00 01 / 00 00 00 01 start codes) into NAL payloads. */
@Suppress("MagicNumber")
private fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
    val startCode = intArrayOf(0, 0, 1)
    val starts = mutableListOf<Int>()
    var i = 0
    while (i < data.size - 2) {
        val matches = data[i].toInt() == startCode[0] &&
            data[i + 1].toInt() == startCode[1] &&
            data[i + 2].toInt() == startCode[2]
        if (matches) {
            starts.add(i + SHORT_START_CODE_SIZE)
            i += SHORT_START_CODE_SIZE
        } else {
            i++
        }
    }
    if (starts.isEmpty()) return emptyList()
    val nals = mutableListOf<ByteArray>()
    for (index in starts.indices) {
        val start = starts[index]
        val end = if (index + 1 < starts.size) {
            // Back up over the start code of the next NAL.
            var next = starts[index + 1] - SHORT_START_CODE_SIZE
            if (next > start && data[next - 1].toInt() == 0) next--
            next
        } else {
            data.size
        }
        if (end > start) {
            nals.add(data.copyOfRange(start, end))
        }
    }
    return nals
}
