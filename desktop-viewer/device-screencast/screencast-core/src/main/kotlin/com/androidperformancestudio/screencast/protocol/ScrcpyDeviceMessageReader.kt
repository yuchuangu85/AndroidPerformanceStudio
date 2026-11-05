package com.androidperformancestudio.screencast.protocol

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads device messages sent from the scrcpy server over the control socket
 * (scrcpy v3.1 `DeviceMessageWriter` format).
 */
class ScrcpyDeviceMessageReader(
    rawInputStream: InputStream,
) : AutoCloseable {
    private val input = DataInputStream(BufferedInputStream(rawInputStream))

    /**
     * Reads the next device message, blocking until one is available.
     * Returns `null` on a clean end of stream.
     */
    @Suppress("SwallowedException")
    @Throws(IOException::class)
    fun readMessage(): DeviceMessage? {
        val type = try {
            input.readUnsignedByte()
        } catch (error: EOFException) {
            return null
        }
        return when (type) {
            TYPE_CLIPBOARD -> {
                val length = input.readInt()
                require(length >= 0 && length <= CLIPBOARD_TEXT_MAX_LENGTH) {
                    "Invalid clipboard message length: $length"
                }
                val raw = ByteArray(length)
                input.readFully(raw)
                DeviceMessage.Clipboard(String(raw, Charsets.UTF_8))
            }
            TYPE_ACK_CLIPBOARD -> DeviceMessage.AckClipboard(input.readLong())
            TYPE_UHID_OUTPUT -> {
                val id = input.readUnsignedShort()
                val size = input.readUnsignedShort()
                val data = ByteArray(size)
                input.readFully(data)
                DeviceMessage.UhidOutput(id, data)
            }
            else -> throw IOException("Unknown scrcpy device message type: $type")
        }
    }

    override fun close() {
        input.close()
    }

    sealed interface DeviceMessage {
        /** The device clipboard changed to [text]. */
        data class Clipboard(
            val text: String,
        ) : DeviceMessage

        /** A set-clipboard request with [sequence] completed. */
        data class AckClipboard(
            val sequence: Long,
        ) : DeviceMessage

        data class UhidOutput(
            val id: Int,
            val data: ByteArray,
        ) : DeviceMessage
    }

    companion object {
        const val TYPE_CLIPBOARD = 0
        const val TYPE_ACK_CLIPBOARD = 1
        const val TYPE_UHID_OUTPUT = 2

        // scrcpy server DeviceMessageWriter: MESSAGE_MAX_SIZE - 5.
        const val CLIPBOARD_TEXT_MAX_LENGTH = (1 shl 18) - 5
    }
}
