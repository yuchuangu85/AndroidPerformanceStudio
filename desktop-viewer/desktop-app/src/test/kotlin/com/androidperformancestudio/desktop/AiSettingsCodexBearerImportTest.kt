package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.androidperformancestudio.ai.AiHttpMethod
import com.androidperformancestudio.ai.AiHttpRequest
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.LocalAiConfigSource
import com.androidperformancestudio.ai.LocalAiConfigurationImporter
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class AiSettingsCodexBearerImportTest {
    @Test
    fun `Codex imports a provider bearer token with OpenAI auth without fallback or implicit saves`() {
        val requests = CopyOnWriteArrayList<AiHttpRequest>()
        withFixture(AiHttpTransport { request ->
            requests += request
            assertEquals(AiHttpMethod.GET, request.method)
            assertEquals("http://127.0.0.1:12345/v1/models", request.url)
            assertEquals("Bearer fixture-provider-token", request.headers["Authorization"])
            AiHttpResponse(200, """{"data":[{"id":"gpt-bearer-fixture"}]}""")
        }) { fixture ->
            val directory = Files.createDirectories(fixture.home.resolve(".codex"))
            Files.writeString(
                directory.resolve("config.toml"),
                """
                model = "gpt-bearer-fixture"
                model_provider = "gateway"

                [model_providers.gateway]
                base_url = "http://127.0.0.1:12345/v1"
                wire_api = "responses"
                requires_openai_auth = true
                experimental_bearer_token = "fixture-provider-token"
                """.trimIndent(),
            )
            // A provider token must bypass the official auth file, even when it cannot be parsed.
            Files.writeString(directory.resolve("auth.json"), "{invalid-json")
            val importer = LocalAiConfigurationImporter(home = fixture.home, environment = emptyMap())
            val savedModel = fixture.runtime.aiModel()
            val savedEndpoint = fixture.runtime.aiEndpoint()
            val savedClaudeModel = fixture.runtime.claudeModel()
            val savedClaudeEndpoint = fixture.runtime.claudeEndpoint()
            val savedClaudeKind = fixture.runtime.claudeCredentialKind()
            val savedSettings = fixture.settings.load()
            val savedPlatformPreferences = fixture.platformPreferences.keys().associateWith { fixture.platformPreferences.get(it, null) }

            runDesktopComposeUiTest(width = 1100, height = 1400) {
                setContent {
                    ViewerTheme(false) {
                        Surface(Modifier.fillMaxSize()) {
                            AiSettingsPageContent(
                                language = UiLanguage.ENGLISH,
                                runtime = fixture.runtime,
                                localConfigReader = importer,
                                platformSettingsStore = fixture.settings,
                            )
                        }
                    }
                }
                onNodeWithTag("ai-settings-page").assertIsDisplayed()
                onNodeWithTag("ai-settings-category").assertIsDisplayed()
                onNodeWithTag("ai-connection-group-codex").assertIsDisplayed()
                onNodeWithTag("ai-import-codex")
                    .assert(hasAnyAncestor(hasTestTag("ai-connection-group-codex")))
                onNodeWithTag("ai-import-claude").assertDoesNotExist()
                onNodeWithTag("ai-connection-group-claude").assertDoesNotExist()
                selectConnection(LocalAiConfigSource.CLAUDE)
                onNodeWithTag("ai-import-codex").assertDoesNotExist()
                onNodeWithTag("ai-connection-group-codex").assertDoesNotExist()
                onNodeWithTag("ai-claude-connection-model").performScrollTo()
                    .performTextReplacement("claude-independent-draft")
                onNodeWithTag("ai-claude-connection-endpoint").performScrollTo()
                    .performTextReplacement("https://claude-fixture.example")
                val claudeDraft = listOf("key", "model", "endpoint").associateWith { field ->
                    onNodeWithTag("ai-claude-connection-$field")
                        .fetchSemanticsNode().config[SemanticsProperties.EditableText]
                }
                assertTrue(requests.isEmpty())

                selectConnection(LocalAiConfigSource.CODEX)
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) {
                    onNodeWithTag("ai-codex-connection-model")
                        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "gpt-bearer-fixture"
                }
                waitUntil(timeoutMillis = 5000) {
                    SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-model-picker").fetchSemanticsNode().config
                }
                onNodeWithTag("ai-codex-model-picker").performScrollTo().assertIsEnabled()
                onNodeWithTag("ai-codex-connection-model").assertEditableText("gpt-bearer-fixture")
                onNodeWithTag("ai-codex-connection-endpoint").assertEditableText("http://127.0.0.1:12345/v1/responses")
                onNodeWithTag("ai-codex-connection-key")
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                onNodeWithTag("ai-codex-import-status").assertExists()
                onNodeWithTag("ai-codex-import-error").assertDoesNotExist()
                onNodeWithTag("ai-codex-model-error").assertDoesNotExist()
                selectConnection(LocalAiConfigSource.CLAUDE)
                claudeDraft.forEach { (field, text) ->
                    onNodeWithTag("ai-claude-connection-$field")
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, text))
                }
                assertEquals(1, requests.size)
                assertTrue(fixture.credentialWrites.isEmpty())
                assertNull(fixture.runtime.credential("openai:api-key"))
                assertNull(fixture.runtime.credential("claude:api-key"))
                assertNull(fixture.runtime.credential("claude:auth-token"))
                assertTrue(fixture.preferences.keys().isEmpty())
                assertEquals(savedPlatformPreferences, fixture.platformPreferences.keys().associateWith { fixture.platformPreferences.get(it, null) })
                assertEquals(savedModel, fixture.runtime.aiModel())
                assertEquals(savedEndpoint, fixture.runtime.aiEndpoint())
                assertEquals("", fixture.runtime.aiModelsEndpoint())
                assertEquals(savedSettings, fixture.settings.load())

                selectConnection(LocalAiConfigSource.CODEX)
                onNodeWithTag("ai-codex-connection-model").assertEditableText("gpt-bearer-fixture")
                onNodeWithTag("ai-codex-connection-endpoint").assertEditableText("http://127.0.0.1:12345/v1/responses")
                onNodeWithTag("ai-codex-connection-save").performScrollTo().assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load().model == "gpt-bearer-fixture" }
                waitUntil(timeoutMillis = 5000) {
                    SemanticsProperties.Disabled !in onNodeWithTag("ai-codex-connection-save").fetchSemanticsNode().config
                }
                onNodeWithTag("ai-codex-save-error").assertDoesNotExist()
                onNodeWithTag("ai-codex-import-error").assertDoesNotExist()
                onNodeWithTag("ai-codex-connection-key").assertEditableText("")
                assertEquals(listOf("openai:api-key"), fixture.credentialWrites)
                assertEquals("fixture-provider-token", fixture.runtime.credential("openai:api-key"))
                assertEquals("gpt-bearer-fixture", fixture.runtime.aiModel())
                assertEquals("http://127.0.0.1:12345/v1/responses", fixture.runtime.aiEndpoint())
                assertEquals("", fixture.runtime.aiModelsEndpoint())
                assertEquals(savedSettings.copy(model = "gpt-bearer-fixture"), fixture.settings.load())
                assertEquals("codex", fixture.settings.load().providerId)
                assertNull(fixture.runtime.credential("claude:api-key"))
                assertNull(fixture.runtime.credential("claude:auth-token"))
                assertEquals(savedClaudeModel, fixture.runtime.claudeModel())
                assertEquals(savedClaudeEndpoint, fixture.runtime.claudeEndpoint())
                assertEquals(savedClaudeKind, fixture.runtime.claudeCredentialKind())
                selectConnection(LocalAiConfigSource.CLAUDE)
                claudeDraft.forEach { (field, text) ->
                    onNodeWithTag("ai-claude-connection-$field")
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, text))
                }
                assertEquals(1, requests.size)
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

    private fun SemanticsNodeInteraction.assertEditableText(value: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    private fun withFixture(transport: AiHttpTransport, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("aps-ai-codex-bearer-settings-").toRealPath()
        val preferences = Preferences.userRoot().node("aps-ai-codex-bearer-${UUID.randomUUID()}")
        val platformPreferences = Preferences.userRoot().node("aps-ai-codex-bearer-provider-${UUID.randomUUID()}")
        val credentialWrites = CopyOnWriteArrayList<String>()
        val credentialStore = InMemoryCredentialStore()
        val credentials = object : CredentialStore by credentialStore {
            override fun write(key: String, value: String) {
                credentialStore.write(key, value)
                credentialWrites += key
            }
        }
        val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
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
            val settings = AiPlatformSettingsStore(platformPreferences)
            settings.save(AiPlatformSettings(providerId = "codex"))
            block(Fixture(root.resolve("home"), runtime, settings, preferences, platformPreferences, credentialWrites))
        } finally {
            runtime.close()
            preferences.removeNode()
            platformPreferences.removeNode()
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(
        val home: Path,
        val runtime: SourceWorkspaceRuntime,
        val settings: AiPlatformSettingsStore,
        val preferences: Preferences,
        val platformPreferences: Preferences,
        val credentialWrites: List<String>,
    )
}
