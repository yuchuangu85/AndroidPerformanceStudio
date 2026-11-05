package com.androidperformancestudio.screencast.session

/**
 * One decoded screencast frame as tightly packed BGRA bytes (Skia N32
 * layout). One allocation per frame; consumers must not hold references
 * across frames — the decoder may reuse internal buffers.
 */
data class DecodedByteFrame(
    val bgra: ByteArray,
    val width: Int,
    val height: Int,
)
