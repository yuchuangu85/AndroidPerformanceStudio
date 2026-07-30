package com.androidperformancestudio.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class UnsupportedProtocolVersionException(
    val actualMajor: Int,
    val supportedMajor: Int,
) : IllegalArgumentException(
    "Unsupported protocol major $actualMajor; viewer supports $supportedMajor",
)

class ProtocolCodec(
    private val supportedMajor: Int,
) {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encodeSnapshot(snapshot: LayoutSnapshot): String =
        json.encodeToString(snapshot)

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
