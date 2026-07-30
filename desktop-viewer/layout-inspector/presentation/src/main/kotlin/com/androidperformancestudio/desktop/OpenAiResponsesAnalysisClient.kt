package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.Severity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun interface AiAnalysisClient {
    suspend fun analyze(input: AiAnalysisInput): AiAnalysisReport
}

internal data class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

internal data class AiHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface AiHttpTransport {
    fun post(request: AiHttpRequest): AiHttpResponse
}

internal class OpenAiResponsesAnalysisClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val transport: AiHttpTransport = JdkAiHttpTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AiAnalysisClient {
    override suspend fun analyze(input: AiAnalysisInput): AiAnalysisReport = analyzeBlocking(input)

    fun analyzeBlocking(input: AiAnalysisInput): AiAnalysisReport {
        require(apiKey.isNotBlank()) { "OPENAI_API_KEY is required for AI analysis" }
        val response = transport.post(
            AiHttpRequest(
                url = endpoint,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                ),
                body = buildRequestBody(input),
            ),
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("AI analysis request failed (${response.statusCode})")
        }
        val outputText = extractOutputText(response.body)
        val decoded = json.decodeFromString<AiResponseReportDto>(outputText)
        return decoded.toDomain(model)
    }

    private fun buildRequestBody(input: AiAnalysisInput): String = buildJsonObject {
        put("model", model)
        put("instructions", SYSTEM_INSTRUCTIONS)
        put(
            "input",
            "Analyze this Android layout snapshot JSON. Return only the structured JSON report.\n${input.json}",
        )
        put("text", buildJsonObject {
            put("format", buildJsonObject {
                put("type", "json_schema")
                put("name", "agentperf_ai_analysis")
                put("strict", true)
                put("schema", REPORT_SCHEMA)
            })
        })
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
        throw IllegalStateException("AI analysis response did not contain output text")
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/responses"
        const val DEFAULT_MODEL = "gpt-5.6-luna"

        fun fromEnvironment(): OpenAiResponsesAnalysisClient = OpenAiResponsesAnalysisClient(
            apiKey = System.getenv("OPENAI_API_KEY").orEmpty(),
            model = System.getenv("AGENTPERF_AI_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
            endpoint = System.getenv("OPENAI_BASE_URL")
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { baseUrl ->
                    if (baseUrl.endsWith("/v1")) "$baseUrl/responses" else "$baseUrl/v1/responses"
                }
                ?: DEFAULT_ENDPOINT,
        )
    }
}

private class JdkAiHttpTransport : AiHttpTransport {
    private val client = HttpClient.newHttpClient()

    override fun post(request: AiHttpRequest): AiHttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(request.url))
            .POST(HttpRequest.BodyPublishers.ofString(request.body))
        request.headers.forEach(builder::header)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return AiHttpResponse(statusCode = response.statusCode(), body = response.body())
    }
}

@Serializable
private data class AiResponseReportDto(
    val summary: String,
    val findings: List<AiResponseFindingDto> = emptyList(),
)

@Serializable
private data class AiResponseFindingDto(
    val ruleId: String,
    val severity: String,
    val nodeId: String,
    val title: String,
    val message: String,
    val recommendation: String,
    val confidence: Float,
)

private fun AiResponseReportDto.toDomain(model: String) = AiAnalysisReport(
    model = model,
    summary = summary,
    findings = findings.map {
        AiFinding(
            ruleId = it.ruleId,
            severity = runCatching { Severity.valueOf(it.severity) }.getOrDefault(Severity.INFO),
            nodeId = it.nodeId,
            title = it.title,
            message = it.message,
            recommendation = it.recommendation,
            confidence = it.confidence.coerceIn(0f, 1f),
        )
    },
)

private const val SYSTEM_INSTRUCTIONS = """
You are AndroidPerfermanceStudio's Android UI performance reviewer. Use only the provided layout JSON. Do not infer private user text because text values are redacted. Focus on actionable layout, rendering, accessibility, and hierarchy risks. Keep findings concise and attach each finding to the most relevant nodeId.
"""

private val REPORT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", buildJsonArray {
        addString("summary")
        addString("findings")
    })
    put("properties", buildJsonObject {
        put("summary", buildJsonObject { put("type", "string") })
        put("findings", buildJsonObject {
            put("type", "array")
            put("maxItems", 8)
            put("items", buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("required", buildJsonArray {
                    addString("ruleId")
                    addString("severity")
                    addString("nodeId")
                    addString("title")
                    addString("message")
                    addString("recommendation")
                    addString("confidence")
                })
                put("properties", buildJsonObject {
                    put("ruleId", buildJsonObject { put("type", "string") })
                    put("severity", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            addString("INFO")
                            addString("WARNING")
                            addString("ERROR")
                        })
                    })
                    put("nodeId", buildJsonObject { put("type", "string") })
                    put("title", buildJsonObject { put("type", "string") })
                    put("message", buildJsonObject { put("type", "string") })
                    put("recommendation", buildJsonObject { put("type", "string") })
                    put("confidence", buildJsonObject {
                        put("type", "number")
                        put("minimum", 0)
                        put("maximum", 1)
                    })
                })
            })
        })
    })
}

private fun JsonArrayBuilder.addString(value: String) {
    add(kotlinx.serialization.json.JsonPrimitive(value))
}

private typealias JsonArrayBuilder = kotlinx.serialization.json.JsonArrayBuilder
