package com.androidperformancestudio.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class CaptureFrame(
    val snapshotJson: String,
    val screenshotPng: ByteArray,
)

class CaptureFrameFormatException(message: String) : IllegalArgumentException(message)

class CaptureRemoteException(
    val code: String,
    val remoteMessage: String,
) : IllegalStateException("$code: $remoteMessage")

class CaptureFrameCodec {
    fun write(frame: CaptureFrame, output: OutputStream) {
        val snapshotBytes = frame.snapshotJson.toByteArray(StandardCharsets.UTF_8)
        require(snapshotBytes.size <= MAX_SNAPSHOT_BYTES) { "Snapshot payload is too large" }
        require(frame.screenshotPng.size <= MAX_SCREENSHOT_BYTES) { "Screenshot payload is too large" }
        output.write("CAPTURE ${snapshotBytes.size} ${frame.screenshotPng.size}\n".toByteArray())
        output.write(snapshotBytes)
        output.write(frame.screenshotPng)
        output.flush()
    }

    fun read(input: InputStream): CaptureFrame {
        val header = readHeader(input)
        if (header.startsWith("ERROR ")) {
            val parts = header.split(' ', limit = 3)
            if (parts.size != 3 || parts[1].isBlank()) {
                throw CaptureFrameFormatException("Malformed error response")
            }
            throw CaptureRemoteException(parts[1], parts[2])
        }
        val parts = header.split(' ')
        if (parts.size != 3 || parts[0] != "CAPTURE") {
            throw CaptureFrameFormatException("Malformed capture header")
        }
        val snapshotLength = parseLength(parts[1], MAX_SNAPSHOT_BYTES, "snapshot")
        val screenshotLength = parseLength(parts[2], MAX_SCREENSHOT_BYTES, "screenshot")
        val snapshot = readFully(input, snapshotLength, "snapshot")
        val screenshot = readFully(input, screenshotLength, "screenshot")
        return CaptureFrame(
            snapshotJson = snapshot.toString(StandardCharsets.UTF_8),
            screenshotPng = screenshot,
        )
    }

    private fun readHeader(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_BYTES) {
            val value = input.read()
            if (value == -1) throw CaptureFrameFormatException("Capture response ended before its header")
            if (value == '\n'.code) return bytes.toByteArray().toString(StandardCharsets.UTF_8)
            bytes += value.toByte()
        }
        throw CaptureFrameFormatException("Capture response header is too large")
    }

    private fun parseLength(value: String, maximum: Int, label: String): Int {
        val parsed = value.toIntOrNull()
            ?: throw CaptureFrameFormatException("Invalid $label payload length")
        if (parsed < 0 || parsed > maximum) {
            throw CaptureFrameFormatException("$label payload length is out of bounds")
        }
        return parsed
    }

    private fun readFully(input: InputStream, length: Int, label: String): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw CaptureFrameFormatException("Truncated $label payload")
            offset += count
        }
        return result
    }

    companion object {
        const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
        const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 128
    }
}
