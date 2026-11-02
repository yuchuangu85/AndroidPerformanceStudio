package com.androidperformancestudio.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OpenAiModelCatalogTest {
    @Test
    fun `configuration validation shares exact routing checks without creating a catalog or transport`() {
        OpenAiModelCatalog.validateEndpoints("https://example.test/responses")
        OpenAiModelCatalog.validateEndpoints("https://example.test/responses", "https://example.test/v1/models")
        assertEquals(
            AiModelCatalogFailure.INVALID_ENDPOINT,
            assertFailsWith<AiModelCatalogException> {
                OpenAiModelCatalog.validateEndpoints(
                    "https://example.test/responses",
                    "https://other.example/v1/models",
                )
            }.reason,
        )
        assertEquals(
            AiModelCatalogFailure.INVALID_ENDPOINT,
            assertFailsWith<AiModelCatalogException> {
                OpenAiModelCatalog.validateEndpoints("not a URI secret", "https://example.test/v1/models")
            }.reason,
        )
    }

    @Test
    fun `explicit same origin Models URL is used exactly once without changing Responses route`() =
        runBlocking {
            val responses = "https://example.test/responses"
            val requests = mutableListOf<AiHttpRequest>()
            val catalog =
                OpenAiModelCatalog(
                    "secret",
                    responses,
                    AiHttpTransport {
                        requests += it
                        AiHttpResponse(200, """{"data":[{"id":"gpt-5"}]}""")
                    },
                    modelsEndpointOverride = "https://example.test/v1/models",
                )

            assertEquals(listOf("gpt-5"), catalog.listModels())
            assertEquals(1, requests.size)
            assertEquals("https://example.test/v1/models", requests.single().url)
            assertEquals(AiHttpMethod.GET, requests.single().method)
            assertEquals("Bearer secret", requests.single().headers["Authorization"])
            assertEquals("https://example.test/responses", responses)
        }

    @Test
    fun `web UI Responses URL needs an explicit v1 catalog when its sibling serves HTML`() =
        runBlocking {
            val responses = "https://example.test/ui/codex/responses"
            val siblingModels = "https://example.test/ui/codex/models"
            val apiModels = "https://example.test/v1/models"
            val requestedUrls = mutableListOf<String>()
            val transport =
                AiHttpTransport { request ->
                    requestedUrls += request.url
                    when (request.url) {
                        siblingModels -> AiHttpResponse(200, "<!doctype html><html>Codex UI</html>")
                        apiModels -> AiHttpResponse(200, """{"data":[{"id":"gpt-5"}]}""")
                        else -> error("Unexpected catalog route")
                    }
                }

            val inferredCatalog = OpenAiModelCatalog("secret", responses, transport)
            assertEquals(
                AiModelCatalogFailure.HTML_RESPONSE,
                assertFailsWith<AiModelCatalogException> { inferredCatalog.listModels() }.reason,
            )
            assertEquals(listOf(siblingModels), requestedUrls)

            val explicitCatalog =
                OpenAiModelCatalog(
                    "secret",
                    responses,
                    transport,
                    modelsEndpointOverride = apiModels,
                )
            assertEquals(listOf("gpt-5"), explicitCatalog.listModels())
            assertEquals(listOf(siblingModels, apiModels), requestedUrls)
        }

    @Test
    fun `same origin overrides compare case insensitive hosts and effective ports but preserve exact paths`() =
        runBlocking {
            val cases =
                listOf(
                    Triple(
                        "https://example.test/responses",
                        "https://EXAMPLE.test:443/v1/models",
                        "https://EXAMPLE.test:443/v1/models",
                    ),
                    Triple(
                        "http://127.0.0.1:12345/responses",
                        "http://127.0.0.1:12345/proxy/models",
                        "http://127.0.0.1:12345/proxy/models",
                    ),
                    Triple(
                        "http://[::1]:12345/responses",
                        "http://[::1]:12345/v1/models",
                        "http://[::1]:12345/v1/models",
                    ),
                )
            for ((responses, override, expected) in cases) {
                var captured: AiHttpRequest? = null
                val catalog =
                    OpenAiModelCatalog(
                        "secret",
                        responses,
                        AiHttpTransport {
                            captured = it
                            AiHttpResponse(200, """{"data":[]}""")
                        },
                        modelsEndpointOverride = override,
                    )
                assertEquals(emptyList(), catalog.listModels())
                assertEquals(expected, captured?.url)
            }
        }

    @Test
    fun `implicit root Responses catalog retries the same origin v1 catalog once after HTML`() =
        runBlocking {
            for (override in listOf(null, "", "  ")) {
                val requests = mutableListOf<AiHttpRequest>()
                OpenAiModelCatalog.validateEndpoints("https://example.test/responses", override)
                val catalog =
                    OpenAiModelCatalog(
                        "secret",
                        "https://example.test/responses",
                        AiHttpTransport {
                            requests += it
                            if (it.url == "https://example.test/models") {
                                AiHttpResponse(200, "<html>private-response</html>")
                            } else {
                                assertEquals("https://example.test/v1/models", it.url)
                                AiHttpResponse(200, """{"data":[{"id":"gpt-catalog"}]}""")
                            }
                        },
                        modelsEndpointOverride = override,
                    )
                assertEquals(listOf("gpt-catalog"), catalog.listModels())
                assertEquals(
                    listOf("https://example.test/models", "https://example.test/v1/models"),
                    requests.map { it.url },
                )
                requests.forEach {
                    assertEquals(AiHttpMethod.GET, it.method)
                    assertEquals("Bearer secret", it.headers["Authorization"])
                }
            }
        }

    @Test
    fun `root catalog JSON and explicit catalogs never trigger automatic fallback`() = runBlocking {
        for (override in listOf(null, "https://example.test/models", "https://example.test/custom/catalog")) {
            val requests = mutableListOf<String>()
            val catalog = OpenAiModelCatalog(
                "secret",
                "https://example.test/responses",
                AiHttpTransport {
                    requests += it.url
                    val body = if (override == null) """{"data":[{"id":"gpt-root"}]}""" else "<html>private</html>"
                    AiHttpResponse(200, body)
                },
                modelsEndpointOverride = override,
            )
            if (override == null) {
                assertEquals(listOf("gpt-root"), catalog.listModels())
            } else {
                assertEquals(
                    AiModelCatalogFailure.HTML_RESPONSE,
                    assertFailsWith<AiModelCatalogException> { catalog.listModels() }.reason,
                )
            }
            assertEquals(listOf(override ?: "https://example.test/models"), requests)
        }
    }

    @Test
    fun `root catalog HTTP and malformed JSON errors do not trigger a route probe`() = runBlocking {
        for (response in listOf(
            AiHttpResponse(401, "<html>private</html>"),
            AiHttpResponse(403, "<html>private</html>"),
            AiHttpResponse(429, "<html>private</html>"),
            AiHttpResponse(503, "<html>private</html>"),
            AiHttpResponse(200, "private-not-json"),
        )) {
            var calls = 0
            val catalog = OpenAiModelCatalog("secret", "https://example.test/responses", AiHttpTransport {
                calls++
                response
            })
            assertFailsWith<IllegalStateException> { catalog.listModels() }
            assertEquals(1, calls)
        }
        var calls = 0
        val catalog = OpenAiModelCatalog("secret", "https://example.test/responses", AiHttpTransport {
            calls++
            throw java.io.IOException("fixture")
        })
        assertEquals(AiRequestFailureKind.NETWORK, assertFailsWith<AiRequestException> { catalog.listModels() }.kind)
        assertEquals(1, calls)
    }

    @Test
    fun `XML and angle prefixed garbage do not authorize the HTML route fallback`() = runBlocking {
        for (body in listOf("<?xml version=\"1.0\"?><Error>AccessDenied</Error>", "<", "<private>", "<htmlish>")) {
            var calls = 0
            val catalog = OpenAiModelCatalog("secret", "https://example.test/responses", AiHttpTransport {
                calls++
                AiHttpResponse(200, body)
            })
            val failure = assertFailsWith<AiModelCatalogException> { catalog.listModels() }
            assertEquals(AiModelCatalogFailure.INVALID_RESPONSE, failure.reason)
            assertNull(failure.cause)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `failed fallback stops after two requests and never exposes response bodies`() = runBlocking {
        val failures = listOf(AiHttpResponse(200, "<html>private-secret</html>"), AiHttpResponse(401, "private-secret"))
        for (fallback in failures) {
            val requests = mutableListOf<String>()
            val catalog = OpenAiModelCatalog("secret", "https://example.test/responses", AiHttpTransport {
                requests += it.url
                if (requests.size == 1) AiHttpResponse(200, "<html>private-secret</html>") else fallback
            })
            val failure = assertFailsWith<IllegalStateException> { catalog.listModels() }
            assertFalse(failure.toString().contains("private-secret"))
            assertNull(failure.cause)
            assertEquals(listOf("https://example.test/models", "https://example.test/v1/models"), requests)
        }
    }

    @Test
    fun `invalid and different origin Models URLs reject before transport without exposing credentials`() =
        runBlocking {
            val cases =
                listOf(
                    "https://other.example/v1/models",
                    "https://example.test:444/v1/models",
                    "http://example.test/v1/models",
                    "http://127.0.0.1:12345/v1/models",
                    "https://user:secret@example.test/v1/models",
                    "https://example.test/v1/models?key=secret",
                    "https://example.test/v1/models#secret",
                    "https://example.test:0/v1/models",
                    "https://example.test:65536/v1/models",
                    "https://example.test:/v1/models",
                    "/v1/models",
                    "not a URI secret",
                )
            var calls = 0
            for (override in cases) {
                val failure =
                    assertFailsWith<AiModelCatalogException> {
                        OpenAiModelCatalog(
                            "secret",
                            "https://example.test/responses",
                            AiHttpTransport {
                                calls++
                                error("No transport")
                            },
                            modelsEndpointOverride = override,
                        ).listModels()
                    }
                assertEquals(AiModelCatalogFailure.INVALID_ENDPOINT, failure.reason)
                assertFalse(failure.toString().contains("secret"))
                assertFalse(failure.toString().contains(override.takeIf(String::isNotBlank) ?: "never-body"))
                assertNull(failure.cause)
            }
            assertEquals(0, calls)
        }

    @Test
    fun `loopback Models override cannot change the host port or protocol`() =
        runBlocking {
            var calls = 0
            for (override in listOf(
                "http://localhost:12345/v1/models",
                "http://127.0.0.1:12346/v1/models",
                "https://127.0.0.1:12345/v1/models",
            )) {
                assertEquals(
                    AiModelCatalogFailure.INVALID_ENDPOINT,
                    assertFailsWith<AiModelCatalogException> {
                        OpenAiModelCatalog(
                            "secret",
                            "http://127.0.0.1:12345/responses",
                            AiHttpTransport {
                                calls++
                                error("No transport")
                            },
                            modelsEndpointOverride = override,
                        ).listModels()
                    }.reason,
                )
            }
            assertEquals(0, calls)
        }

    @Test
    fun `API bases retain their final path segment and full Responses URLs use their sibling catalog`() =
        runBlocking {
            val cases =
                mapOf(
                    "https://example.test/v1" to "https://example.test/v1/models",
                    "https://example.test/v1/" to "https://example.test/v1/models",
                    "https://example.test/proxy/openai/v1" to "https://example.test/proxy/openai/v1/models",
                    "https://example.test/proxy/openai/v1/responses/" to "https://example.test/proxy/openai/v1/models",
                    "https://example.test/v1/models" to "https://example.test/v1/models",
                    "https://example.test/proxy/v1/%72esponses" to "https://example.test/proxy/v1/models",
                    "https://example.test/proxy/v1/%6Dodels/" to "https://example.test/proxy/v1/%6Dodels",
                    "https://example.test/proxy%2Fopenai/v1/responses" to
                        "https://example.test/proxy%2Fopenai/v1/models",
                    "https://example.test/v1%2Fresponses" to "https://example.test/v1%2Fresponses/models",
                    "https://example.test" to "https://example.test/models",
                    "http://127.0.0.1:12345/v1" to "http://127.0.0.1:12345/v1/models",
                    "http://[::1]:12345/v1/responses" to "http://[::1]:12345/v1/models",
                )
            for ((endpoint, expected) in cases) {
                var request: AiHttpRequest? = null
                val catalog =
                    OpenAiModelCatalog(
                        "secret",
                        endpoint,
                        AiHttpTransport {
                            request = it
                            AiHttpResponse(200, """{"data":[{"id":"gpt-5"}]}""")
                        },
                    )
                assertEquals(listOf("gpt-5"), catalog.listModels())
                assertEquals(expected, request?.url)
                assertEquals("application/json", request?.headers?.get("Accept"))
            }
        }

    @Test
    fun `successful HTML is not parsed or exposed as JSON and never retried`() =
        runBlocking {
            var attempts = 0
            val catalog =
                OpenAiModelCatalog(
                    "secret",
                    "https://example.test/v1",
                    AiHttpTransport {
                        attempts++
                        AiHttpResponse(200, "\uFEFF  <!doctype html>\n<html lang=\"zh-CN\">secret-private-page</html>")
                    },
                )
            val failure = assertFailsWith<AiModelCatalogException> { catalog.listModels() }
            assertEquals(AiModelCatalogFailure.HTML_RESPONSE, failure.reason)
            assertFalse(failure.toString().contains("secret"))
            assertFalse(failure.toString().contains("doctype"))
            assertNull(failure.cause)
            assertEquals(1, attempts)
        }

    @Test
    fun `malformed JSON and unexpected catalog shapes have a stable body free failure`() =
        runBlocking {
            val bodies =
                listOf(
                    "private-response",
                    "{\"secret\":",
                    "[]",
                    "null",
                    "{}",
                    "{\"data\":{}}",
                    "{\"data\":[{}]}",
                    "{\"data\":[{\"id\":1}]}",
                    "{\"data\":[{\"id\":\"\"}]}",
                    "{\"data\":[{\"id\":\"gpt-5\\nsecret\"}]}",
                    " ".repeat(1_048_577),
                )
            for (body in bodies) {
                val catalog = OpenAiModelCatalog("secret", transport = AiHttpTransport { AiHttpResponse(200, body) })
                val failure = assertFailsWith<AiModelCatalogException> { catalog.listModels() }
                assertEquals(AiModelCatalogFailure.INVALID_RESPONSE, failure.reason)
                assertFalse(failure.toString().contains("secret"))
                assertNull(failure.cause)
            }
        }

    @Test
    fun `credential bearing decorated and non loopback HTTP endpoints fail before transport`() =
        runBlocking {
            val endpoints =
                listOf(
                    "http://example.test/v1",
                    "https://user:secret@example.test/v1",
                    "https://example.test/v1?key=secret",
                    "https://example.test/v1#secret",
                    "not a URI",
                    "https://example.test:0/v1",
                    "https://example.test:65536/v1",
                    "http://localhost.example.test/v1",
                )
            var calls = 0
            for (endpoint in endpoints) {
                val failure =
                    assertFailsWith<AiModelCatalogException> {
                        OpenAiModelCatalog(
                            "secret",
                            endpoint,
                            AiHttpTransport {
                                calls++
                                error("No transport")
                            },
                        ).listModels()
                    }
                assertEquals(AiModelCatalogFailure.INVALID_ENDPOINT, failure.reason)
                assertFalse(failure.toString().contains("secret"))
            }
            assertEquals(0, calls)
        }

    @Test
    fun `HTTP errors keep existing classification without exposing an HTML error page`() =
        runBlocking {
            val catalog =
                OpenAiModelCatalog(
                    "secret",
                    transport =
                        AiHttpTransport {
                            AiHttpResponse(401, "<html>secret</html>")
                        },
                )
            val failure = assertFailsWith<AiRequestException> { catalog.listModels() }
            assertEquals(AiRequestFailureKind.AUTHENTICATION, failure.kind)
            assertFalse(failure.toString().contains("secret"))
        }

    @Test
    fun `namespaced text model ids retain their full routing identity`() =
        runBlocking {
            val catalog = OpenAiModelCatalog(
                "secret",
                transport = AiHttpTransport {
                    AiHttpResponse(
                        200,
                        """{"data":[
                            {"id":"gateway/gpt-5"},
                            {"id":"gateway/o3"},
                            {"id":"region/team/gpt-codex-fixture"},
                            {"id":"provider/ft:gpt-5:org:copy"},
                            {"id":"ft:provider/gpt-5:org:copy"},
                            {"id":"image-proxy/gpt-text-fixture"},
                            {"id":"gateway/gpt-5"}
                        ]}""",
                    )
                },
            )
            assertEquals(
                listOf(
                    "gateway/gpt-5",
                    "gateway/o3",
                    "region/team/gpt-codex-fixture",
                    "provider/ft:gpt-5:org:copy",
                    "ft:provider/gpt-5:org:copy",
                    "image-proxy/gpt-text-fixture",
                ).sorted(),
                catalog.listModels(),
            )
        }

    @Test
    fun `namespaces do not bypass multimedia and unrelated model exclusions`() =
        runBlocking {
            val catalog = OpenAiModelCatalog(
                "secret",
                transport = AiHttpTransport {
                    AiHttpResponse(
                        200,
                        """{"data":[
                            {"id":"gateway/gpt-image-1"},
                            {"id":"gateway/gpt-audio-fixture"},
                            {"id":"gateway/gpt-realtime-fixture"},
                            {"id":"gateway/text-embedding-3-small"},
                            {"id":"gpt-proxy/claude-fixture"},
                            {"id":"gpt-proxy/unknown-model"},
                            {"id":"gateway/gpt-fixture/"}
                        ]}""",
                    )
                },
            )
            assertEquals(emptyList(), catalog.listModels())
        }

    @Test
    fun `loads only likely Responses text models from the configured endpoint`() =
        runBlocking {
            var captured: AiHttpRequest? = null
            val catalog =
                OpenAiModelCatalog(
                    apiKey = "secret",
                    responsesEndpoint = "https://example.test/openai/v1/responses",
                    transport =
                        AiHttpTransport { request ->
                            captured = request
                            AiHttpResponse(
                                200,
                                """{"data":[{"id":"text-embedding-3-small"},{"id":"gpt-5"},""" +
                                    """{"id":"gpt-image-1"},{"id":"o3"}]}""",
                            )
                        },
                )

            assertEquals(listOf("gpt-5", "o3"), catalog.listModels())
            assertEquals("https://example.test/openai/v1/models", captured?.url)
            assertEquals(AiHttpMethod.GET, captured?.method)
            assertEquals("Bearer secret", captured?.headers?.get("Authorization"))
        }
}
