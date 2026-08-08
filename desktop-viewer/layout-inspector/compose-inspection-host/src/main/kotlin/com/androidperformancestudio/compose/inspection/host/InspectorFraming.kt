package com.androidperformancestudio.compose.inspection.host

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object InspectorFraming {
    private val header = "UIINSPCT".toByteArray(Charsets.US_ASCII)
    const val MAX_MESSAGE_BYTES = 64 * 1024 * 1024

    fun write(output: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_MESSAGE_BYTES) { "Inspector message exceeds 64 MiB" }
        synchronized(output) {
            DataOutputStream(output).apply {
                write(header)
                writeInt(payload.size)
                write(payload)
                flush()
            }
        }
    }

    fun read(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        require(data.readNBytes(header.size).contentEquals(header)) { "Invalid inspector frame header" }
        val size = data.readInt()
        require(size in 0..MAX_MESSAGE_BYTES) { "Invalid inspector frame size: $size" }
        return ByteArray(size).also(data::readFully)
    }
}
