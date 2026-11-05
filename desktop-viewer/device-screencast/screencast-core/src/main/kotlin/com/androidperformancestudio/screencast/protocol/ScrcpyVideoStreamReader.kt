package com.androidperformancestudio.screencast.protocol

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads the scrcpy v3.x video stream: the fixed-width device name, codec
 * metadata, then framed H.264 packets.
 *
 * Framing (when `send_frame_meta` is enabled, the default) is per packet:
 * 8-byte big-endian `ptsAndFlags` followed by a 4-byte payload size.
 * `ptsAndFlags` carries the PTS in the low bits with:
 *  - bit 63 set for codec configuration packets,
 *  - bit 62 set for key frames.
 */
class ScrcpyVideoStreamReader(
    rawInputStream: InputStream,
) : AutoCloseable {
    private val input = DataInputStream(BufferedInputStream(rawInputStream))

    /** Reads the 64-byte NUL-padded device name sent before codec metadata. */
    @Throws(IOException::class)
    fun readDeviceName(): String {
        val buffer = ByteArray(DEVICE_NAME_FIELD_LENGTH)
        input.readFully(buffer)
        val end = buffer.indexOf(0).takeIf { it >= 0 } ?: buffer.size
        return String(buffer, 0, end, Charsets.UTF_8)
    }

    /** Reads the 12-byte video codec metadata: codec id, width, height (all u32, big-endian). */
    @Throws(IOException::class)
    fun readVideoMetadata(): VideoMetadata {
        val codecId = input.readInt()
        val width = input.readInt()
        val height = input.readInt()
        return VideoMetadata(codecId, width, height)
    }

    /**
     * Reads the next framed packet, blocking until a full payload is available.
     * Returns `null` on a clean end of stream.
     */
    @Suppress("SwallowedException")
    @Throws(IOException::class)
    fun readPacket(): VideoPacket? {
        val header = try {
            val ptsAndFlags = input.readLong()
            val size = input.readInt()
            require(size >= 0 && size <= MAX_PACKET_SIZE) { "Invalid scrcpy packet size: $size" }
            Header(ptsAndFlags, size)
        } catch (error: EOFException) {
            return null
        }
        val payload = ByteArray(header.size)
        input.readFully(payload)
        return VideoPacket(
            isConfig = header.ptsAndFlags and PACKET_FLAG_CONFIG != 0L,
            isKeyFrame = header.ptsAndFlags and PACKET_FLAG_KEY_FRAME != 0L,
            pts = header.ptsAndFlags and PACKET_PTS_MASK,
            payload = payload,
        )
    }

    override fun close() {
        input.close()
    }

    private data class Header(
        val ptsAndFlags: Long,
        val size: Int,
    )

    data class VideoMetadata(
        val codecId: Int,
        val width: Int,
        val height: Int,
    ) {
        val codecName: String
            get() =
                when (codecId) {
                    CODEC_H264 -> "h264"
                    CODEC_H265 -> "h265"
                    CODEC_AV1 -> "av1"
                    else -> "unknown(0x${codecId.toUInt().toString(HEX_RADIX)})"
                }
    }

    data class VideoPacket(
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val pts: Long,
        val payload: ByteArray,
    )

    companion object {
        private const val HEX_RADIX = 16

        const val DEVICE_NAME_FIELD_LENGTH = 64
        const val CODEC_H264 = 0x68323634
        const val CODEC_H265 = 0x68323635
        const val CODEC_AV1 = 0x00617631

        const val PACKET_FLAG_CONFIG = 1L shl 63
        const val PACKET_FLAG_KEY_FRAME = 1L shl 62

        /** PTS occupies the low 62 bits below the config/key-frame flags. */
        const val PACKET_PTS_MASK = (PACKET_FLAG_KEY_FRAME - 1)

        // Defensive bound: 4K H.264 frames stay far below this; the server caps
        // messages at 256k for control but frames can exceed that, so allow 16 MiB.
        const val MAX_PACKET_SIZE = 16 shl 20
    }
}

private fun ByteArray.indexOf(byte: Byte): Int = indexOfFirst { it == byte }
