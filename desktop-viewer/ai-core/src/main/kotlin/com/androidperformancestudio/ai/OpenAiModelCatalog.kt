package com.androidperformancestudio.ai

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public class OpenAiModelCatalog(
    private val apiKey: String,
    responsesEndpoint: String = OpenAiResponsesClient.DEFAULT_ENDPOINT,
    private val transport: AiHttpTransport = JdkAiHttpTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val modelsEndpoint: String = modelCatalogEndpoint(responsesEndpoint)

    public suspend fun listModels(): List<String> {
        require(apiKey.isNotBlank()) { "An API key is required to list AI models" }
        val response = transport.executeClassified(
            AiHttpRequest(
                url = modelsEndpoint,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                method = AiHttpMethod.GET,
            ),
        )
        if (response.statusCode !in AI_HTTP_SUCCESS_CODES) throw response.statusCode.toRequestException()
        return json.parseToJsonElement(response.body).jsonObject
            .getValue("data").jsonArray
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .filter(::isLikelyResponsesTextModel)
            .distinct()
            .sorted()
    }
}

private fun modelCatalogEndpoint(responsesEndpoint: String): String {
    val endpoint = URI.create(responsesEndpoint.trimEnd('/'))
    require(endpoint.scheme == "https" || endpoint.host == "localhost") {
        "AI endpoint must use HTTPS (or localhost for development)"
    }
    return endpoint.resolve("models").toString()
}

private fun isLikelyResponsesTextModel(modelId: String): Boolean {
    val normalized = modelId.removePrefix("ft:").lowercase()
    val textFamily = normalized.startsWith("gpt-") || Regex("""o\d(?:-|$).*""").matches(normalized)
    // ponytail: /v1/models has no capability field; replace this name filter if the API adds one.
    val incompatible = listOf(
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
