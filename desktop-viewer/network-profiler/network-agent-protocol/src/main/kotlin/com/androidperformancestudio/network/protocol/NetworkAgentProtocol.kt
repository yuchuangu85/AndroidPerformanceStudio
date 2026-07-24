package com.androidperformancestudio.network.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

public const val NETWORK_AGENT_PROTOCOL_VERSION: Int = 1
public const val NETWORK_AGENT_PORT: Int = 49_373

@Serializable
public data class AgentCommand(
    val type: String,
    val protocolVersion: Int = NETWORK_AGENT_PROTOCOL_VERSION,
    val token: String? = null,
    val maxEvents: Int = 500,
)

@Serializable
public data class AgentNetworkEvent(
    val sequence: Long,
    val callId: String,
    val kind: String,
    val monotonicNs: Long,
    val method: String? = null,
    val url: String? = null,
    val statusCode: Int? = null,
    val byteCount: Long? = null,
    val protocol: String? = null,
    val connectionId: String? = null,
    val message: String? = null,
)

@Serializable
public data class AgentResponse(
    val type: String,
    val protocolVersion: Int = NETWORK_AGENT_PROTOCOL_VERSION,
    val processId: Int? = null,
    val events: List<AgentNetworkEvent> = emptyList(),
    val droppedEvents: Long = 0,
    val deviceMonotonicNs: Long = System.nanoTime(),
    val message: String? = null,
)

public object NetworkAgentCodec {
    private const val MAX_FRAME_BYTES: Int = 4 * 1024 * 1024
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun writeCommand(output: OutputStream, command: AgentCommand) = write(output, json.encodeToString(AgentCommand.serializer(), command))

    public fun readCommand(input: InputStream): AgentCommand = json.decodeFromString(AgentCommand.serializer(), read(input))

    public fun writeResponse(output: OutputStream, response: AgentResponse) = write(output, json.encodeToString(AgentResponse.serializer(), response))

    public fun readResponse(input: InputStream): AgentResponse = json.decodeFromString(AgentResponse.serializer(), read(input))

    private fun write(output: OutputStream, value: String) {
        val payload = value.toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_FRAME_BYTES) { "Network Agent frame is too large" }
        DataOutputStream(output).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    private fun read(input: InputStream): String {
        val stream = DataInputStream(input)
        val length = stream.readInt()
        require(length in 1..MAX_FRAME_BYTES) { "Invalid Network Agent frame length: $length" }
        return ByteArray(length).also(stream::readFully).toString(StandardCharsets.UTF_8)
    }
}
