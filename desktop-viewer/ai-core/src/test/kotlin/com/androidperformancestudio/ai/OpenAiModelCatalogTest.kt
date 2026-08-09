package com.androidperformancestudio.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class OpenAiModelCatalogTest {
    @Test
    fun `loads only likely Responses text models from the configured endpoint`() = runBlocking {
        var captured: AiHttpRequest? = null
        val catalog = OpenAiModelCatalog(
            apiKey = "secret",
            responsesEndpoint = "https://example.test/openai/v1/responses",
            transport = AiHttpTransport { request ->
                captured = request
                AiHttpResponse(
                    200,
                    """{"data":[{"id":"text-embedding-3-small"},{"id":"gpt-5"},{"id":"gpt-image-1"},{"id":"o3"}]}""",
                )
            },
        )

        assertEquals(listOf("gpt-5", "o3"), catalog.listModels())
        assertEquals("https://example.test/openai/v1/models", captured?.url)
        assertEquals(AiHttpMethod.GET, captured?.method)
        assertEquals("Bearer secret", captured?.headers?.get("Authorization"))
    }
}
