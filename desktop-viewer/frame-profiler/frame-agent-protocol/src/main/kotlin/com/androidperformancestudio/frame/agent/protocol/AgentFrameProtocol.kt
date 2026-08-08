@file:Suppress("MagicNumber", "ThrowsCount")

package com.androidperformancestudio.frame.agent.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

public const val JANK_STATS_RULE_ID: String = "androidx.metrics.performance.JankStats.FrameData.isJank.default"
public const val JANK_STATS_RULE_VERSION: String = "1.0.0"

@Serializable
public data class AgentFrameStages(
    val inputNs: Long? = null,
    val animationNs: Long? = null,
    val layoutMeasureNs: Long? = null,
    val drawNs: Long? = null,
    val syncNs: Long? = null,
    val commandIssueNs: Long? = null,
    val swapBuffersNs: Long? = null,
    val gpuNs: Long? = null,
)

@Serializable
public enum class AgentExpectedDurationSource {
    PLATFORM_DEADLINE,
    REFRESH_RATE,
    UNKNOWN,
}

@Serializable
public data class AgentFrameSample(
    val sequence: Long,
    val packageName: String,
    val activityName: String? = null,
    val windowId: String? = null,
    val intendedVsyncNs: Long? = null,
    val actualVsyncNs: Long? = null,
    val frameCompletedNs: Long? = null,
    val expectedDurationNs: Long? = null,
    val expectedDurationSource: AgentExpectedDurationSource = AgentExpectedDurationSource.UNKNOWN,
    val refreshRateHz: Double? = null,
    val frameTimelineVsyncId: Long? = null,
    val totalDurationNs: Long? = null,
    val stages: AgentFrameStages = AgentFrameStages(),
    val platformJank: Boolean? = null,
    val platformJankRuleId: String? = null,
    val platformJankRuleVersion: String? = null,
    val states: Map<String, String> = emptyMap(),
    val eligibleForJank: Boolean = true,
)

@Serializable
public data class AgentFrameBatch(
    val cursor: Long,
    val frames: List<AgentFrameSample> = emptyList(),
    val droppedFrames: Long = 0,
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

public class AgentFrameProtocolException(
    message: String,
) : IllegalArgumentException(message)

public class AgentFrameRemoteException(
    public val code: String,
    public val remoteMessage: String,
) : IllegalStateException("$code: $remoteMessage")

public class AgentFrameBatchCodec {
    public fun write(
        batch: AgentFrameBatch,
        output: OutputStream,
    ) {
        val payload = PROTOCOL_JSON.encodeToString(batch).toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_BATCH_BYTES) { "Frame batch payload is too large" }
        output.write("FRAMES ${payload.size}\n".toByteArray(StandardCharsets.UTF_8))
        output.write(payload)
        output.flush()
    }

    public fun read(input: InputStream): AgentFrameBatch {
        val header = readHeader(input)
        if (header.startsWith("ERROR ")) {
            val parts = header.split(' ', limit = 3)
            if (parts.size != 3 || parts[1].isBlank()) {
                throw AgentFrameProtocolException("Malformed error response")
            }
            throw AgentFrameRemoteException(parts[1], parts[2])
        }
        val parts = header.split(' ')
        if (parts.size != 2 || parts[0] != "FRAMES") {
            throw AgentFrameProtocolException("Malformed frame batch header")
        }
        val length = parts[1].toIntOrNull() ?: throw AgentFrameProtocolException("Invalid frame batch length")
        if (length !in 0..MAX_BATCH_BYTES) {
            throw AgentFrameProtocolException("Frame batch length is out of bounds")
        }
        return PROTOCOL_JSON.decodeFromString(readFully(input, length).toString(StandardCharsets.UTF_8))
    }

    private fun readHeader(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_BYTES) {
            val value = input.read()
            if (value == -1) throw AgentFrameProtocolException("Frame response ended before its header")
            if (value == '\n'.code) return bytes.toByteArray().toString(StandardCharsets.UTF_8)
            bytes += value.toByte()
        }
        throw AgentFrameProtocolException("Frame response header is too large")
    }

    private fun readFully(
        input: InputStream,
        length: Int,
    ): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw AgentFrameProtocolException("Truncated frame batch payload")
            offset += count
        }
        return result
    }

    public companion object {
        public const val MAX_BATCH_BYTES: Int = 8 * 1024 * 1024
        private const val MAX_HEADER_BYTES: Int = 128
    }
}

private val PROTOCOL_JSON =
    Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
