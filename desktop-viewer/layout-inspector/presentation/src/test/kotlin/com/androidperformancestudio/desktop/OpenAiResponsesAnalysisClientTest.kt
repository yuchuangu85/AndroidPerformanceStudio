package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.ai.AiHttpRequest
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiResponsesAnalysisClientTest {
    @Test
    fun `posts structured responses request and parses report`() {
        var capturedRequest: AiHttpRequest? = null
        val client = OpenAiResponsesAnalysisClient(
            apiKey = "test-key",
            model = "gpt-test",
            transport = AiHttpTransport { request ->
                capturedRequest = request
                AiHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "output": [
                            {
                              "type": "message",
                              "content": [
                                {
                                  "type": "output_text",
                                  "text": "{\"summary\":\"Looks risky\",\"findings\":[{\"ruleId\":\"ai.layout\",\"severity\":\"WARNING\",\"nodeId\":\"root\",\"title\":\"Layout risk\",\"message\":\"Nested layout\",\"recommendation\":\"Flatten it\",\"confidence\":0.7}]}"
                                }
                              ]
                            }
                          ]
                        }
                    """.trimIndent(),
                )
            },
        )

        val prepared = runBlocking { client.prepare(AiAnalysisInput("{\"nodes\":[]}")) }
        assertEquals(null, capturedRequest)
        val report = runBlocking { client.analyze(prepared) }

        val request = requireNotNull(capturedRequest)
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals("Bearer test-key", request.headers["Authorization"])
        assertTrue(request.body.contains("\"type\":\"json_schema\""))
        assertTrue(request.body.contains("agentperf_ai_analysis"))
        assertTrue(request.body.contains("{\\\"nodes\\\":[]}"))
        val sentInput = Json.parseToJsonElement(request.body).jsonObject.getValue("input").jsonPrimitive.content
        assertEquals(sentInput.encodeToByteArray().size, prepared.manifest.payloadBytes)
        assertEquals("gpt-test", report.model)
        assertEquals("Looks risky", report.summary)
        assertEquals(Severity.WARNING, report.findings.single().severity)
        assertEquals("Flatten it", report.findings.single().recommendation)
    }

    @Test
    fun `oversized prepared analysis is blocked without an HTTP request`() {
        var requests = 0
        val client = OpenAiResponsesAnalysisClient(
            apiKey = "test-key",
            model = "gpt-test",
            transport = AiHttpTransport {
                requests += 1
                error("blocked analysis must not reach transport")
            },
        )

        val prepared = runBlocking { client.prepare(AiAnalysisInput("x".repeat(300_000))) }

        assertFalse(prepared.manifest.canAnalyze)
        assertThrows<IllegalStateException> { runBlocking { client.analyze(prepared) } }
        assertEquals(0, requests)
    }
}
