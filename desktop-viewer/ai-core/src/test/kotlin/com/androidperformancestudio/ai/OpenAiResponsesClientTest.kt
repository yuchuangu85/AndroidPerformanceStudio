package com.androidperformancestudio.ai

import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OpenAiResponsesClientTest {
    @Test
    fun `builds a structured request and extracts nested output text`() = runBlocking {
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
    fun `rejects unsuccessful responses`() = runBlocking {
        val client =
            OpenAiResponsesClient(
                apiKey = "test-key",
                model = "gpt-test",
                transport = AiHttpTransport { AiHttpResponse(statusCode = 429, body = "rate limited") },
            )

        val failure =
            assertFailsWith<AiRequestException> {
                client.execute(
                    StructuredAiRequest(
                        instructions = "Return JSON",
                        input = "snapshot",
                        schemaName = "analysis",
                        schemaJson = """{"type":"object"}""",
                    ),
                )
            }

        assertEquals(AiRequestFailureKind.RATE_LIMIT, failure.kind)
        assertEquals(429, failure.statusCode)
    }

    @Test
    fun `classifies transport timeout`() = runBlocking {
        val client = OpenAiResponsesClient(
            apiKey = "test-key",
            model = "gpt-test",
            transport = AiHttpTransport { throw HttpTimeoutException("timed out") },
        )

        val failure = assertFailsWith<AiRequestException> {
            client.execute(
                StructuredAiRequest("Return JSON", "snapshot", "analysis", """{"type":"object"}"""),
            )
        }

        assertEquals(AiRequestFailureKind.TIMEOUT, failure.kind)
    }

    @Test
    fun `classifies transport network failure`() = runBlocking {
        val client = OpenAiResponsesClient(
            apiKey = "test-key",
            model = "gpt-test",
            transport = AiHttpTransport { throw IOException("offline") },
        )

        val failure = assertFailsWith<AiRequestException> {
            client.execute(
                StructuredAiRequest("Return JSON", "snapshot", "analysis", """{"type":"object"}"""),
            )
        }

        assertEquals(AiRequestFailureKind.NETWORK, failure.kind)
    }
}
