package com.androidperformancestudio.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiResponsesClientTest {
    @Test
    fun `builds a structured request and extracts nested output text`() {
        var capturedRequest: AiHttpRequest? = null
        val client =
            OpenAiResponsesClient(
                apiKey = "test-key",
                model = "gpt-test",
                transport =
                    AiHttpTransport { request ->
                        capturedRequest = request
                        AiHttpResponse(
                            statusCode = 200,
                            body =
                                """
                                {
                                  "output": [{
                                    "content": [{"type":"output_text","text":"{\"summary\":\"ok\"}"}]
                                  }]
                                }
                                """.trimIndent(),
                        )
                    },
            )

        val response =
            client.execute(
                StructuredAiRequest(
                    instructions = "Return JSON",
                    input = "snapshot",
                    schemaName = "analysis",
                    schemaJson = """{"type":"object"}""",
                ),
            )

        val request = requireNotNull(capturedRequest)
        assertEquals(OpenAiResponsesClient.DEFAULT_ENDPOINT, request.url)
        assertEquals("Bearer test-key", request.headers["Authorization"])
        assertTrue(request.body.contains("\"name\":\"analysis\""))
        assertEquals("gpt-test", response.model)
        assertEquals("{\"summary\":\"ok\"}", response.outputText)
    }

    @Test
    fun `rejects unsuccessful responses`() {
        val client =
            OpenAiResponsesClient(
                apiKey = "test-key",
                model = "gpt-test",
                transport = AiHttpTransport { AiHttpResponse(statusCode = 429, body = "rate limited") },
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                client.execute(
                    StructuredAiRequest(
                        instructions = "Return JSON",
                        input = "snapshot",
                        schemaName = "analysis",
                        schemaJson = """{"type":"object"}""",
                    ),
                )
            }

        assertEquals("AI request failed (429)", failure.message)
    }
}
