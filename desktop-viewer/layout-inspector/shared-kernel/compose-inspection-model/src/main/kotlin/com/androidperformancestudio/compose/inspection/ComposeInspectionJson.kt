package com.androidperformancestudio.compose.inspection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UnsupportedComposeInspectionSchemaException(val actual: Int) :
    IllegalArgumentException("Unsupported Compose inspection schema $actual")

class ComposeInspectionJson(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {
    fun encode(document: ComposeInspectionDocument): String = json.encodeToString(document)

    fun decode(value: String): ComposeInspectionDocument {
        val schemaVersion = json.parseToJsonElement(value).jsonObject
            .getValue("schemaVersion").jsonPrimitive.content.toInt()
        if (schemaVersion != COMPOSE_INSPECTION_SCHEMA_VERSION) {
            throw UnsupportedComposeInspectionSchemaException(schemaVersion)
        }
        return json.decodeFromString(value)
    }
}
