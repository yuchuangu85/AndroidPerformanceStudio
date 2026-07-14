package dev.agentperf.desktop

import dev.agentperf.analysis.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

        val report = client.analyzeBlocking(AiAnalysisInput("{\"nodes\":[]}"))

        val request = requireNotNull(capturedRequest)
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals("Bearer test-key", request.headers["Authorization"])
        assertTrue(request.body.contains("\"type\":\"json_schema\""))
        assertTrue(request.body.contains("agentperf_ai_analysis"))
        assertTrue(request.body.contains("{\\\"nodes\\\":[]}"))
        assertEquals("gpt-test", report.model)
        assertEquals("Looks risky", report.summary)
        assertEquals(Severity.WARNING, report.findings.single().severity)
        assertEquals("Flatten it", report.findings.single().recommendation)
    }
}
