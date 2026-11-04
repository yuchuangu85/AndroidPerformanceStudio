package com.androidperformancestudio.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

public enum class AiModelCatalogFailure { HTML_RESPONSE, INVALID_RESPONSE, INVALID_ENDPOINT }

/** Stable metadata only; raw response bodies and parser exceptions must not reach settings or logs. */
public class AiModelCatalogException(
    public val reason: AiModelCatalogFailure,
) : IllegalStateException("E_AI_MODEL_CATALOG_${reason.name}")

public class OpenAiModelCatalog(
    private val apiKey: String,
    responsesEndpoint: String = OpenAiResponsesClient.DEFAULT_ENDPOINT,
    private val transport: AiHttpTransport = JdkAiHttpTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    modelsEndpointOverride: String? = null,
) {
    private val modelsEndpoint: String = modelCatalogEndpoint(responsesEndpoint, modelsEndpointOverride)
    private val fallbackEndpoint: String? =
        if (modelsEndpointOverride.isNullOrBlank() && URI(responsesEndpoint.trim().trimEnd('/')).path == "/responses") {
            URI(modelsEndpoint).resolve("/v1/models").toString()
        } else {
            null
        }

    public companion object {
        /** Validate routing before saving configuration; no client, credential or request is created. */
        public fun validateEndpoints(
            responsesEndpoint: String,
            modelsEndpointOverride: String? = null,
        ) {
            modelCatalogEndpoint(responsesEndpoint, modelsEndpointOverride)
        }
    }

    public suspend fun listModels(): List<String> {
        require(apiKey.isNotBlank()) { "An API key is required to list AI models" }
        val body = requestCatalog(modelsEndpoint)
        val models =
            try {
                parseModels(body)
            } catch (failure: AiModelCatalogException) {
                val fallback = fallbackEndpoint
                if (failure.reason != AiModelCatalogFailure.HTML_RESPONSE || fallback == null) throw failure
                // Root Responses gateways may serve a website at /models and the API at /v1/models.
                parseModels(requestCatalog(fallback))
            }
        return models.filter(::isLikelyResponsesTextModel).distinct().sorted()
    }

    private suspend fun requestCatalog(endpoint: String): String {
        val response =
            transport.executeClassified(
                AiHttpRequest(
                    url = endpoint,
                    headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
                    method = AiHttpMethod.GET,
                ),
            )
        if (response.statusCode !in AI_HTTP_SUCCESS_CODES) throw response.statusCode.toRequestException()
        return response.body
    }

    private fun parseModels(body: String): List<String> {
        if (body.length > MAX_CATALOG_CHARS) invalidCatalog()
        val payload = body.trimStart { it.isWhitespace() || it == '\uFEFF' }
        if (HTML_DOCUMENT_PREFIX.containsMatchIn(payload)) {
            throw AiModelCatalogException(AiModelCatalogFailure.HTML_RESPONSE)
        }
        val root =
            try {
                json.parseToJsonElement(payload) as? JsonObject
            } catch (_: IllegalArgumentException) {
                invalidCatalog()
            } ?: invalidCatalog()
        val data = root["data"] as? JsonArray ?: invalidCatalog()
        if (data.size > MAX_CATALOG_MODELS) invalidCatalog()
        return data.map { entry ->
            val id = (entry as? JsonObject)?.get("id") as? JsonPrimitive ?: invalidCatalog()
            val value = id.content
            if (!id.isString) invalidCatalog()
            if (value.isBlank() || value.length > MAX_MODEL_ID_CHARS || value.any(Char::isISOControl)) {
                invalidCatalog()
            }
            value
        }
    }
}

private fun modelCatalogEndpoint(
    responsesEndpoint: String,
    modelsEndpointOverride: String?,
): String {
    val normalized = responsesEndpoint.trim().trimEnd('/')
    val endpoint = parseCatalogEndpoint(normalized)
    val explicit = modelsEndpointOverride?.trim()?.takeIf(String::isNotEmpty)
    if (explicit != null) {
        val models = parseCatalogEndpoint(explicit)
        if (!endpoint.scheme.equals(models.scheme, ignoreCase = true) ||
            !endpoint.host.equals(models.host, ignoreCase = true) ||
            effectiveCatalogPort(endpoint) != effectiveCatalogPort(models)
        ) {
            invalidEndpoint()
        }
        return explicit
    }
    // A base such as /proxy/v1 is a directory, not a file to replace via URI.resolve("models").
    // Decode only the final raw segment so encoded prefixes and literal encoded slashes stay intact.
    val finalSegment = URI.create("/${endpoint.rawPath.substringAfterLast('/')}").path
    val base = if (finalSegment == "/responses") normalized.substringBeforeLast('/') else normalized
    return if (finalSegment == "/models") normalized else "$base/models"
}

private fun parseCatalogEndpoint(value: String): URI {
    val endpoint =
        try {
            URI(value)
        } catch (_: IllegalArgumentException) {
            invalidEndpoint()
        } catch (_: java.net.URISyntaxException) {
            invalidEndpoint()
        }
    validateCatalogEndpoint(endpoint)
    return endpoint
}

private fun effectiveCatalogPort(endpoint: URI): Int =
    if (endpoint.port != -1) {
        endpoint.port
    } else if (endpoint.scheme.equals("https", ignoreCase = true)) {
        HTTPS_DEFAULT_PORT
    } else {
        HTTP_DEFAULT_PORT
    }

private fun validateCatalogEndpoint(endpoint: URI) {
    if (endpoint.host.isNullOrBlank() || endpoint.isOpaque) invalidEndpoint()
    if (endpoint.userInfo != null || endpoint.rawQuery != null || endpoint.rawFragment != null) invalidEndpoint()
    if (endpoint.port !in -1..MAX_ENDPOINT_PORT || endpoint.port == 0 || endpoint.rawAuthority.endsWith(':')) {
        invalidEndpoint()
    }
    val loopback = endpoint.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    val safeTransport =
        endpoint.scheme.equals("https", ignoreCase = true) ||
            endpoint.scheme.equals("http", ignoreCase = true) &&
            loopback
    if (!safeTransport) invalidEndpoint()
}

private fun invalidCatalog(): Nothing = throw AiModelCatalogException(AiModelCatalogFailure.INVALID_RESPONSE)

private fun invalidEndpoint(): Nothing = throw AiModelCatalogException(AiModelCatalogFailure.INVALID_ENDPOINT)

private const val MAX_CATALOG_CHARS = 1_048_576
private const val MAX_CATALOG_MODELS = 10_000
private const val MAX_MODEL_ID_CHARS = 256
private const val MAX_ENDPOINT_PORT = 65_535
private const val HTTPS_DEFAULT_PORT = 443
private const val HTTP_DEFAULT_PORT = 80
private val HTML_DOCUMENT_PREFIX =
    Regex("""^(?:<!doctype\s+html(?:\s|>)|<html(?:\s|>))""", RegexOption.IGNORE_CASE)

private fun isLikelyResponsesTextModel(modelId: String): Boolean {
    // Inspect the model family without dropping the gateway namespace from the returned ID.
    val normalized = modelId.substringAfterLast('/').removePrefix("ft:").lowercase()
    val textFamily = normalized.startsWith("gpt-") || Regex("""o\d(?:-|$).*""").matches(normalized)
    // ponytail: /v1/models has no capability field; replace this name filter if the API adds one.
    val incompatible =
        listOf(
            "audio",
            "image",
            "realtime",
            "transcribe",
            "tts",
            "search",
            "embedding",
            "moderation",
            "chatgpt",
            "deep-research",
        ).any(normalized::contains)
    return textFamily && !incompatible
}
