package com.androidperformancestudio.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonEncodingException
import kotlinx.serialization.json.jsonObject

class UnsupportedProtocolVersionException(
    val actualMajor: Int,
    val supportedMajor: Int,
) : IllegalArgumentException(
    "Unsupported protocol major $actualMajor; viewer supports $supportedMajor",
)

/**
 * Raised when a layout snapshot cannot be serialized, e.g. because a float property still
 * carries NaN/Infinity. The message is prefixed with the `encode` stage so callers that
 * aggregate per-stage capture failures surface a meaningful reason instead of a bare
 * serializer error.
 */
class SnapshotEncodeException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

class ProtocolCodec(
    private val supportedMajor: Int,
) {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encodeSnapshot(snapshot: LayoutSnapshot): String = try {
        json.encodeToString(snapshot)
    } catch (error: JsonEncodingException) {
        throw SnapshotEncodeException(
            "encode: layout snapshot serialization failed: ${error.message}",
            error,
        )
    }

    fun decodeSnapshot(value: String): LayoutSnapshot {
        val root = json.parseToJsonElement(value).jsonObject
        val version = json.decodeFromJsonElement(
            ProtocolVersion.serializer(),
            root.getValue("protocolVersion"),
        )
        if (version.major != supportedMajor) {
            throw UnsupportedProtocolVersionException(
                actualMajor = version.major,
                supportedMajor = supportedMajor,
            )
        }
        return json.decodeFromString(value)
    }
}
