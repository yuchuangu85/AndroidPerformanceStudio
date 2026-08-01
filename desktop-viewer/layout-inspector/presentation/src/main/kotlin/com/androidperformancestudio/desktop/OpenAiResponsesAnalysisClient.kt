package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.JdkAiHttpTransport
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.StructuredAiRequest
import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public fun interface AiAnalysisClient {
    public suspend fun analyze(input: AiAnalysisInput): AiAnalysisReport
}

internal class OpenAiResponsesAnalysisClient(
    apiKey: String,
    model: String,
    endpoint: String = DEFAULT_ENDPOINT,
    transport: AiHttpTransport = JdkAiHttpTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AiAnalysisClient {
    private val responsesClient =
        OpenAiResponsesClient(
            apiKey = apiKey,
            model = model,
            endpoint = endpoint,
            transport = transport,
            json = json,
        )

    override suspend fun analyze(input: AiAnalysisInput): AiAnalysisReport = analyzeBlocking(input)

    fun analyzeBlocking(input: AiAnalysisInput): AiAnalysisReport {
        val response =
            responsesClient.execute(
                StructuredAiRequest(
                    instructions = SYSTEM_INSTRUCTIONS,
                    input =
                        "Analyze this Android layout snapshot JSON. " +
                            "Return only the structured JSON report.\n${input.json}",
                    schemaName = "agentperf_ai_analysis",
                    schemaJson = REPORT_SCHEMA.toString(),
                ),
            )
        val decoded = json.decodeFromString<AiResponseReportDto>(response.outputText)
        return decoded.toDomain(response.model)
    }

    companion object {
        const val DEFAULT_ENDPOINT = OpenAiResponsesClient.DEFAULT_ENDPOINT
        const val DEFAULT_MODEL = "gpt-5.6-luna"

        fun fromEnvironment(): OpenAiResponsesAnalysisClient =
            OpenAiResponsesAnalysisClient(
                apiKey = System.getenv("OPENAI_API_KEY").orEmpty(),
                model = System.getenv("AGENTPERF_AI_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
                endpoint =
                    System.getenv("OPENAI_BASE_URL")
                        ?.trimEnd('/')
                        ?.takeIf { it.isNotBlank() }
                        ?.let { baseUrl ->
                            if (baseUrl.endsWith("/v1")) "$baseUrl/responses" else "$baseUrl/v1/responses"
                        } ?: DEFAULT_ENDPOINT,
            )
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
