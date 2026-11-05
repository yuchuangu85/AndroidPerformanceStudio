package com.androidperformancestudio.screencast.session

/**
 * A pluggable H.264 decoder for the scrcpy video stream.
 *
 * The session feeds packets in stream order: the codec configuration packet
 * (Annex-B SPS/PPS) via [configure], then framed access units via [decode].
 * Implementations must be safe for serialized use from a single decode loop.
 */
interface ScreencastFrameDecoder {
    /** Feeds the codec configuration packet (Annex-B SPS/PPS). */
    fun configure(payload: ByteArray)

    /**
     * Decodes one H.264 access unit. Returns the decoded frame bytes, or
     * `null` when the packet did not complete a decodable picture.
     */
    fun decode(payload: ByteArray): DecodedByteFrame?

    /** Drops decoder state (config, references) for a fresh session. */
    fun reset()
}
