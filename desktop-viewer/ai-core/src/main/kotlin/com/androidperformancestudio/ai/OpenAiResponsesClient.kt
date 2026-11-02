package com.androidperformancestudio.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val AI_HTTP_SUCCESS_CODES: IntRange =
    HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE

public data class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String = "",
    val method: AiHttpMethod = AiHttpMethod.POST,
)

public enum class AiHttpMethod { GET, POST }

public data class AiHttpResponse(
    val statusCode: Int,
    val body: String,
)

public fun interface AiHttpTransport {
    public suspend fun execute(request: AiHttpRequest): AiHttpResponse
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

public enum class AiRequestFailureKind {
    AUTHENTICATION,
    RATE_LIMIT,
    TIMEOUT,
    NETWORK,
    HTTP,
}

public class AiRequestException(
    public val kind: AiRequestFailureKind,
    public val statusCode: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    statusCode?.let { "AI request failed ($it): ${kind.name.lowercase()}" }
        ?: "AI request failed: ${kind.name.lowercase()}",
    cause,
)

public class OpenAiResponsesClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val transport: AiHttpTransport = JdkAiHttpTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxOutputTokens: Int = 8192,
) {
    init {
        require(maxOutputTokens in 1..16_384)
    }

    public suspend fun execute(request: StructuredAiRequest): AiTextResponse {
        require(apiKey.isNotBlank()) { "An API key is required for AI requests" }
        val response =
            transport.executeClassified(
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
        if (response.statusCode !in AI_HTTP_SUCCESS_CODES) throw response.statusCode.toRequestException()
        return AiTextResponse(model = model, outputText = extractOutputText(response.body))
    }

    private fun buildRequestBody(request: StructuredAiRequest): String =
        buildJsonObject {
            put("model", model)
            put("store", false)
            put("tools", JsonArray(emptyList()))
            put("tool_choice", "none")
            put("parallel_tool_calls", false)
            put("max_output_tokens", maxOutputTokens)
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
        require(root["status"]?.jsonPrimitive?.contentOrNull.let { it == null || it == "completed" }) {
            "E_AI_RESPONSE_INCOMPLETE"
        }
        // Even a server that ignores tool_choice must not turn a tool-bearing response into success.
        root["output"]?.jsonArray?.forEach { item ->
            val type = (item as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            require(type in setOf("message", "reasoning")) { "E_AI_TOOL_RESPONSE" }
        }
        root["output"]?.jsonArray?.let { output ->
            val messages = output.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            messages.forEach { message ->
                require(message["phase"]?.jsonPrimitive?.contentOrNull in setOf(null, "commentary", "final_answer")) {
                    "E_AI_RESPONSE_PHASE"
                }
            }
            val explicitFinal = messages.filter { it["phase"]?.jsonPrimitive?.contentOrNull == "final_answer" }
            val candidates = explicitFinal.ifEmpty { messages.filter { it["phase"]?.jsonPrimitive?.contentOrNull == null } }
            val selected = candidates.singleOrNull() ?: error("E_AI_FINAL_OUTPUT_MISSING")
            val text = selected["content"]?.jsonArray?.joinToString("") { item ->
                val content = item.jsonObject
                val type = content["type"]?.jsonPrimitive?.contentOrNull
                require(type != "refusal") { "E_AI_REFUSAL" }
                require(type == "output_text") { "E_AI_RESPONSE_CONTENT" }
                content.getValue("text").jsonPrimitive.content
            }.orEmpty()
            require(text.isNotBlank()) { "E_AI_FINAL_OUTPUT_MISSING" }
            return text
        }
        root["output_text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        error("AI response did not contain output text")
    }

    public companion object {
        public const val DEFAULT_ENDPOINT: String = "https://api.openai.com/v1/responses"

    }
}

internal fun Int.toRequestException(): AiRequestException = AiRequestException(
    kind = when (this) {
        401, 403 -> AiRequestFailureKind.AUTHENTICATION
        408, 504 -> AiRequestFailureKind.TIMEOUT
        429 -> AiRequestFailureKind.RATE_LIMIT
        else -> AiRequestFailureKind.HTTP
    },
    statusCode = this,
)

internal suspend fun AiHttpTransport.executeClassified(request: AiHttpRequest): AiHttpResponse = try {
    execute(request)
} catch (failure: HttpTimeoutException) {
    throw AiRequestException(AiRequestFailureKind.TIMEOUT, cause = failure)
} catch (failure: IOException) {
    throw AiRequestException(AiRequestFailureKind.NETWORK, cause = failure)
}

public class JdkAiHttpTransport : AiHttpTransport {
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()

    override suspend fun execute(request: AiHttpRequest): AiHttpResponse {
        val builder =
            HttpRequest
                .newBuilder(URI.create(request.url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        when (request.method) {
            AiHttpMethod.GET -> builder.GET()
            AiHttpMethod.POST -> builder.POST(HttpRequest.BodyPublishers.ofString(request.body))
        }
        request.headers.forEach(builder::header)
        return suspendCancellableCoroutine { continuation ->
            val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, failure ->
                continuation.resumeWith(
                    if (failure == null) {
                        Result.success(AiHttpResponse(statusCode = response.statusCode(), body = response.body()))
                    } else {
                        Result.failure(failure.cause ?: failure)
                    },
                )
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS: Long = 15
        const val REQUEST_TIMEOUT_SECONDS: Long = 120
    }
}
