package com.androidperformancestudio.ai

import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
                                  "output": [{"type":"message",
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
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("none", body.getValue("tool_choice").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), body.getValue("tools"))
        assertEquals(false, body.getValue("store").jsonPrimitive.boolean)
        assertEquals(false, body.getValue("parallel_tool_calls").jsonPrimitive.boolean)
        assertEquals(8192, body.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals("gpt-test", response.model)
        assertEquals("{\"summary\":\"ok\"}", response.outputText)
    }

    @Test
    fun `tool-bearing output is rejected even if the server also provides final text`() = runBlocking {
        for (type in listOf("function_call", "web_search_call", "computer_call", "file_search_call", "local_shell_call", "unknown_tool")) {
            val client = OpenAiResponsesClient("fixture", "codex-fixture", transport = AiHttpTransport {
                AiHttpResponse(200, """{"output_text":"{}","output":[{"type":"$type"}]}""")
            })
            val failure = assertFailsWith<IllegalArgumentException> {
                client.execute(StructuredAiRequest("bounded", "{}", "analysis", """{"type":"object"}"""))
            }
            assertEquals("E_AI_TOOL_RESPONSE", failure.message)
        }
    }

    @Test
    fun `incomplete response never publishes partial text`() = runBlocking {
        val client = OpenAiResponsesClient("fixture", "codex-fixture", transport = AiHttpTransport {
            AiHttpResponse(200, """{"status":"incomplete","output_text":"{}"}""")
        })
        assertEquals("E_AI_RESPONSE_INCOMPLETE", assertFailsWith<IllegalArgumentException> {
            client.execute(StructuredAiRequest("bounded", "{}", "analysis", """{"type":"object"}"""))
        }.message)
    }

    @Test
    fun `explicit output budget is bounded and sent unchanged`(): Unit = runBlocking {
        var captured = ""
        val client = OpenAiResponsesClient("fixture", "codex-fixture", maxOutputTokens = 256, transport = AiHttpTransport {
            captured = it.body
            AiHttpResponse(200, """{"output_text":"{}"}""")
        })
        client.execute(StructuredAiRequest("bounded", "{}", "analysis", """{"type":"object"}"""))
        assertEquals(256, Json.parseToJsonElement(captured).jsonObject.getValue("max_output_tokens").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { OpenAiResponsesClient("fixture", "codex-fixture", maxOutputTokens = 0) }
    }

    @Test
    fun `Codex commentary is not mistaken for the final structured answer`() = runBlocking {
        val client = OpenAiResponsesClient("fixture", "codex-fixture", transport = AiHttpTransport {
            AiHttpResponse(200, """{"output_text":"wrong alias","output":[
                {"type":"reasoning","summary":[]},
                {"type":"message","phase":"commentary","content":[{"type":"output_text","text":"Inspecting synthetic evidence"}]},
                {"type":"message","phase":"final_answer","content":[{"type":"output_text","text":"{\"summary\":"},{"type":"output_text","text":"\"ok\"}"}]}
            ]}""")
        })
        val response = client.execute(StructuredAiRequest("bounded", "{}", "analysis", """{"type":"object"}"""))
        assertEquals("""{"summary":"ok"}""", response.outputText)
    }

    @Test
    fun `refusal is rejected instead of trusting an output text alias`() = runBlocking {
        val client = OpenAiResponsesClient("fixture", "codex-fixture", transport = AiHttpTransport {
            AiHttpResponse(200, """{"output_text":"{}","output":[{"type":"message","phase":"final_answer","content":[{"type":"refusal","refusal":"No"}]}]}""")
        })
        assertEquals("E_AI_REFUSAL", assertFailsWith<IllegalArgumentException> {
            client.execute(StructuredAiRequest("bounded", "{}", "analysis", """{"type":"object"}"""))
        }.message)
    }

    @Test
    fun `commentary only is not a completed analysis`() = runBlocking {
        val client = OpenAiResponsesClient("fixture", "codex-fixture", transport = AiHttpTransport {
            AiHttpResponse(200, """{"output_text":"{}","output":[{"type":"message","phase":"commentary","content":[{"type":"output_text","text":"Still analyzing"}]}]}""")
        })
        assertEquals("E_AI_FINAL_OUTPUT_MISSING", assertFailsWith<IllegalStateException> {
            client.execute(StructuredAiRequest("bounded", "{}", "analysis", """{"type":"object"}"""))
        }.message)
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
