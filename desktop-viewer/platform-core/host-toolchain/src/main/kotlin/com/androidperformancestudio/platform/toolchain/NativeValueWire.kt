package com.androidperformancestudio.platform.toolchain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Frozen v1 wire shared with native engines; limits apply before allocation. */
object NativeValueWire {
    const val MAX_FRAME_BYTES = 32 * 1024 * 1024
    const val MAX_STRING_BYTES = 4 * 1024 * 1024
    const val MAX_COUNT = 1_000_000
    const val MAX_DEPTH = 64

    fun decodeSingleFrame(bytes: ByteArray): Any? {
        if (bytes.size > MAX_FRAME_BYTES + Int.SIZE_BYTES) throw IOException("Excessive native response")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val value = readFrame(input)
        if (input.available() != 0) throw IOException("Trailing native response frames")
        return value
    }

    fun readFrame(input: DataInputStream): Any? {
        val length = input.readInt().toLong() and 0xffff_ffffL
        if (length !in 1..MAX_FRAME_BYTES.toLong()) throw IOException("Invalid native frame length: $length")
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        val frame = DataInputStream(ByteArrayInputStream(bytes))
        val value = readValue(frame, 0, Budget())
        if (frame.available() != 0) throw IOException("Trailing bytes in native frame")
        return value
    }

    fun writeFrame(
        output: DataOutputStream,
        value: Any?,
    ) {
        val bytes = BoundedBytes()
        writeValue(DataOutputStream(bytes), value, 0, Budget())
        output.writeInt(bytes.size())
        bytes.writeTo(output)
    }

    private fun readValue(
        input: DataInputStream,
        depth: Int,
        budget: Budget,
    ): Any? {
        budget.consume(depth)
        return when (val tag = input.readUnsignedByte()) {
            0 -> null
            1 -> false
            2 -> true
            3 -> input.readLong()
            4 -> readString(input)
            5 -> {
                val count = readCount(input, input.available())
                buildList { repeat(count) { add(readValue(input, depth + 1, budget)) } }
            }
            6 -> {
                val count = readCount(input, input.available() / 5)
                val values = linkedMapOf<String, Any?>()
                repeat(count) {
                    val key = readString(input)
                    if (values.containsKey(key)) throw IOException("Duplicate native map key")
                    values[key] = readValue(input, depth + 1, budget)
                }
                values
            }
            else -> throw IOException("Invalid native value tag: $tag")
        }
    }

    private fun readCount(
        input: DataInputStream,
        remaining: Int,
    ): Int {
        val count = input.readInt().toLong() and 0xffff_ffffL
        if (count > MAX_COUNT || count > remaining) throw IOException("Excessive native collection count")
        return count.toInt()
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt().toLong() and 0xffff_ffffL
        if (length > MAX_STRING_BYTES || length > input.available()) throw IOException("Invalid native string length")
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        return Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun writeValue(
        output: DataOutputStream,
        value: Any?,
        depth: Int,
        budget: Budget,
    ) {
        budget.consume(depth)
        when (value) {
            null -> output.writeByte(0)
            false -> output.writeByte(1)
            true -> output.writeByte(2)
            is Long -> {
                output.writeByte(3)
                output.writeLong(value)
            }
            is Int -> {
                output.writeByte(3)
                output.writeLong(value.toLong())
            }
            is String -> {
                output.writeByte(4)
                writeString(output, value)
            }
            is List<*> -> {
                checkCount(value.size)
                output.writeByte(5)
                output.writeInt(value.size)
                value.forEach { writeValue(output, it, depth + 1, budget) }
            }
            is Map<*, *> -> {
                checkCount(value.size)
                output.writeByte(6)
                output.writeInt(value.size)
                value.forEach { (key, item) ->
                    writeString(output, key as? String ?: throw IOException("Native map key must be a string"))
                    writeValue(output, item, depth + 1, budget)
                }
            }
            else -> throw IOException("Unsupported native value type")
        }
    }

    private fun writeString(
        output: DataOutputStream,
        value: String,
    ) {
        if (value.length > MAX_STRING_BYTES) throw IOException("Excessive native string")
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) throw IOException("Excessive native string")
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun checkCount(count: Int) {
        if (count > MAX_COUNT) throw IOException("Excessive native collection count")
    }

    private class Budget {
        private var values = 0

        fun consume(depth: Int) {
            if (depth > MAX_DEPTH || ++values > MAX_COUNT) throw IOException("Excessive native nesting or value count")
        }
    }

    private class BoundedBytes : ByteArrayOutputStream() {
        override fun write(value: Int) {
            ensureCapacityFor(1)
            super.write(value)
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            ensureCapacityFor(length)
            super.write(bytes, offset, length)
        }

        private fun ensureCapacityFor(length: Int) {
            if (length > MAX_FRAME_BYTES - size()) throw IOException("Native frame exceeds size limit")
        }
    }
}
