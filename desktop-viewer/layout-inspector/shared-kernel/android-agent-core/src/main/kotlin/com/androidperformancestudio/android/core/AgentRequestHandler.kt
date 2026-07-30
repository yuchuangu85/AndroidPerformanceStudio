package com.androidperformancestudio.android.core

import com.androidperformancestudio.protocol.CaptureFrame
import com.androidperformancestudio.protocol.CaptureFrameCodec
import java.io.OutputStream

fun interface CaptureProvider {
    fun capture(): CaptureFrame
}

fun interface AgentRequestExtension {
    fun handle(
        command: String,
        arguments: List<String>,
        output: OutputStream,
    ): Boolean
}

class CaptureUnavailableException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class AgentRequestHandler(
    private val token: String,
    private val captureFrameCodec: CaptureFrameCodec = CaptureFrameCodec(),
    private val extensions: List<AgentRequestExtension> = emptyList(),
    private val captureProvider: CaptureProvider,
) {
    fun handle(request: String, output: OutputStream) {
        val parts = request.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val command = parts.firstOrNull().orEmpty()
        val requestToken = parts.getOrNull(1).orEmpty()
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
            else -> {
                val handled = extensions.any { extension -> extension.handle(command, parts.drop(2), output) }
                if (!handled) writeError(output, "UNKNOWN_REQUEST", "Unsupported request")
            }
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
