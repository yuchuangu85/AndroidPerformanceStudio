package com.androidperformancestudio.ai

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ClaudeModelCatalogTest {
    @Test
    fun `Claude catalogs use provider authentication and retain custom model identifiers`() = runBlocking {
        ImportedCredentialKind.entries.forEach { kind ->
            val requests = mutableListOf<AiHttpRequest>()
            val catalog = ClaudeModelCatalog("fixture-secret", credentialKind = kind, transport = AiHttpTransport {
                requests += it
                AiHttpResponse(200, """{"data":[{"id":"claude-fixture"},{"id":"custom-model"}]}""")
            })
            assertEquals(listOf("claude-fixture", "custom-model"), catalog.listModels())
            val request = requests.single()
            assertEquals("https://api.anthropic.com/v1/models", request.url)
            assertEquals(AiHttpMethod.GET, request.method)
            assertEquals("application/json", request.headers["Accept"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
            if (kind == ImportedCredentialKind.API_KEY) {
                assertEquals("fixture-secret", request.headers["x-api-key"])
                assertNull(request.headers["Authorization"])
            } else {
                assertEquals("Bearer fixture-secret", request.headers["Authorization"])
                assertNull(request.headers["x-api-key"])
            }
        }
    }

    @Test
    fun `root versioned gateway and explicit model endpoints preserve their route`() = runBlocking {
        val cases = mapOf(
            "https://gateway.example" to "/v1/models",
            "https://gateway.example/prefix/" to "/prefix/v1/models",
            "https://gateway.example/prefix/v1/" to "/prefix/v1/models",
            "https://gateway.example/prefix/v1/messages" to "/prefix/v1/models",
            "https://gateway.example/prefix/v1/models/" to "/prefix/v1/models",
            "https://gateway.example/prefix%2Fclaude/v1/%6Dessages" to "/prefix%2Fclaude/v1/models",
            "http://127.0.0.1:12345" to "/v1/models",
            "http://[::1]:12345/v1" to "/v1/models",
        )
        cases.forEach { (endpoint, path) ->
            ClaudeModelCatalog.validateEndpoint(endpoint)
            ClaudeModelCatalog("fixture-key", endpoint, transport = AiHttpTransport {
                assertEquals(path, URI(it.url).rawPath)
                AiHttpResponse(200, """{"data":[],"has_more":false}""")
            }).listModels()
        }
    }

    @Test
    fun `pagination encodes cursors deduplicates and sorts all pages`() = runBlocking {
        val requests = mutableListOf<AiHttpRequest>()
        val catalog = ClaudeModelCatalog("fixture-key", transport = AiHttpTransport {
            requests += it
            if (requests.size == 1) AiHttpResponse(
                200,
                """{"data":[{"id":"z-model"},{"id":"a-model"}],"has_more":true,"last_id":"a/b &?"}""",
            ) else AiHttpResponse(200, """{"data":[{"id":"a-model"},{"id":"custom-model"}],"has_more":false}""")
        })
        assertEquals(listOf("a-model", "custom-model", "z-model"), catalog.listModels())
        assertEquals(2, requests.size)
        assertEquals("after_id=a%2Fb+%26%3F", URI(requests.last().url).rawQuery)
        assertEquals(requests.first().headers, requests.last().headers)
    }

    @Test
    fun `invalid endpoints fail without transport or sensitive diagnostics`() = runBlocking {
        var calls = 0
        val endpoints = listOf(
            "http://gateway.example",
            "https://user:secret@gateway.example",
            "https://gateway.example?secret=value",
            "https://gateway.example/#secret",
            "https://gateway.example:0",
            "file:///secret",
            "not a URI secret",
        )
        endpoints.forEach { endpoint ->
            val failure = assertFailsWith<AiModelCatalogException> {
                ClaudeModelCatalog("fixture-key", endpoint, transport = AiHttpTransport {
                    calls++
                    error("No transport expected")
                }).listModels()
            }
            assertEquals(AiModelCatalogFailure.INVALID_ENDPOINT, failure.reason)
            assertFalse(failure.toString().contains("secret"))
            assertNull(failure.cause)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `invalid bodies and unbounded catalogs have stable body free failures`() = runBlocking {
        val bodies = listOf(
            "private-secret-body", "[]", "{}", """{"data":{}}""", """{"data":[{}]}""",
            """{"data":[{"id":123}]}""", """{"data":[{"id":""}]}""",
            """{"data":[{"id":"private\nsecret"}]}""", """{"data":[],"has_more":"false"}""",
            """{"data":[],"has_more":true,"last_id":"cursor"}""",
            """{"data":[{"id":"model"}],"has_more":true}""",
            """{"data":[{"id":"${"x".repeat(257)}"}]}""",
            " ".repeat(1_048_577),
            """{"data":[${List(10_001) { """{"id":"model"}""" }.joinToString(",") }]}""",
        )
        bodies.forEach { body ->
            val failure = assertFailsWith<AiModelCatalogException> {
                ClaudeModelCatalog("fixture-key", transport = AiHttpTransport {
                    AiHttpResponse(200, body)
                }).listModels()
            }
            assertEquals(AiModelCatalogFailure.INVALID_RESPONSE, failure.reason)
            assertFalse(failure.toString().contains("secret"))
            assertNull(failure.cause)
        }
    }

    @Test
    fun `HTML responses are classified without exposing the page`() = runBlocking {
        val failure = assertFailsWith<AiModelCatalogException> {
            ClaudeModelCatalog("fixture-key", transport = AiHttpTransport {
                AiHttpResponse(200, "\uFEFF <!doctype html><html>private-secret-page</html>")
            }).listModels()
        }
        assertEquals(AiModelCatalogFailure.HTML_RESPONSE, failure.reason)
        assertFalse(failure.toString().contains("secret"))
        assertNull(failure.cause)
    }

    @Test
    fun `repeated pagination cursors fail instead of looping`() = runBlocking {
        var calls = 0
        assertFailsWith<AiModelCatalogException> {
            ClaudeModelCatalog("fixture-key", transport = AiHttpTransport {
                calls++
                AiHttpResponse(200, """{"data":[{"id":"model"}],"has_more":true,"last_id":"model"}""")
            }).listModels()
        }
        assertEquals(2, calls)
    }

    @Test
    fun `pagination stops at a finite page budget even when every cursor changes`() = runBlocking {
        var calls = 0
        assertFailsWith<AiModelCatalogException> {
            ClaudeModelCatalog("fixture-key", transport = AiHttpTransport {
                calls++
                AiHttpResponse(200, """{"data":[{"id":"model"}],"has_more":true,"last_id":"cursor-$calls"}""")
            }).listModels()
        }
        assertEquals(100, calls)
    }

    @Test
    fun `HTTP errors stay classified and cancellation is propagated`() = runBlocking {
        val failure = assertFailsWith<AiRequestException> {
            ClaudeModelCatalog("fixture-key", transport = AiHttpTransport {
                AiHttpResponse(401, "private-secret-body")
            }).listModels()
        }
        assertEquals(AiRequestFailureKind.AUTHENTICATION, failure.kind)
        assertFalse(failure.toString().contains("secret"))
        val cancellation = CancellationException("cancelled")
        assertEquals(cancellation, assertFailsWith<CancellationException> {
            ClaudeModelCatalog("fixture-key", transport = AiHttpTransport { throw cancellation }).listModels()
        })
    }
}
