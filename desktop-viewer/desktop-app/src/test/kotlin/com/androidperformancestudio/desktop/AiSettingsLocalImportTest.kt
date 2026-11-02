package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.AiModelCatalogException
import com.androidperformancestudio.ai.JdkAiHttpTransport
import com.androidperformancestudio.ai.ImportedAiConfiguration
import com.androidperformancestudio.ai.ImportedCredentialKind
import com.androidperformancestudio.ai.LocalAiConfigSource
import com.androidperformancestudio.ai.LocalAiConfigurationImport
import com.androidperformancestudio.ai.LocalAiConfigurationReader
import com.androidperformancestudio.ai.LocalAiImportFailure
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.source.ContentAddressedSourceCache
import com.androidperformancestudio.source.DefaultSourceWorkspaceService
import com.androidperformancestudio.source.IndexedSourceResolver
import com.androidperformancestudio.source.LocalSourceProvider
import com.androidperformancestudio.source.SourceProviderRegistry
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalTestApi::class)
class AiSettingsLocalImportTest {
    @Test
    fun `denied credential reads during Claude authentication switching clear stale catalogs without escaping the UI`() {
        withFixture(AiHttpTransport { AiHttpResponse(200, """{"data":[{"id":"claude-fixture"}]}""") }) { fixture ->
            fixture.runtime.saveClaudeCredential("fixture-key", "https://claude.example", ImportedCredentialKind.API_KEY)
            fixture.runtime.saveClaudeConfiguration("claude-model", "https://claude.example", ImportedCredentialKind.API_KEY)
            fixture.settings.save(fixture.settings.load().copy(providerId = "claude", model = "claude-model"))
            runDesktopComposeUiTest(width = 900, height = 900) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, platformSettingsStore = fixture.settings)
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    SemanticsProperties.Disabled !in onNodeWithTag("ai-claude-model-picker").fetchSemanticsNode().config
                }
                onNodeWithContentDescription("Authentication").performClick()
                onNodeWithText("Anthropic Auth Token").performClick()
                runOnIdle { fixture.credentials.failReads = true }
                onNodeWithContentDescription("Authentication").performClick()
                onNodeWithText("Anthropic API Key").performClick()
                onNodeWithTag("ai-claude-model-picker").assertIsNotEnabled()
                onNodeWithTag("ai-claude-model-refresh").assertIsNotEnabled()
                onNodeWithTag("ai-claude-connection-save").assertIsNotEnabled()
                onNodeWithTag("ai-claude-connection-model").assertEditableText("claude-model")
                assertEquals("claude", fixture.settings.load().providerId)
            }
        }
    }

    @Test
    fun `all AI connections use the shared model dropdown in both languages and themes`() {
        LocalAiConfigSource.entries.forEach { source ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    val family = if (source == LocalAiConfigSource.CLAUDE) "claude" else "gpt"
                    val tagPrefix = "ai-${source.name.lowercase()}"
                    val currentModel = "$family-current-model"
                    val otherModel = "$family-${"long-model-name-".repeat(5)}preview"
                    val calls = AtomicInteger()
                    withFixture(AiHttpTransport {
                        calls.incrementAndGet()
                        AiHttpResponse(200, """{"data":[{"id":"$currentModel"},{"id":"$otherModel"}],"has_more":false}""")
                    }) { fixture ->
                        val expectedSource = source
                        val reader = object : LocalAiConfigurationReader {
                            override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport {
                                check(source == expectedSource)
                                return LocalAiConfigurationImport.Success(
                                    ImportedAiConfiguration(source, "fixture-model-key", "https://models.example/v1/responses", currentModel),
                                )
                            }
                        }
                        runDesktopComposeUiTest(width = 720, height = 760) {
                            setContent {
                                ViewerTheme(darkTheme = dark) {
                                    Surface(Modifier.fillMaxSize()) {
                                        AiSettingsContent(language, fixture.runtime, localConfigReader = reader, platformSettingsStore = fixture.settings)
                                    }
                                }
                            }
                            selectConnection(source)
                            onNodeWithTag("$tagPrefix-model-picker").assertIsNotEnabled()
                            onNodeWithTag("ai-import-${source.name.lowercase()}").performScrollTo().performClick()
                            waitUntil(timeoutMillis = 10_000) {
                                SemanticsProperties.Disabled !in onNodeWithTag("$tagPrefix-model-picker").fetchSemanticsNode().config
                            }
                            onNode(
                                hasTestTag("dropdown-selector-expand-icon") and hasAnyAncestor(hasTestTag("$tagPrefix-model-picker")),
                                useUnmergedTree = true,
                            ).assertExists()
                            onNodeWithTag("$tagPrefix-model-picker").assertIsEnabled().performScrollTo().performClick()
                            onNodeWithTag("$tagPrefix-model-menu").assertIsDisplayed()
                            onNode(hasText(currentModel) and isSelected()).assertExists()
                            val menu = onNodeWithTag("$tagPrefix-model-menu").captureToImage().toPixelMap()
                            val screenshot = BufferedImage(menu.width, menu.height, BufferedImage.TYPE_INT_ARGB)
                            for (y in 0 until menu.height) {
                                for (x in 0 until menu.width) screenshot.setRGB(x, y, menu[x, y].toArgb())
                            }
                            val output = Path.of("build/reports/ai-model-dropdown/${source.name}-${language.name}-dark-$dark.png")
                            Files.createDirectories(output.parent)
                            ImageIO.write(screenshot, "png", output.toFile())
                            onNodeWithText(otherModel).performClick()
                            onNodeWithTag("$tagPrefix-connection-model").assertEditableText(otherModel)
                            onNodeWithTag("$tagPrefix-model-menu").assertDoesNotExist()
                            onNodeWithTag("$tagPrefix-connection-model").performScrollTo().performTextReplacement("manual-model")
                            onNodeWithTag("$tagPrefix-connection-model").assertEditableText("manual-model")
                            assertEquals(1, calls.get(), "Selecting a model must not refresh or execute a provider")
                            assertEquals(0, fixture.credentials.writes)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `Claude import automatically loads a selectable catalog with its own authentication without saving`() {
        ImportedCredentialKind.entries.forEach { kind ->
            withFixture { fixture ->
                val paths = CopyOnWriteArrayList<String>()
                val authorizations = CopyOnWriteArrayList<String>()
                val apiKeys = CopyOnWriteArrayList<String>()
                val versions = CopyOnWriteArrayList<String>()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/") { exchange ->
                    paths += exchange.requestURI.path
                    authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
                    apiKeys += exchange.requestHeaders.getFirst("x-api-key").orEmpty()
                    versions += exchange.requestHeaders.getFirst("anthropic-version").orEmpty()
                    val bytes = """{"data":[{"id":"claude-catalog-fixture"},{"id":"custom-claude-model"}],"has_more":false}""".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                server.start()
                try {
                    val endpoint = "http://127.0.0.1:${server.address.port}/gateway"
                    val reader = object : LocalAiConfigurationReader {
                        override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport =
                            LocalAiConfigurationImport.Success(ImportedAiConfiguration(source, "fixture-claude-secret", endpoint, "imported-model", kind))
                    }
                    runDesktopComposeUiTest(width = 1000, height = 1000) {
                        setContent {
                            ViewerTheme(false) {
                                AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader, platformSettingsStore = fixture.settings)
                            }
                        }
                        runOnIdle { fixture.credentials.values["openai:api-key"] = "fixture-unrelated-openai-key" }
                        selectConnection(LocalAiConfigSource.CLAUDE)
                        onNodeWithTag("ai-import-claude").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            SemanticsProperties.Disabled !in onNodeWithTag("ai-claude-model-picker").fetchSemanticsNode().config
                        }
                        onNodeWithTag("ai-claude-connection-model").assertEditableText("imported-model")
                        onNodeWithTag("ai-claude-model-picker").performScrollTo().performClick()
                        onNodeWithText("custom-claude-model").performClick()
                        onNodeWithTag("ai-claude-connection-model").assertEditableText("custom-claude-model")
                        onNodeWithTag("ai-claude-model-refresh").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { paths.size == 2 && SemanticsProperties.Disabled !in onNodeWithTag("ai-claude-model-refresh").fetchSemanticsNode().config }
                        onNodeWithTag("ai-claude-connection-model").assertEditableText("custom-claude-model")
                        assertEquals(listOf("/gateway/v1/models", "/gateway/v1/models"), paths)
                        assertEquals(List(2) { if (kind == ImportedCredentialKind.BEARER_TOKEN) "Bearer fixture-claude-secret" else "" }, authorizations)
                        assertEquals(List(2) { if (kind == ImportedCredentialKind.API_KEY) "fixture-claude-secret" else "" }, apiKeys)
                        assertEquals(List(2) { "2023-06-01" }, versions)
                        assertEquals(0, fixture.credentials.writes)
                        assertEquals("codex", fixture.settings.load().providerId)
                        assertEquals("https://api.anthropic.com", fixture.runtime.claudeEndpoint())
                        assertEquals("fixture-unrelated-openai-key", fixture.credentials.values["openai:api-key"])
                    }
                } finally {
                    server.stop(0)
                }
            }
        }
    }

    @Test
    fun `Codex import also refreshes automatically and catalog failures preserve the imported draft`() {
        val requests = CopyOnWriteArrayList<String>()
        val transport = AiHttpTransport { request ->
            requests += request.url
            assertEquals("Bearer fixture-import-key", request.headers["Authorization"])
            if (requests.size == 1) AiHttpResponse(200, """{"data":[{"id":"gpt-import-fixture"}]}""")
            else AiHttpResponse(401, "private-body fixture-import-key")
        }
        withFixture(transport) { fixture ->
            val reader = object : LocalAiConfigurationReader {
                override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport =
                    LocalAiConfigurationImport.Success(ImportedAiConfiguration(source, "fixture-import-key", "https://codex.example/v1/responses", "draft-model"))
            }
            runDesktopComposeUiTest(width = 1000, height = 1000) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader, platformSettingsStore = fixture.settings)
                    }
                }
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-picker").fetchSemanticsNode().config }
                onNodeWithTag("ai-codex-connection-model").assertEditableText("draft-model")
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Authentication failed.", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("ai-codex-connection-model").assertEditableText("draft-model")
                onNodeWithTag("ai-codex-connection-endpoint").assertEditableText("https://codex.example/v1/responses")
                onNodeWithTag("ai-codex-connection-save").assertIsEnabled()
                onNodeWithTag("ai-codex-model-picker").assertIsNotEnabled()
                onNodeWithTag("ai-import-codex").assertIsEnabled()
                assertEquals(List(2) { "https://codex.example/v1/models" }, requests)
                assertEquals(0, fixture.credentials.writes)
            }
        }
    }

    @Test
    fun `Claude catalog rejects invalid endpoints and credentials from another host or authentication kind`() {
        val requests = CopyOnWriteArrayList<String>()
        val transport = AiHttpTransport { request ->
            requests += request.url
            assertEquals("fixture-saved-key", request.headers["x-api-key"])
            AiHttpResponse(200, """{"data":[{"id":"claude-saved-fixture"}]}""")
        }
        withFixture(transport) { fixture ->
            fixture.runtime.saveClaudeCredential("fixture-saved-key", "https://saved.example", ImportedCredentialKind.API_KEY)
            fixture.runtime.saveClaudeConfiguration("saved-model", "https://saved.example", ImportedCredentialKind.API_KEY)
            val reads = fixture.credentials.reads
            assertThrows<AiModelCatalogException> { runBlocking { fixture.runtime.claudeModels("", "http://unsafe.example", ImportedCredentialKind.API_KEY) } }
            assertEquals(reads, fixture.credentials.reads)
            listOf("https://edited.example" to ImportedCredentialKind.API_KEY, "https://saved.example" to ImportedCredentialKind.BEARER_TOKEN).forEach { (endpoint, kind) ->
                val failure = assertThrows<AiModelCredentialException> { runBlocking { fixture.runtime.claudeModels("", endpoint, kind) } }
                assertTrue(failure.endpointMismatch)
            }
            assertTrue(requests.isEmpty())
            assertEquals(listOf("claude-saved-fixture"), runBlocking { fixture.runtime.claudeModels("", "https://saved.example", ImportedCredentialKind.API_KEY) })
            assertEquals(listOf("claude-saved-fixture"), runBlocking { fixture.runtime.claudeModels("fixture-saved-key", "https://edited.example", ImportedCredentialKind.API_KEY) })
            assertEquals(listOf("https://saved.example/v1/models", "https://edited.example/v1/models"), requests)
        }
    }

    @Test
    fun `saved Claude connection reloads its catalog on entry and editing the endpoint clears it`() {
        val requests = CopyOnWriteArrayList<String>()
        withFixture(AiHttpTransport { request ->
            requests += request.url
            assertEquals("Bearer fixture-saved-token", request.headers["Authorization"])
            AiHttpResponse(200, """{"data":[{"id":"claude-available-model"}]}""")
        }) { fixture ->
            fixture.runtime.saveClaudeCredential("fixture-saved-token", "https://claude.example/v1", ImportedCredentialKind.BEARER_TOKEN)
            fixture.runtime.saveClaudeConfiguration("saved-model", "https://claude.example/v1", ImportedCredentialKind.BEARER_TOKEN)
            fixture.settings.save(AiPlatformSettings(providerId = "claude", model = "saved-model"))
            runDesktopComposeUiTest(width = 1000, height = 1000) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, platformSettingsStore = fixture.settings)
                    }
                }
                waitUntil(timeoutMillis = 5000) { SemanticsProperties.Disabled !in onNodeWithTag("ai-claude-model-picker").fetchSemanticsNode().config }
                onNodeWithTag("ai-claude-connection-model").assertEditableText("saved-model")
                onNodeWithTag("ai-claude-model-picker").performScrollTo().performClick()
                onNodeWithText("claude-available-model").performClick()
                onNodeWithTag("ai-claude-connection-endpoint").performScrollTo().performTextReplacement("https://different.example")
                onNodeWithTag("ai-claude-model-picker").assertIsNotEnabled()
                onNodeWithTag("ai-claude-model-refresh").assertIsNotEnabled()
                assertEquals(listOf("https://claude.example/v1/models"), requests)
                assertEquals("https://claude.example/v1", fixture.runtime.claudeEndpoint())
                assertEquals("saved-model", fixture.settings.load().model)
            }
        }
    }

    @Test
    fun `Codex connection can persist an independent same origin catalog without changing Responses or provider`() {
        withFixture { fixture ->
            val paths = CopyOnWriteArrayList<String>()
            val key = "fixture-catalog-key"
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                paths += exchange.requestURI.path
                assertEquals("GET", exchange.requestMethod)
                assertEquals("Bearer $key", exchange.requestHeaders.getFirst("Authorization"))
                val catalog = exchange.requestURI.path == "/gateway/catalog/models"
                val body = if (catalog) "{\"data\":[{\"id\":\"gpt-catalog-fixture\"}]}" else "<!doctype html><html>private-page</html>"
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", if (catalog) "application/json" else "text/html")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
            try {
                val origin = "http://127.0.0.1:${server.address.port}"
                val responses = "$origin/responses"
                val catalog = "$origin/gateway/catalog/models"
                fixture.settings.save(AiPlatformSettings(providerId = "codex", model = "manual-fixture"))
                fixture.credentials.values["openai:api-key"] = key
                fixture.runtime.saveAiConfiguration("manual-fixture", responses)
                val savedPreferences = fixture.preferences.keys().associateWith { fixture.preferences.get(it, null) }
                val savedSettings = fixture.settings.load()
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent {
                        ViewerTheme(false) {
                            AiSettingsContent(UiLanguage.SIMPLIFIED_CHINESE, fixture.runtime, platformSettingsStore = fixture.settings)
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("独立的模型列表地址", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(listOf("/models", "/v1/models"), paths)
                    onNodeWithTag("ai-codex-model-picker").assertIsNotEnabled()
                    onNodeWithTag("ai-codex-model-retry-v1").assertDoesNotExist()
                    onNodeWithTag("ai-codex-connection-model").assertEditableText("manual-fixture")
                    onNodeWithTag("ai-codex-connection-models-endpoint").assertEditableText("")
                    assertEquals(savedPreferences, fixture.preferences.keys().associateWith { fixture.preferences.get(it, null) })
                    assertEquals(savedSettings, fixture.settings.load())
                    onNodeWithTag("ai-codex-connection-models-endpoint").performScrollTo().performTextReplacement(catalog)
                    onNodeWithTag("ai-codex-model-refresh").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-picker").fetchSemanticsNode().config
                    }
                    onNodeWithTag("ai-codex-model-picker").performScrollTo().performClick()
                    onNodeWithText("gpt-catalog-fixture").performClick()
                    onNodeWithTag("ai-codex-connection-endpoint").assertEditableText(responses)
                    onNodeWithTag("ai-codex-connection-save").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { fixture.runtime.aiModelsEndpoint() == catalog }
                    assertEquals(responses, fixture.runtime.aiEndpoint())
                    assertEquals("codex", fixture.settings.load().providerId)
                    assertEquals(1, fixture.credentials.writes, "The first Codex save preserves the legacy Custom credential")
                    assertEquals(key, fixture.runtime.credential("custom:api-key"))
                }
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent {
                        ViewerTheme(false) {
                            AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, platformSettingsStore = fixture.settings)
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-picker").fetchSemanticsNode().config
                    }
                    onNodeWithTag("ai-codex-connection-models-endpoint").assertEditableText(catalog)
                    onNodeWithTag("ai-codex-connection-endpoint").assertEditableText(responses)
                    assertEquals(listOf("/models", "/v1/models", "/gateway/catalog/models", "/gateway/catalog/models"), paths)
                    assertEquals("codex", fixture.settings.load().providerId)
                    assertEquals("gpt-catalog-fixture", fixture.runtime.aiModel())
                    assertEquals(key, fixture.credentials.values["openai:api-key"])
                }
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `catalog overrides validate before reading credentials and cannot change saved configuration on failure`() {
        withFixture { fixture ->
            val responses = "https://gateway.example/responses"
            val catalog = "https://gateway.example/v1/models"
            fixture.runtime.saveAiConfiguration("gpt-fixture", responses, catalog)
            fixture.credentials.values["openai:api-key"] = "fixture-saved-secret"
            val original = fixture.preferences.keys().associateWith { fixture.preferences.get(it, "") }
            val reads = fixture.credentials.reads
            listOf("https://other.example/v1/models", "http://gateway.example/v1/models", "https://user:private@gateway.example/models").forEach { invalid ->
                assertThrows<AiModelCatalogException> { runBlocking { fixture.runtime.aiModels("", responses, invalid) } }
                assertThrows<AiModelCatalogException> { fixture.runtime.saveAiConfiguration("replacement-model", responses, invalid) }
            }
            assertEquals(reads, fixture.credentials.reads)
            assertEquals(0, fixture.credentials.writes)
            assertEquals(original, fixture.preferences.keys().associateWith { fixture.preferences.get(it, "") })
            fixture.runtime.saveAiConfiguration("changed-model", responses)
            assertEquals(catalog, fixture.runtime.aiModelsEndpoint())
            fixture.runtime.saveAiConfiguration("new-connection", "https://gateway.example/new/responses")
            assertEquals("", fixture.runtime.aiModelsEndpoint())
            fixture.runtime.saveAiConfiguration("clear-fixture", responses, catalog)
            fixture.runtime.saveAiConfiguration("clear-fixture", responses, "")
            assertEquals("", fixture.runtime.aiModelsEndpoint())
        }
    }

    @Test
    fun `model refresh HTML malformed JSON and authentication errors are localized and never expose response or credential`() {
        val secret = "sk-fixture-model-private0123456789"
        listOf(
            Triple(200, "<!doctype html><html>private-body $secret</html>", "返回了网页"),
            Triple(200, "{broken private-body $secret", "不是有效的模型列表"),
            Triple(401, "<html>private-body $secret</html>", "认证失败"),
        ).forEach { (status, body, expected) ->
            withFixture { fixture ->
                val paths = CopyOnWriteArrayList<String>()
                val calls = AtomicInteger()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/") { exchange ->
                    paths += exchange.requestURI.path
                    assertEquals("Bearer $secret", exchange.requestHeaders.getFirst("Authorization"))
                    val first = calls.incrementAndGet() == 1
                    val content = if (first) "{\"data\":[{\"id\":\"gpt-fixture-retained\"}]}" else body
                    val bytes = content.toByteArray()
                    exchange.responseHeaders.add("Content-Type", if (first) "application/json" else "text/html")
                    exchange.sendResponseHeaders(if (first) 200 else status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                server.start()
                try {
                    val endpoint = "http://127.0.0.1:${server.address.port}/proxy/v1"
                    fixture.credentials.values["openai:api-key"] = secret
                    fixture.runtime.saveAiConfiguration("hand-entered-model", endpoint)
                    runDesktopComposeUiTest(width = 1100, height = 900) {
                        setContent {
                            ViewerTheme(false) {
                                AiSettingsContent(UiLanguage.SIMPLIFIED_CHINESE, fixture.runtime, platformSettingsStore = fixture.settings)
                            }
                        }
                        waitUntil(timeoutMillis = 10_000) { calls.get() == 1 }
                        waitUntil(timeoutMillis = 10_000) {
                            onNodeWithTag("ai-codex-model-refresh").fetchSemanticsNode().config.let { SemanticsProperties.Disabled !in it }
                        }
                        onNodeWithTag("ai-codex-connection-model").performScrollTo().performTextReplacement("keep-this-manual-model")
                        onNodeWithTag("ai-codex-model-refresh").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("ai-codex-model-error").performScrollTo().assertIsDisplayed()
                        onNodeWithTag("ai-codex-model-retry-v1").assertDoesNotExist()
                        val message =
                            onNodeWithTag("ai-codex-model-error")
                                .fetchSemanticsNode()
                                .config[SemanticsProperties.Text]
                                .joinToString { it.text }
                        assertTrue(message.contains(expected))
                        assertFalse(message.contains("private-body") || message.contains(secret) || message.contains("<!doctype", true))
                        assertFalse(message.contains("JsonDecodingException") || message.contains("offset"))
                        onNodeWithTag("ai-codex-connection-model").assertEditableText("keep-this-manual-model")
                        onNodeWithTag("ai-codex-model-picker").performScrollTo().assertIsEnabled().performClick()
                        onNodeWithText("gpt-fixture-retained").assertExists()
                        assertEquals(listOf("/proxy/v1/models", "/proxy/v1/models"), paths)
                        assertEquals(0, fixture.credentials.writes)
                    }
                } finally {
                    server.stop(0)
                }
            }
        }
    }

    @Test
    fun `model refresh never sends the saved credential to an edited endpoint but explicit key can test it`() {
        withFixture { fixture ->
            val calls = AtomicInteger()
            val authorizations = CopyOnWriteArrayList<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                calls.incrementAndGet()
                authorizations += exchange.requestHeaders.getFirst("Authorization")
                val bytes = "{\"data\":[{\"id\":\"gpt-explicit-fixture\"}]}".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
            try {
                fixture.runtime.saveAiConfiguration("saved-model", "https://saved.example/v1/responses")
                fixture.credentials.values["openai:api-key"] = "sk-saved-key-private0123456789"
                val endpoint = "http://127.0.0.1:${server.address.port}/edited/v1"
                val failure = assertThrows<IllegalStateException> { runBlocking { fixture.runtime.aiModels("", endpoint) } }
                assertEquals("E_AI_CREDENTIAL_ENDPOINT", failure.message)
                assertEquals(0, calls.get())
                assertEquals(listOf("gpt-explicit-fixture"), runBlocking { fixture.runtime.aiModels("explicit-fixture-key", endpoint) })
                assertEquals(1, calls.get())
                assertEquals(listOf("Bearer explicit-fixture-key"), authorizations)
                assertEquals("sk-saved-key-private0123456789", fixture.credentials.values["openai:api-key"])
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `Codex button fills masked connection without reading Claude or saving until explicitly requested`() {
        withFixture { fixture ->
            val calls = CopyOnWriteArrayList<LocalAiConfigSource>()
            val reader = object : LocalAiConfigurationReader {
                override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport {
                    calls += source
                    return LocalAiConfigurationImport.Success(
                        ImportedAiConfiguration(source, "fixture-codex-key", "https://codex.example/v1/responses", "fixture-codex-model"),
                    )
                }
            }
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader, platformSettingsStore = fixture.settings)
                    }
                }
                runOnIdle { assertTrue(calls.isEmpty()) }
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                awaitModel("fixture-codex-model")
                onNodeWithTag("ai-codex-connection-model").assertEditableText("fixture-codex-model")
                onNodeWithTag("ai-codex-connection-endpoint").assertEditableText("https://codex.example/v1/responses")
                onNodeWithText("Local configuration filled. Save to apply.").assertIsDisplayed()
                runOnIdle {
                    assertEquals(listOf(LocalAiConfigSource.CODEX), calls)
                    assertEquals(0, fixture.credentials.writes)
                    assertEquals(null, fixture.runtime.credential("openai:api-key"))
                    assertFalse(fixture.preferences.keys().any { it.contains("key", true) })
                }
                onNodeWithTag("ai-codex-connection-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                onNodeWithTag("ai-codex-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load().model == "fixture-codex-model" }
                runOnIdle {
                    assertEquals("fixture-codex-key", fixture.runtime.credential("openai:api-key"))
                    assertEquals("https://codex.example/v1/responses", fixture.runtime.aiEndpoint())
                    assertEquals("fixture-codex-model", fixture.runtime.aiModel())
                    assertEquals("codex", fixture.settings.load().providerId)
                }
            }
        }
    }

    @Test
    fun `Claude import saves a separate token connection and selects Claude without overwriting OpenAI`() {
        withFixture { fixture ->
            fixture.settings.save(AiPlatformSettings(providerId = "claude"))
            fixture.credentials.values["openai:api-key"] = "fixture-existing-openai-key"
            val reader = successfulReader(LocalAiConfigSource.CLAUDE, "fixture-claude-token", ImportedCredentialKind.BEARER_TOKEN)
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader, platformSettingsStore = fixture.settings)
                    }
                }
                onNodeWithTag("ai-import-claude").performScrollTo().performClick()
                awaitModel("fixture-claude-model", LocalAiConfigSource.CLAUDE)
                onNodeWithTag("ai-claude-connection-model").assertEditableText("fixture-claude-model")
                runOnIdle { assertEquals(0, fixture.credentials.writes) }
                onNodeWithTag("ai-claude-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load().model == "fixture-claude-model" }
                waitForIdle()
                runOnIdle {
                    assertEquals("fixture-existing-openai-key", fixture.runtime.credential("openai:api-key"))
                    assertEquals(
                        mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-claude-token", "ANTHROPIC_BASE_URL" to "https://claude.example"),
                        fixture.runtime.claudeEnvironment(),
                    )
                    assertEquals("claude", fixture.settings.load().providerId)
                    assertEquals("fixture-claude-model", fixture.settings.load().model)
                    assertEquals(ImportedCredentialKind.BEARER_TOKEN, fixture.runtime.claudeCredentialKind())
                    assertFalse(fixture.preferences.keys().any { fixture.preferences.get(it, "").contains("fixture-claude-token") })
                }
            }
        }
    }

    @Test
    fun `connection-only import does not reuse a previous saved credential`() {
        withFixture { fixture ->
            fixture.settings.save(AiPlatformSettings(providerId = "claude"))
            fixture.runtime.saveClaudeConfiguration("old-model", "https://old.example", ImportedCredentialKind.API_KEY)
            fixture.credentials.values["claude:api-key"] = "fixture-old-key"
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiSettingsContent(
                            UiLanguage.ENGLISH, fixture.runtime,
                            localConfigReader = successfulReader(LocalAiConfigSource.CLAUDE, null),
                            platformSettingsStore = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-import-claude").performScrollTo().performClick()
                awaitModel("fixture-claude-model", LocalAiConfigSource.CLAUDE)
                onNodeWithTag("ai-claude-connection-endpoint").assertEditableText("https://claude.example")
                onNodeWithTag("ai-claude-connection-save").assertIsNotEnabled()
                onNodeWithTag("ai-claude-model-refresh").assertIsNotEnabled()
                onNodeWithTag("ai-claude-model-picker").assertIsNotEnabled()
                runOnIdle {
                    assertEquals(0, fixture.credentials.writes)
                    assertEquals("https://old.example", fixture.runtime.claudeEndpoint())
                }
            }
        }
    }

    @Test
    fun `switching authentication after an endpoint change cannot reuse the other endpoint key`() {
        withFixture { fixture ->
            fixture.runtime.saveClaudeCredential("fixture-key-for-old-host", "https://old.example", ImportedCredentialKind.API_KEY)
            fixture.runtime.saveClaudeConfiguration("old-model", "https://old.example", ImportedCredentialKind.API_KEY)
            fixture.settings.save(AiPlatformSettings(providerId = "claude"))
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiSettingsContent(
                            UiLanguage.ENGLISH, fixture.runtime,
                            localConfigReader = successfulReader(LocalAiConfigSource.CLAUDE, "fixture-new-host-token", ImportedCredentialKind.BEARER_TOKEN),
                            platformSettingsStore = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-import-claude").performScrollTo().performClick()
                awaitModel("fixture-claude-model", LocalAiConfigSource.CLAUDE)
                onNodeWithTag("ai-claude-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load().model == "fixture-claude-model" }
                waitForIdle()
                onNodeWithContentDescription("Authentication").performScrollTo().performClick()
                onNodeWithText("Anthropic API Key").performClick()
                onNodeWithTag("ai-claude-connection-save").assertIsNotEnabled()
                runOnIdle {
                    assertFalse(fixture.runtime.hasClaudeCredential(ImportedCredentialKind.API_KEY, "https://claude.example"))
                    assertEquals("fixture-new-host-token", fixture.runtime.claudeEnvironment()["ANTHROPIC_AUTH_TOKEN"])
                    fixture.runtime.saveClaudeConfiguration("new-model", "https://claude.example", ImportedCredentialKind.API_KEY)
                    assertEquals(mapOf("ANTHROPIC_BASE_URL" to "https://claude.example"), fixture.runtime.claudeEnvironment())
                }
            }
        }
    }

    @Test
    fun `failed credential replacement invalidates its previous endpoint binding`() {
        withFixture { fixture ->
            fixture.runtime.saveClaudeCredential("fixture-old-key", "https://old.example", ImportedCredentialKind.API_KEY)
            fixture.runtime.saveClaudeConfiguration("old-model", "https://old.example", ImportedCredentialKind.API_KEY)
            fixture.credentials.failWrites = true

            assertThrows<IllegalStateException> {
                fixture.runtime.saveClaudeCredential("fixture-new-key", "https://new.example", ImportedCredentialKind.API_KEY)
            }

            assertFalse(fixture.runtime.hasClaudeCredential(ImportedCredentialKind.API_KEY, "https://old.example"))
            assertFalse(fixture.runtime.hasClaudeCredential(ImportedCredentialKind.API_KEY, "https://new.example"))
            assertEquals(mapOf("ANTHROPIC_BASE_URL" to "https://old.example"), fixture.runtime.claudeEnvironment())
        }
    }

    @Test
    fun `failed import preserves draft fields and only displays a stable localized error`() {
        withFixture { fixture ->
            val reader = object : LocalAiConfigurationReader {
                override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport =
                    LocalAiConfigurationImport.Failure(LocalAiImportFailure.MALFORMED)
            }
            val initialModel = fixture.runtime.aiModel()
            val initialEndpoint = fixture.runtime.aiEndpoint()
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader, platformSettingsStore = fixture.settings)
                    }
                }
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) {
                    onAllNodesWithText("The local configuration could not be read safely. Existing fields were kept.")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                onNodeWithTag("ai-codex-connection-model").assertEditableText(initialModel)
                onNodeWithTag("ai-codex-connection-endpoint").assertEditableText(initialEndpoint)
                onNodeWithText("The local configuration could not be read safely. Existing fields were kept.").assertIsDisplayed()
                runOnIdle { assertEquals(0, fixture.credentials.writes) }
            }
        }
    }

    @Test
    fun `invalid edited endpoint is rejected before a credential is written`() {
        withFixture { fixture ->
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiSettingsContent(
                            UiLanguage.ENGLISH, fixture.runtime,
                            localConfigReader = successfulReader(LocalAiConfigSource.CLAUDE, "fixture-must-not-save"),
                            platformSettingsStore = fixture.settings,
                        )
                    }
                }
                selectConnection(LocalAiConfigSource.CLAUDE)
                onNodeWithTag("ai-import-claude").performScrollTo().performClick()
                awaitModel("fixture-claude-model", LocalAiConfigSource.CLAUDE)
                onNodeWithTag("ai-claude-connection-endpoint").performScrollTo().performTextReplacement("http://remote.example")
                onNodeWithTag("ai-claude-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) {
                    onAllNodesWithText("The connection could not be saved.").fetchSemanticsNodes().isNotEmpty()
                }
                runOnIdle {
                    assertEquals(0, fixture.credentials.writes)
                    assertEquals("https://api.anthropic.com", fixture.runtime.claudeEndpoint())
                    assertEquals("codex", fixture.settings.load().providerId)
                }
            }
        }
    }

    @Test
    fun `local import controls render in both languages themes and compact settings widths`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                listOf(720, 1100).forEach { width ->
                    withFixture { fixture ->
                        runDesktopComposeUiTest(width = width, height = 760) {
                            setContent {
                                ViewerTheme(darkTheme = dark) {
                                    Surface(Modifier.fillMaxSize()) {
                                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                                            AiSettingsContent(
                                                language, fixture.runtime,
                                                localConfigReader = successfulReader(LocalAiConfigSource.CLAUDE, "fixture-masked-key"),
                                                platformSettingsStore = fixture.settings,
                                            )
                                        }
                                    }
                                }
                            }
                            onNodeWithTag("ai-import-codex").performScrollTo().assertIsDisplayed()
                            onNodeWithTag("ai-import-claude").assertDoesNotExist()
                            onNodeWithTag("ai-connection-group-claude").assertDoesNotExist()
                            selectConnection(LocalAiConfigSource.CLAUDE)
                            onNodeWithTag("ai-import-codex").assertDoesNotExist()
                            onNodeWithTag("ai-connection-group-codex").assertDoesNotExist()
                            onNodeWithTag("ai-import-claude").performScrollTo().assertIsDisplayed().performClick()
                            awaitModel("fixture-claude-model", LocalAiConfigSource.CLAUDE)
                            onNodeWithTag("ai-claude-connection-key").performScrollTo().assertIsDisplayed()
                            onNodeWithTag("ai-claude-connection-endpoint").performScrollTo().assertIsDisplayed()
                            onNodeWithTag("ai-claude-connection-save").performScrollTo().assertIsDisplayed()
                            if (width == 720) {
                                val pixels = onRoot().captureToImage().toPixelMap()
                                val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                                for (y in 0 until pixels.height) {
                                    for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                                }
                                val output = Path.of("build/reports/ai-settings-import/${language.name}-dark-$dark.png")
                                Files.createDirectories(output.parent)
                                ImageIO.write(image, "png", output.toFile())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DesktopComposeUiTest.selectConnection(source: LocalAiConfigSource) {
        onNodeWithTag("ai-provider-selector").performScrollTo().performClick()
        onNode(
            hasText(if (source == LocalAiConfigSource.CODEX) "Codex" else "Claude") and
                hasAnyAncestor(hasTestTag("ai-provider-menu")),
        ).performClick()
    }

    private fun DesktopComposeUiTest.awaitModel(value: String, source: LocalAiConfigSource = LocalAiConfigSource.CODEX) {
        waitUntil(timeoutMillis = 5000) {
            onNodeWithTag("ai-${source.name.lowercase()}-connection-model").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == value
        }
        waitForIdle()
    }

    private fun SemanticsNodeInteraction.assertEditableText(value: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    private fun successfulReader(expectedSource: LocalAiConfigSource, key: String?, kind: ImportedCredentialKind = ImportedCredentialKind.API_KEY): LocalAiConfigurationReader =
        object : LocalAiConfigurationReader {
            override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport {
                check(source == expectedSource)
                return LocalAiConfigurationImport.Success(ImportedAiConfiguration(source, key, "https://claude.example", "fixture-claude-model", kind))
            }
        }

    private fun withFixture(modelCatalogTransport: AiHttpTransport? = null, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("aps-ai-import-settings-")
        val preferences = Preferences.userRoot().node("aps-test-import-${UUID.randomUUID()}")
        val platformPreferences = Preferences.userRoot().node("aps-test-provider-${UUID.randomUUID()}")
        val credentials = RecordingCredentials()
        val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
        val http = JdkAiHttpTransport()
        val runtime = SourceWorkspaceRuntime(
            repository,
            DefaultSourceWorkspaceService(SourceProviderRegistry(listOf(LocalSourceProvider())), repository, ContentAddressedSourceCache(root.resolve("cache"))),
            IndexedSourceResolver(repository),
            SqliteAnalysisSessionRepository(root.resolve("analysis.db")),
            credentials,
            preferences,
            modelCatalogTransport = modelCatalogTransport ?: AiHttpTransport { request ->
                val host = java.net.URI(request.url).host
                if (host == "127.0.0.1") http.execute(request)
                else {
                    check(host.endsWith(".example")) { "Unexpected catalog request in fixture" }
                    AiHttpResponse(200, """{"data":[],"has_more":false}""")
                }
            },
        )
        try {
            val settings = AiPlatformSettingsStore(platformPreferences)
            settings.save(AiPlatformSettings(providerId = "codex"))
            block(Fixture(runtime, credentials, preferences, settings))
        } finally {
            runtime.close()
            preferences.removeNode()
            platformPreferences.removeNode()
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(val runtime: SourceWorkspaceRuntime, val credentials: RecordingCredentials, val preferences: Preferences, val settings: AiPlatformSettingsStore)

    private class RecordingCredentials : CredentialStore {
        val values = ConcurrentHashMap<String, String>()
        @Volatile var writes = 0
        @Volatile var reads = 0
        var failWrites = false
        var failReads = false
        override fun read(key: String): String? { check(!failReads) { "E_CREDENTIAL_READ" }; reads++; return values[key] }
        override fun write(key: String, value: String) { check(!failWrites); values[key] = value; writes++ }
        override fun delete(key: String) { values.remove(key) }
    }
}
