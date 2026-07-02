package dev.agentperf.android.core

import dev.agentperf.protocol.CaptureFrame
import dev.agentperf.protocol.CaptureFrameCodec
import java.io.OutputStream

fun interface CaptureProvider {
    fun capture(): CaptureFrame
}

class CaptureUnavailableException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class AgentRequestHandler(
    private val token: String,
    private val captureFrameCodec: CaptureFrameCodec = CaptureFrameCodec(),
    private val captureProvider: CaptureProvider,
) {
    fun handle(request: String, output: OutputStream) {
        val separator = request.indexOf(' ')
        val command = if (separator < 0) request else request.substring(0, separator)
        val requestToken = if (separator < 0) "" else request.substring(separator + 1)
        if (requestToken != token) {
            writeError(output, "UNAUTHORIZED", "Invalid session token")
            return
        }
        when (command) {
            "PING" -> writeLine(output, "PONG 1.0")
            "CAPTURE" -> {
                try {
                    captureFrameCodec.write(captureProvider.capture(), output)
                } catch (error: CaptureUnavailableException) {
                    writeError(output, error.code, error.message ?: "Capture unavailable")
                }
            }
            else -> writeError(output, "UNKNOWN_REQUEST", "Unsupported request")
        }
    }

    private fun writeError(output: OutputStream, code: String, message: String) {
        writeLine(output, "ERROR $code ${message.replace('\n', ' ').replace('\r', ' ')}")
    }

    private fun writeLine(output: OutputStream, value: String) {
        output.write("$value\n".toByteArray())
        output.flush()
    }
}
