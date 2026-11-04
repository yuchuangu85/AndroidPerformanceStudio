package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpMethod
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.ImportedAiConfiguration
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.LocalAiConfigSource
import com.androidperformancestudio.ai.LocalAiConfigurationImport
import com.androidperformancestudio.ai.LocalAiConfigurationReader
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
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class AiModelCatalogRecoveryTest {
    @Test
    fun `root HTML automatically recovers a selectable catalog without changing the connection or settings`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach(::assertRecovery)
    }

    private fun assertRecovery(language: UiLanguage) {
        val requests = CopyOnWriteArrayList<String>()
        withFixture(AiHttpTransport { request ->
            requests += request.url
            assertEquals(AiHttpMethod.GET, request.method)
            assertEquals("Bearer fixture-key", request.headers["Authorization"])
            if (request.url == "https://gateway.example/v1/models") {
                AiHttpResponse(200, """{"data":[{"id":"gpt-fixture"}]}""")
            } else {
                AiHttpResponse(200, "<!doctype html><html>private-page</html>")
            }
        }) { runtime, settings, preferences ->
            val savedPreferences = preferences.keys().associateWith { preferences.get(it, null) }
            val savedSettings = settings.load()
            runDesktopComposeUiTest(width = 1000, height = 900) {
                setContent {
                    ViewerTheme(false) {
                        Surface(Modifier.fillMaxSize()) {
                            AiSettingsContent(language, runtime, platformSettingsStore = settings)
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-picker").fetchSemanticsNode().config }
                onNodeWithTag("ai-connection-group-codex").assertExists()
                onNodeWithTag("ai-connection-group-claude").assertDoesNotExist()
                onNodeWithTag("ai-connection-group-custom").assertDoesNotExist()
                assertEquals(listOf("https://gateway.example/models", "https://gateway.example/v1/models"), requests)
                onNodeWithTag("ai-codex-model-error").assertDoesNotExist()
                onNodeWithTag("ai-codex-model-retry-v1").assertDoesNotExist()
                onNodeWithTag("ai-codex-connection-model").assertText("manual-model")
                onNodeWithTag("ai-codex-connection-endpoint").assertText("https://gateway.example/responses")
                onNodeWithTag("ai-codex-connection-models-endpoint").assertText("")
                onNodeWithTag("ai-codex-model-picker").performScrollTo()
                val pixels = onRoot().captureToImage().toPixelMap()
                val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
                }
                val output = Path.of("build/reports/ai-model-recovery/automatic-${language.name}.png")
                Files.createDirectories(output.parent)
                ImageIO.write(screenshot, "png", output.toFile())
                onNodeWithTag("ai-codex-model-picker").assertIsEnabled().performScrollTo().performClick()
                onNodeWithText("gpt-fixture").performClick()
                onNodeWithTag("ai-codex-connection-model").assertText("gpt-fixture")
                onNodeWithTag("ai-codex-connection-models-endpoint").assertText("")
                assertEquals("manual-model", runtime.aiModel())
                assertEquals("https://gateway.example/responses", runtime.aiEndpoint())
                assertEquals("", runtime.aiModelsEndpoint(), "Recovery does not implicitly save a catalog override")
                assertEquals(savedPreferences, preferences.keys().associateWith { preferences.get(it, null) })
                assertEquals(savedSettings, settings.load())
            }
        }
    }

    @Test
    fun `a failed automatic fallback does not loop save or overwrite the inference connection`() {
        val requests = CopyOnWriteArrayList<String>()
        withFixture(AiHttpTransport { request ->
            requests += request.url
            AiHttpResponse(200, "<!doctype html><html>private-page</html>")
        }) { runtime, settings, preferences ->
            val savedPreferences = preferences.keys().associateWith { preferences.get(it, null) }
            val savedSettings = settings.load()
            runDesktopComposeUiTest(width = 1000, height = 900) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.SIMPLIFIED_CHINESE, runtime, platformSettingsStore = settings)
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    requests.size == 2 && onAllNodesWithTag("ai-codex-model-error").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                onNodeWithTag("ai-codex-model-retry-v1").assertDoesNotExist()
                onNodeWithTag("ai-codex-model-picker").assertIsNotEnabled()
                onNodeWithTag("ai-codex-connection-model").assertText("manual-model")
                onNodeWithTag("ai-codex-connection-endpoint").assertText("https://gateway.example/responses")
                onNodeWithTag("ai-codex-connection-models-endpoint").assertText("")
                assertEquals(listOf("https://gateway.example/models", "https://gateway.example/v1/models"), requests)
                onNodeWithTag("ai-codex-model-refresh").assertIsEnabled().performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    requests.size == 4 && onAllNodesWithTag("ai-codex-model-error").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                assertEquals(List(2) { listOf("https://gateway.example/models", "https://gateway.example/v1/models") }.flatten(), requests)
                onNodeWithTag("ai-codex-model-retry-v1").assertDoesNotExist()
                onNodeWithTag("ai-codex-model-picker").assertIsNotEnabled()
                assertEquals("", runtime.aiModelsEndpoint())
                assertEquals("https://gateway.example/responses", runtime.aiEndpoint())
                assertEquals("manual-model", runtime.aiModel())
                assertEquals(savedPreferences, preferences.keys().associateWith { preferences.get(it, null) })
                assertEquals(savedSettings, settings.load())
            }
        }
    }

    @Test
    fun `same connection import preserves an explicit custom catalog draft without saving it`() {
        val requests = CopyOnWriteArrayList<String>()
        val catalog = "https://gateway.example/gateway/catalog/models"
        withFixture(AiHttpTransport { request ->
            requests += request.url
            assertEquals(AiHttpMethod.GET, request.method)
            assertEquals("Bearer fixture-key", request.headers["Authorization"])
            if (request.url == catalog) AiHttpResponse(200, """{"data":[{"id":"gpt-custom-fixture"}]}""")
            else AiHttpResponse(200, "<!doctype html><html>private-page</html>")
        }) { runtime, settings, preferences ->
            val savedPreferences = preferences.keys().associateWith { preferences.get(it, null) }
            val savedSettings = settings.load()
            runDesktopComposeUiTest(width = 1000, height = 900) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, runtime, localConfigReader = reader("https://gateway.example/responses"), platformSettingsStore = settings)
                    }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ai-codex-model-error").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(listOf("https://gateway.example/models", "https://gateway.example/v1/models"), requests)
                onNodeWithTag("ai-codex-connection-models-endpoint").performScrollTo().performTextReplacement(catalog)
                onNodeWithTag("ai-codex-model-refresh").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-picker").fetchSemanticsNode().config }
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { requests.size == 4 && SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-picker").fetchSemanticsNode().config }
                assertEquals(listOf("https://gateway.example/models", "https://gateway.example/v1/models", catalog, catalog), requests)
                onNodeWithTag("ai-codex-model-error").assertDoesNotExist()
                onNodeWithTag("ai-codex-model-retry-v1").assertDoesNotExist()
                onNodeWithTag("ai-codex-connection-models-endpoint").assertText(catalog)
                onNodeWithTag("ai-codex-connection-endpoint").assertText("https://gateway.example/responses")
                onNodeWithTag("ai-codex-connection-model").assertText("manual-model")
                onNodeWithTag("ai-codex-model-picker").performScrollTo().performClick()
                onNodeWithText("gpt-custom-fixture").performClick()
                onNodeWithTag("ai-codex-connection-model").assertText("gpt-custom-fixture")
                assertEquals("", runtime.aiModelsEndpoint())
                assertEquals("https://gateway.example/responses", runtime.aiEndpoint())
                assertEquals("manual-model", runtime.aiModel())
                assertEquals(savedPreferences, preferences.keys().associateWith { preferences.get(it, null) })
                assertEquals(savedSettings, settings.load())
            }
        }
    }

    @Test
    fun `importing a different connection clears its predecessor catalog override`() {
        val requests = CopyOnWriteArrayList<String>()
        withFixture(AiHttpTransport { request ->
            requests += request.url
            AiHttpResponse(200, """{"data":[{"id":"gpt-fixture"}]}""")
        }) { runtime, settings, _ ->
            runtime.saveAiConfiguration("manual-model", "https://gateway.example/responses", "https://gateway.example/private/models")
            runDesktopComposeUiTest(width = 1000, height = 900) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, runtime, localConfigReader = reader("https://other.example/responses"), platformSettingsStore = settings)
                    }
                }
                waitUntil(timeoutMillis = 10_000) { SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-picker").fetchSemanticsNode().config }
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { requests.size == 2 && SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-refresh").fetchSemanticsNode().config }
                assertEquals(listOf("https://gateway.example/private/models", "https://other.example/models"), requests)
                onNodeWithTag("ai-codex-connection-models-endpoint").assertText("")
                assertEquals("https://gateway.example/private/models", runtime.aiModelsEndpoint())
            }
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertText(value: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    private fun reader(endpoint: String) = object : LocalAiConfigurationReader {
        override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport =
            LocalAiConfigurationImport.Success(ImportedAiConfiguration(source, "fixture-key", endpoint, "manual-model"))
    }

    private fun withFixture(transport: AiHttpTransport, block: (SourceWorkspaceRuntime, AiPlatformSettingsStore, Preferences) -> Unit) {
        val root = Files.createTempDirectory("aps-model-recovery-")
        val preferences = Preferences.userRoot().node("aps-model-recovery-${UUID.randomUUID()}")
        val settingsPreferences = Preferences.userRoot().node("aps-model-recovery-provider-${UUID.randomUUID()}")
        val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
        val credentials = InMemoryCredentialStore().apply { write("openai:api-key", "fixture-key") }
        val runtime = SourceWorkspaceRuntime(
            repository,
            DefaultSourceWorkspaceService(SourceProviderRegistry(listOf(LocalSourceProvider())), repository, ContentAddressedSourceCache(root.resolve("cache"))),
            IndexedSourceResolver(repository),
            SqliteAnalysisSessionRepository(root.resolve("analysis.db")),
            credentials,
            preferences,
            modelCatalogTransport = transport,
        )
        try {
            runtime.saveAiConfiguration("manual-model", "https://gateway.example/responses")
            val settings = AiPlatformSettingsStore(settingsPreferences)
            settings.save(AiPlatformSettings(providerId = "codex", model = "manual-model"))
            block(runtime, settings, preferences)
        } finally {
            runtime.close()
            preferences.removeNode()
            settingsPreferences.removeNode()
            root.toFile().deleteRecursively()
        }
    }
}
