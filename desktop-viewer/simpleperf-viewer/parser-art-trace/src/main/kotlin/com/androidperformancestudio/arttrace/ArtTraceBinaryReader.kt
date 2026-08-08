package com.androidperformancestudio.arttrace

/**
 * Bounds-checked little-endian reader over the raw `.trace` bytes. Every read either succeeds or
 * throws [ArtTraceFormatException]; the parser catches it and reports a structured [ArtTraceParseResult.Failure]
 * so a truncated file never crashes the app.
 */
internal class ArtTraceFormatException(message: String) : Exception(message)

internal class ArtTraceBinaryReader(
    private val bytes: ByteArray,
) {
    var position: Int = 0

    val isAtEnd: Boolean
        get() = position >= bytes.size

    fun remaining(): Int = bytes.size - position

    fun readU8(): Int {
        requireAvailable(1)
        return bytes[position++].toInt() and 0xff
    }

    fun readU16(): Int {
        requireAvailable(2)
        val value = (bytes[position].toInt() and 0xff) or ((bytes[position + 1].toInt() and 0xff) shl 8)
        position += 2
        return value
    }

    fun readU24(): Int {
        requireAvailable(3)
        val value =
            (bytes[position].toInt() and 0xff) or
                ((bytes[position + 1].toInt() and 0xff) shl 8) or
                ((bytes[position + 2].toInt() and 0xff) shl 16)
        position += 3
        return value
    }

    fun readU32(): Long {
        requireAvailable(4)
        var value = 0L
        repeat(4) { index ->
            value = value or ((bytes[position + index].toLong() and 0xff) shl (8 * index))
        }
        position += 4
        return value
    }

    fun readU64(): Long {
        requireAvailable(8)
        var value = 0L
        repeat(8) { index ->
            value = value or ((bytes[position + index].toLong() and 0xff) shl (8 * index))
        }
        position += 8
        return value
    }

    fun readUleb128(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            requireAvailable(1)
            val byte = bytes[position++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
            if (shift >= 70) throw ArtTraceFormatException("uleb128 overflow")
        }
        return result
    }

    fun readSleb128(): Long {
        var result = 0L
        var shift = 0
        var byte: Int
        while (true) {
            requireAvailable(1)
            byte = bytes[position++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            shift += 7
            if (byte and 0x80 == 0) break
            if (shift >= 70) throw ArtTraceFormatException("sleb128 overflow")
        }
        if (byte and 0x40 != 0) {
            result = result or (-1L shl shift)
        }
        return result
    }

    fun readBytes(count: Int): ByteArray {
        requireAvailable(count)
        val result = bytes.copyOfRange(position, position + count)
        position += count
        return result
    }

    fun skip(count: Int) {
        requireAvailable(count)
        position += count
    }

    /** Consumes bytes up to and including the first occurrence of [needle] (or to EOF). */
    fun readUntilInclusive(needle: ByteArray): ByteArray {
        val out = ArrayList<Byte>(needle.size * 2)
        while (position < bytes.size) {
            val byte = bytes[position++]
            out += byte
            if (out.size >= needle.size) {
                var match = true
                for (index in needle.indices) {
                    if (out[out.size - needle.size + index] != needle[index]) {
                        match = false
                        break
                    }
                }
                if (match) break
            }
        }
        return out.toByteArray()
    }

    private fun requireAvailable(count: Int) {
        if (position + count > bytes.size) {
            throw ArtTraceFormatException("truncated trace at offset $position (need $count bytes)")
        }
    }
}
