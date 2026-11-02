package com.androidperformancestudio.ai

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

public class ClaudeModelCatalog(
    private val apiKey: String,
    endpoint: String = "https://api.anthropic.com",
    private val credentialKind: ImportedCredentialKind = ImportedCredentialKind.API_KEY,
    private val transport: AiHttpTransport = JdkAiHttpTransport(),
) {
    private val modelsEndpoint = claudeModelsEndpoint(endpoint)

    public companion object {
        public fun validateEndpoint(endpoint: String) {
            claudeModelsEndpoint(endpoint)
        }
    }

    public suspend fun listModels(): List<String> {
        require(apiKey.isNotBlank()) { "A credential is required to list Claude models" }
        val authentication = if (credentialKind == ImportedCredentialKind.BEARER_TOKEN) {
            "Authorization" to "Bearer $apiKey"
        } else {
            "x-api-key" to apiKey
        }
        val headers = mapOf(authentication, "Accept" to "application/json", "anthropic-version" to "2023-06-01")
        val models = mutableListOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(MAX_CLAUDE_CATALOG_PAGES) {
            val suffix = cursor?.let { "?after_id=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" }.orEmpty()
            val response = transport.executeClassified(
                AiHttpRequest(url = modelsEndpoint + suffix, headers = headers, method = AiHttpMethod.GET),
            )
            if (response.statusCode !in AI_HTTP_SUCCESS_CODES) throw response.statusCode.toRequestException()
            val root = parseClaudeCatalog(response.body)
            val data = root["data"] as? JsonArray ?: invalidClaudeCatalog()
            if (data.size > MAX_CLAUDE_CATALOG_MODELS - models.size) invalidClaudeCatalog()
            val page = data.map { entry -> claudeCatalogId((entry as? JsonObject)?.get("id")) }
            models += page
            val hasMore = when (val value = root["has_more"]) {
                null -> false
                is JsonPrimitive -> if (!value.isString) value.booleanOrNull ?: invalidClaudeCatalog()
                    else invalidClaudeCatalog()
                else -> invalidClaudeCatalog()
            }
            if (!hasMore) return models.distinct().sorted()
            val nextCursor = claudeCatalogId(root["last_id"])
            if (page.isEmpty() || !cursors.add(nextCursor)) invalidClaudeCatalog()
            cursor = nextCursor
        }
        invalidClaudeCatalog()
    }
}

private fun claudeModelsEndpoint(endpoint: String): String {
    val normalized = endpoint.trim().trimEnd('/')
    OpenAiModelCatalog.validateEndpoints(normalized)
    val uri = URI(normalized)
    val finalSegment = URI.create("/${uri.rawPath.substringAfterLast('/')}").path
    return when (finalSegment) {
        "/models" -> normalized
        "/messages" -> "${normalized.substringBeforeLast('/')}/models"
        "/v1" -> "$normalized/models"
        else -> "$normalized/v1/models"
    }
}

private fun parseClaudeCatalog(body: String): JsonObject {
    if (body.length > MAX_CLAUDE_CATALOG_CHARS) invalidClaudeCatalog()
    val payload = body.trimStart { it.isWhitespace() || it == '\uFEFF' }
    if (payload.startsWith('<')) throw AiModelCatalogException(AiModelCatalogFailure.HTML_RESPONSE)
    return try {
        Json.parseToJsonElement(payload) as? JsonObject ?: invalidClaudeCatalog()
    } catch (_: IllegalArgumentException) {
        invalidClaudeCatalog()
    }
}

private fun claudeCatalogId(value: JsonElement?): String {
    val id = value as? JsonPrimitive ?: invalidClaudeCatalog()
    if (!id.isString) invalidClaudeCatalog()
    val content = id.content
    if (content.isBlank() || content.length > MAX_CLAUDE_MODEL_ID_CHARS || content.any(Char::isISOControl)) {
        invalidClaudeCatalog()
    }
    return content
}

private fun invalidClaudeCatalog(): Nothing = throw AiModelCatalogException(AiModelCatalogFailure.INVALID_RESPONSE)

private const val MAX_CLAUDE_CATALOG_CHARS = 1_048_576
private const val MAX_CLAUDE_CATALOG_MODELS = 10_000
private const val MAX_CLAUDE_MODEL_ID_CHARS = 256
private const val MAX_CLAUDE_CATALOG_PAGES = 100
