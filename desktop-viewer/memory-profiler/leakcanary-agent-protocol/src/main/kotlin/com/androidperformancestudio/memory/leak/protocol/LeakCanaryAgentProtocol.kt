package com.androidperformancestudio.memory.leak.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

public const val LEAK_CANARY_AGENT_PROTOCOL_VERSION: Int = 1
public const val LEAK_CANARY_AGENT_PORT: Int = 49_376

@Serializable
public data class LeakCanaryAgentSessionDescriptor(
    val protocolMajor: Int = LEAK_CANARY_AGENT_PROTOCOL_VERSION,
    val protocolMinor: Int = 0,
    val socketPort: Int = LEAK_CANARY_AGENT_PORT,
    val token: String,
    val packageName: String,
    val processName: String? = null,
)

public object LeakCanaryAgentSessionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    public fun encode(descriptor: LeakCanaryAgentSessionDescriptor): String =
        json.encodeToString(LeakCanaryAgentSessionDescriptor.serializer(), descriptor)

    public fun decode(value: String): LeakCanaryAgentSessionDescriptor =
        json.decodeFromString(LeakCanaryAgentSessionDescriptor.serializer(), value)
}

@Serializable
public data class LeakCanaryAgentCommand(
    val type: String,
    val protocolVersion: Int = LEAK_CANARY_AGENT_PROTOCOL_VERSION,
    val token: String? = null,
    val cursor: Long = 0L,
    val maxEvents: Int = 500,
    val sessionId: String? = null,
)

@Serializable
public data class LeakCanaryAgentEvent(
    val sequence: Long,
    val kind: String,
    val monotonicNs: Long,
    val packageName: String,
    val processName: String? = null,
    val processId: Int? = null,
    val className: String? = null,
    val description: String? = null,
    val watchId: String? = null,
    val retained: Boolean? = null,
    val source: String = "aps-bridge",
)

@Serializable
public data class LeakCanaryAgentResponse(
    val type: String,
    val protocolVersion: Int = LEAK_CANARY_AGENT_PROTOCOL_VERSION,
    val packageName: String? = null,
    val processId: Int? = null,
    val sessionId: String? = null,
    val events: List<LeakCanaryAgentEvent> = emptyList(),
    val droppedEvents: Long = 0L,
    val latestSequence: Long = 0L,
    val activeWatchCount: Int = 0,
    val leakCanaryAvailable: Boolean = false,
    val message: String? = null,
)

public object LeakCanaryAgentCodec {
    private const val MAX_FRAME_BYTES: Int = 4 * 1024 * 1024
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    public fun writeCommand(
        output: OutputStream,
        command: LeakCanaryAgentCommand,
    ) {
        write(output, json.encodeToString(LeakCanaryAgentCommand.serializer(), command))
    }

    public fun readCommand(input: InputStream): LeakCanaryAgentCommand =
        json.decodeFromString(LeakCanaryAgentCommand.serializer(), read(input))

    public fun writeResponse(
        output: OutputStream,
        response: LeakCanaryAgentResponse,
    ) {
        write(output, json.encodeToString(LeakCanaryAgentResponse.serializer(), response))
    }

    public fun readResponse(input: InputStream): LeakCanaryAgentResponse =
        json.decodeFromString(LeakCanaryAgentResponse.serializer(), read(input))

    private fun write(
        output: OutputStream,
        value: String,
    ) {
        val payload = value.toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_FRAME_BYTES) { "LeakCanary Agent frame is too large" }
        DataOutputStream(output).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    private fun read(input: InputStream): String {
        val stream = DataInputStream(input)
        val length = stream.readInt()
        require(length in 1..MAX_FRAME_BYTES) { "Invalid LeakCanary Agent frame length: $length" }
        return ByteArray(length).also(stream::readFully).toString(StandardCharsets.UTF_8)
    }
}
