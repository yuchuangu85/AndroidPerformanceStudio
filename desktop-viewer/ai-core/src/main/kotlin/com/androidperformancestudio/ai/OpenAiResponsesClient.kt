package com.androidperformancestudio.ai

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

public data class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

public data class AiHttpResponse(
    val statusCode: Int,
    val body: String,
)

public fun interface AiHttpTransport {
    public fun post(request: AiHttpRequest): AiHttpResponse
}

public data class StructuredAiRequest(
    val instructions: String,
    val input: String,
    val schemaName: String,
    val schemaJson: String,
)

public data class AiTextResponse(
    val model: String,
    val outputText: String,
)

public class OpenAiResponsesClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val transport: AiHttpTransport = JdkAiHttpTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    public fun execute(request: StructuredAiRequest): AiTextResponse {
        require(apiKey.isNotBlank()) { "An API key is required for AI requests" }
        val response =
            transport.post(
                AiHttpRequest(
                    url = endpoint,
                    headers =
                        mapOf(
                            "Authorization" to "Bearer $apiKey",
                            "Content-Type" to "application/json",
                        ),
                    body = buildRequestBody(request),
                ),
            )
        check(response.statusCode in SUCCESS_STATUS_CODES) {
            "AI request failed (${response.statusCode})"
        }
        return AiTextResponse(model = model, outputText = extractOutputText(response.body))
    }

    private fun buildRequestBody(request: StructuredAiRequest): String =
        buildJsonObject {
            put("model", model)
            put("instructions", request.instructions)
            put("input", request.input)
            put(
                "text",
                buildJsonObject {
                    put(
                        "format",
                        buildJsonObject {
                            put("type", "json_schema")
                            put("name", request.schemaName)
                            put("strict", true)
                            put("schema", json.parseToJsonElement(request.schemaJson))
                        },
                    )
                },
            )
        }.toString()

    private fun extractOutputText(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        root["output_text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        root["output"]?.jsonArray?.forEach { item ->
            item.jsonObject["content"]?.jsonArray?.forEach { content ->
                val contentObject = content.jsonObject
                if (contentObject["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                    return contentObject.getValue("text").jsonPrimitive.content
                }
            }
        }
        error("AI response did not contain output text")
    }

    public companion object {
        public const val DEFAULT_ENDPOINT: String = "https://api.openai.com/v1/responses"

        private val SUCCESS_STATUS_CODES: IntRange = 200..299
    }
}

public class JdkAiHttpTransport : AiHttpTransport {
    private val client: HttpClient = HttpClient.newHttpClient()

    override fun post(request: AiHttpRequest): AiHttpResponse {
        val builder =
            HttpRequest
                .newBuilder(URI.create(request.url))
                .POST(HttpRequest.BodyPublishers.ofString(request.body))
        request.headers.forEach(builder::header)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return AiHttpResponse(statusCode = response.statusCode(), body = response.body())
    }
}
