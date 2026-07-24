@file:Suppress("MagicNumber", "MaxLineLength", "ThrowsCount")

package com.androidperformancestudio.startup.agent.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

@Serializable
public enum class AgentStartupMilestoneKind {
    PROCESS_START,
    INITIALIZER_ENTER,
    AGENT_READY,
    ACTIVITY_PRE_CREATE,
    ACTIVITY_CREATED,
    ACTIVITY_STARTED,
    ACTIVITY_RESUMED,
    FIRST_FRAME,
    FIRST_DRAW_CALLBACK,
    FULLY_DRAWN,
}

@Serializable
public enum class AgentEvidenceConfidence {
    EXACT,
    ESTIMATED,
    INFERRED,
    UNAVAILABLE,
}

@Serializable
public data class AgentStartupEvent(
    val sequence: Long,
    val runId: String? = null,
    val kind: AgentStartupMilestoneKind,
    val elapsedRealtimeNs: Long,
    val confidence: AgentEvidenceConfidence = AgentEvidenceConfidence.EXACT,
    val packageName: String,
    val activityName: String? = null,
    val processId: Int? = null,
    val processName: String? = null,
)

@Serializable
public data class AgentStartupResult(
    val runId: String? = null,
    val cursor: Long,
    val events: List<AgentStartupEvent> = emptyList(),
    val apiLevel: Int? = null,
    val processId: Int? = null,
    val processStartElapsedRealtimeNs: Long? = null,
    val droppedEvents: Long = 0,
    val warnings: List<String> = emptyList(),
)

public data class AgentSessionDescriptor(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val socketName: String,
    val token: String,
) {
    public companion object {
        public fun parse(value: String): AgentSessionDescriptor {
            val root = PROTOCOL_JSON.parseToJsonElement(value).jsonObject
            return AgentSessionDescriptor(
                protocolMajor =
                    root
                        .getValue("protocolMajor")
                        .jsonPrimitive.content
                        .toInt(),
                protocolMinor =
                    root
                        .getValue("protocolMinor")
                        .jsonPrimitive.content
                        .toInt(),
                socketName = root.getValue("socketName").jsonPrimitive.content,
                token = root.getValue("token").jsonPrimitive.content,
            )
        }
    }
}

public class AgentStartupProtocolException(
    message: String,
) : IllegalArgumentException(message)

public class AgentStartupRemoteException(
    public val code: String,
    public val remoteMessage: String,
) : IllegalStateException("$code: $remoteMessage")

public class AgentStartupResultCodec {
    public fun write(
        result: AgentStartupResult,
        output: OutputStream,
    ) {
        val payload = PROTOCOL_JSON.encodeToString(result).toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_RESULT_BYTES) { "Startup result payload is too large" }
        output.write("STARTUP ${payload.size}\n".toByteArray(StandardCharsets.UTF_8))
        output.write(payload)
        output.flush()
    }

    public fun read(input: InputStream): AgentStartupResult {
        val header = readHeader(input)
        if (header.startsWith("ERROR ")) {
            val parts = header.split(' ', limit = 3)
            if (parts.size != 3 || parts[1].isBlank()) throw AgentStartupProtocolException("Malformed error response")
            throw AgentStartupRemoteException(parts[1], parts[2])
        }
        val parts = header.split(' ')
        if (parts.size != 2 || parts[0] != "STARTUP") throw AgentStartupProtocolException("Malformed startup result header")
        val length = parts[1].toIntOrNull() ?: throw AgentStartupProtocolException("Invalid startup result length")
        if (length !in 0..MAX_RESULT_BYTES) throw AgentStartupProtocolException("Startup result length is out of bounds")
        return PROTOCOL_JSON.decodeFromString(readFully(input, length).toString(StandardCharsets.UTF_8))
    }

    private fun readHeader(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_BYTES) {
            val value = input.read()
            if (value == -1) throw AgentStartupProtocolException("Startup response ended before its header")
            if (value == '\n'.code) return bytes.toByteArray().toString(StandardCharsets.UTF_8)
            bytes += value.toByte()
        }
        throw AgentStartupProtocolException("Startup response header is too large")
    }

    private fun readFully(
        input: InputStream,
        length: Int,
    ): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw AgentStartupProtocolException("Truncated startup result payload")
            offset += count
        }
        return result
    }

    public companion object {
        public const val MAX_RESULT_BYTES: Int = 1024 * 1024
        private const val MAX_HEADER_BYTES: Int = 128
    }
}

private val PROTOCOL_JSON =
    Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
