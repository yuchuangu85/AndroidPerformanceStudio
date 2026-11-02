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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class AiSettingsNamespacedModelsTest {
    @Test
    fun `imported Codex catalog keeps namespaced text model IDs through selection and explicit save`() {
        val requests = CopyOnWriteArrayList<AiHttpRequest>()
        val reads = CopyOnWriteArrayList<LocalAiConfigSource>()
        withFixture(AiHttpTransport { request ->
            requests += request
            AiHttpResponse(
                200,
                """{"data":[{"id":"gateway/gpt-fixture"},{"id":"gateway/o3-fixture"},{"id":"gateway/gpt-image-1"}]}""",
            )
        }) { fixture ->
            val preferencesBefore = fixture.preferences.snapshot()
            val platformPreferencesBefore = fixture.platformPreferences.snapshot()
            val settingsBefore = fixture.settings.load()
            val reader = reader(LocalAiConfigSource.CODEX, reads)

            runDesktopComposeUiTest(width = 1100, height = 1400) {
                setContent {
                    ViewerTheme(false) {
                        Surface(Modifier.fillMaxSize()) {
                            AiSettingsPageContent(
                                language = UiLanguage.ENGLISH,
                                runtime = fixture.runtime,
                                localConfigReader = reader,
                                platformSettingsStore = fixture.settings,
                            )
                        }
                    }
                }
                onNodeWithTag("ai-settings-category").assertIsDisplayed()
                onNodeWithTag("ai-codex-model-picker").assertIsNotEnabled()
                onNodeWithTag("ai-connection-group-claude").assertDoesNotExist()
                onNodeWithTag("ai-import-claude").assertDoesNotExist()
                onNodeWithTag("ai-codex-model-empty").assertDoesNotExist()
                onNodeWithTag("ai-claude-model-empty").assertDoesNotExist()
                assertTrue(requests.isEmpty())
                assertTrue(reads.isEmpty())

                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                awaitEnabled("ai-codex-model-picker")
                assertEquals(listOf(LocalAiConfigSource.CODEX), reads)
                assertEquals(1, requests.size)
                assertEquals(AiHttpMethod.GET, requests.single().method)
                assertEquals("https://codex.example/v1/models", requests.single().url)
                assertEquals("Bearer fixture-codex-key", requests.single().headers["Authorization"])
                onNodeWithTag("ai-codex-model-error").assertDoesNotExist()
                onNodeWithTag("ai-codex-model-empty").assertDoesNotExist()
                onNodeWithTag("ai-codex-connection-model").assertEditableText("imported-manual-model")
                onNodeWithTag("ai-codex-model-picker")
                    .assert(hasAnyAncestor(hasTestTag("ai-connection-group-codex")))
                    .assertIsEnabled().performScrollTo().performClick()
                onNodeWithTag("ai-codex-model-menu").assertIsDisplayed()
                onNodeWithText("gateway/gpt-fixture").assertIsDisplayed()
                onNodeWithText("gateway/o3-fixture").assertIsDisplayed()
                onNodeWithText("gateway/gpt-image-1").assertDoesNotExist()
                onNodeWithText("gpt-fixture").assertDoesNotExist()
                onNodeWithText("o3-fixture").assertDoesNotExist()
                onNodeWithText("gateway/o3-fixture").performClick()
                onNodeWithTag("ai-codex-model-menu").assertDoesNotExist()
                onNodeWithTag("ai-codex-connection-model").assertEditableText("gateway/o3-fixture")
                assertEquals(preferencesBefore, fixture.preferences.snapshot())
                assertEquals(platformPreferencesBefore, fixture.platformPreferences.snapshot())
                assertEquals(settingsBefore, fixture.settings.load())
                assertTrue(fixture.credentialWrites.isEmpty())
                assertNull(fixture.runtime.credential("openai:api-key"))
                assertEquals(1, requests.size)

                onNodeWithTag("ai-codex-connection-save").performScrollTo().assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load().model == "gateway/o3-fixture" }
                awaitEnabled("ai-codex-connection-save")
                onNodeWithTag("ai-codex-save-error").assertDoesNotExist()
                assertEquals("gateway/o3-fixture", fixture.runtime.aiModel())
                assertEquals("https://codex.example/v1/responses", fixture.runtime.aiEndpoint())
                assertEquals("", fixture.runtime.aiModelsEndpoint())
                assertEquals(settingsBefore.copy(model = "gateway/o3-fixture"), fixture.settings.load())
                assertEquals(listOf("openai:api-key"), fixture.credentialWrites)
                assertEquals("fixture-codex-key", fixture.runtime.credential("openai:api-key"))
                assertNull(fixture.runtime.credential("claude:api-key"))
                assertEquals(1, requests.size)
            }
        }
    }

    @Test
    fun `successful empty catalogs show localized non-error feedback without blocking manual models or save`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            LocalAiConfigSource.entries.forEach { source -> assertEmptyCatalog(source, language) }
        }
    }

    private fun assertEmptyCatalog(source: LocalAiConfigSource, language: UiLanguage) {
        val requests = CopyOnWriteArrayList<AiHttpRequest>()
        val reads = CopyOnWriteArrayList<LocalAiConfigSource>()
        val groupId = source.name.lowercase()
        val tagPrefix = "ai-$groupId"
        val otherGroupId = if (source == LocalAiConfigSource.CODEX) "claude" else "codex"
        withFixture(AiHttpTransport { request ->
            requests += request
            AiHttpResponse(200, """{"data":[],"has_more":false}""")
        }) { fixture ->
            val preferencesBefore = fixture.preferences.snapshot()
            val platformPreferencesBefore = fixture.platformPreferences.snapshot()
            val reader = reader(source, reads)
            runDesktopComposeUiTest(width = 1100, height = 1400) {
                setContent {
                    ViewerTheme(false) {
                        Surface(Modifier.fillMaxSize()) {
                            AiSettingsPageContent(
                                language = language,
                                runtime = fixture.runtime,
                                localConfigReader = reader,
                                platformSettingsStore = fixture.settings,
                            )
                        }
                    }
                }
                selectConnection(source)
                onNodeWithTag("ai-connection-group-$otherGroupId").assertDoesNotExist()
                onNodeWithTag("ai-import-$otherGroupId").assertDoesNotExist()
                onNodeWithTag("ai-codex-model-empty").assertDoesNotExist()
                onNodeWithTag("ai-claude-model-empty").assertDoesNotExist()
                assertTrue(requests.isEmpty())
                assertTrue(reads.isEmpty())

                onNodeWithTag("ai-import-$groupId").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) {
                    onAllNodesWithTag("$tagPrefix-model-empty").fetchSemanticsNodes().size == 1
                }
                onNodeWithTag("$tagPrefix-model-empty").performScrollTo().assertIsDisplayed()
                    .assert(hasAnyAncestor(hasTestTag("ai-connection-group-$groupId")))
                    .assertTextEquals(
                        if (language == UiLanguage.SIMPLIFIED_CHINESE) {
                            "\u672a\u8fd4\u56de\u53ef\u9009\u7684\u6587\u672c\u6a21\u578b\u3002" +
                                "\u8bf7\u624b\u52a8\u586b\u5199\u6a21\u578b\uff0c\u6216\u68c0\u67e5\u6b64\u8fde\u63a5\u3002"
                        } else {
                            "No selectable text models were returned. Enter a model manually or check this connection."
                        },
                    )
                onNodeWithTag("$tagPrefix-model-error").assertDoesNotExist()
                onNodeWithTag("$tagPrefix-import-error").assertDoesNotExist()
                onNodeWithTag("ai-$otherGroupId-model-empty").assertDoesNotExist()
                onNodeWithTag("$tagPrefix-model-picker").assertIsNotEnabled()
                onNodeWithTag("$tagPrefix-model-refresh").assertIsEnabled()
                val manualModel = "manual-$groupId-fixture"
                onNodeWithTag("$tagPrefix-connection-model").performScrollTo().assertIsEnabled()
                    .performTextReplacement(manualModel)
                onNodeWithTag("$tagPrefix-connection-model").assertEditableText(manualModel)
                onNodeWithTag("$tagPrefix-connection-save").assertIsEnabled()
                assertEquals(listOf(source), reads)
                assertEquals(1, requests.size)
                assertEquals(AiHttpMethod.GET, requests.single().method)
                assertEquals("https://$groupId.example/v1/models", requests.single().url)
                if (source == LocalAiConfigSource.CODEX) {
                    assertEquals("Bearer fixture-codex-key", requests.single().headers["Authorization"])
                } else {
                    assertEquals("fixture-claude-key", requests.single().headers["x-api-key"])
                }
                assertEquals(preferencesBefore, fixture.preferences.snapshot())
                assertEquals(platformPreferencesBefore, fixture.platformPreferences.snapshot())
                assertTrue(fixture.credentialWrites.isEmpty())

                onNodeWithTag("$tagPrefix-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load().model == manualModel }
                awaitEnabled("$tagPrefix-connection-save")
                onNodeWithTag("$tagPrefix-save-error").assertDoesNotExist()
                onNodeWithTag("$tagPrefix-model-empty").assertExists()
                val credentialReference = if (source == LocalAiConfigSource.CODEX) "openai:api-key" else "claude:api-key"
                assertEquals(listOf(credentialReference), fixture.credentialWrites)
                assertEquals("fixture-$groupId-key", fixture.runtime.credential(credentialReference))
                assertEquals(manualModel, if (source == LocalAiConfigSource.CODEX) fixture.runtime.aiModel() else fixture.runtime.claudeModel())
                assertEquals(groupId, fixture.settings.load().providerId)
                assertEquals(1, requests.size)

                onNodeWithTag("$tagPrefix-connection-key").performScrollTo()
                    .performTextReplacement("fixture-replacement-key")
                onNodeWithTag("$tagPrefix-model-empty").assertDoesNotExist()
                assertEquals(1, requests.size, "Editing a credential must not query its catalog")
                onNodeWithTag("$tagPrefix-model-refresh").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) {
                    onAllNodesWithTag("$tagPrefix-model-empty").fetchSemanticsNodes().size == 1
                }
                assertEquals(2, requests.size)
                assertEquals("fixture-$groupId-key", fixture.runtime.credential(credentialReference))
                assertEquals(listOf(credentialReference), fixture.credentialWrites)

                onNodeWithTag("$tagPrefix-connection-endpoint").performScrollTo()
                    .performTextReplacement("https://edited.example/v1/responses")
                onNodeWithTag("$tagPrefix-model-empty").assertDoesNotExist()
                onNodeWithTag("$tagPrefix-model-error").assertDoesNotExist()
                onNodeWithTag("ai-$otherGroupId-model-empty").assertDoesNotExist()
                assertEquals(2, requests.size, "Editing a connection must not query its catalog")
            }
        }
    }

    private fun reader(expectedSource: LocalAiConfigSource, reads: MutableList<LocalAiConfigSource>) =
        object : LocalAiConfigurationReader {
            override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport {
                reads += source
                check(source == expectedSource)
                val endpoint = if (source == LocalAiConfigSource.CODEX) "https://codex.example/v1/responses" else "https://claude.example"
                return LocalAiConfigurationImport.Success(
                    ImportedAiConfiguration(source, "fixture-${source.name.lowercase()}-key", endpoint, "imported-manual-model"),
                )
            }
        }

    private fun DesktopComposeUiTest.selectConnection(source: LocalAiConfigSource) {
        onNodeWithTag("ai-provider-selector").performScrollTo().performClick()
        onNode(
            hasText(if (source == LocalAiConfigSource.CODEX) "Codex" else "Claude") and
                hasAnyAncestor(hasTestTag("ai-provider-menu")),
        ).performClick()
    }

    private fun DesktopComposeUiTest.awaitEnabled(tag: String) {
        waitUntil(timeoutMillis = 5000) {
            SemanticsProperties.Disabled !in onNodeWithTag(tag).fetchSemanticsNode().config
        }
    }

    private fun SemanticsNodeInteraction.assertEditableText(value: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    private fun Preferences.snapshot(): Map<String, String?> = keys().associateWith { get(it, null) }

    private fun withFixture(transport: AiHttpTransport, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("aps-ai-namespaced-settings-")
        val preferences = Preferences.userRoot().node("aps-ai-namespaced-${UUID.randomUUID()}")
        val platformPreferences = Preferences.userRoot().node("aps-ai-namespaced-provider-${UUID.randomUUID()}")
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
            DefaultSourceWorkspaceService(
                SourceProviderRegistry(listOf(LocalSourceProvider())), repository, ContentAddressedSourceCache(root.resolve("cache")),
            ),
            IndexedSourceResolver(repository),
            SqliteAnalysisSessionRepository(root.resolve("analysis.db")),
            credentials,
            preferences,
            modelCatalogTransport = transport,
        )
        try {
            runtime.saveAiConfiguration("saved-model", "https://saved.example/v1/responses")
            val settings = AiPlatformSettingsStore(platformPreferences)
            settings.save(AiPlatformSettings(providerId = "codex", model = "saved-model"))
            block(Fixture(runtime, settings, preferences, platformPreferences, credentialWrites))
        } finally {
            runtime.close()
            preferences.removeNode()
            platformPreferences.removeNode()
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(
        val runtime: SourceWorkspaceRuntime,
        val settings: AiPlatformSettingsStore,
        val preferences: Preferences,
        val platformPreferences: Preferences,
        val credentialWrites: List<String>,
    )
}
