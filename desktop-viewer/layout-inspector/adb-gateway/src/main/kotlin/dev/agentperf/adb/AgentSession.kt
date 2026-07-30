package com.androidperformancestudio.adb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AgentSessionDescriptor(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val socketName: String,
    val token: String,
) {
    companion object {
        fun parse(value: String): AgentSessionDescriptor {
            val root = Json.parseToJsonElement(value).jsonObject
            return AgentSessionDescriptor(
                protocolMajor = root.getValue("protocolMajor").jsonPrimitive.int,
                protocolMinor = root.getValue("protocolMinor").jsonPrimitive.int,
                socketName = root.getValue("socketName").jsonPrimitive.content,
                token = root.getValue("token").jsonPrimitive.content,
            ).also {
                require(it.protocolMajor == 1) { "Unsupported Agent protocol ${it.protocolMajor}" }
                require(it.socketName.isNotBlank()) { "Agent socket name is missing" }
                require(it.token.isNotBlank()) { "Agent session token is missing" }
            }
        }
    }
}
