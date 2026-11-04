package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.androidperformancestudio.ai.AiHttpRequest
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.ImportedAiConfiguration
import com.androidperformancestudio.ai.ImportedCredentialKind
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.LocalAiConfigSource
import com.androidperformancestudio.ai.LocalAiConfigurationImport
import com.androidperformancestudio.ai.LocalAiConfigurationReader
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.presentation.AiConversationWorkspace
import com.androidperformancestudio.source.ContentAddressedSourceCache
import com.androidperformancestudio.source.DefaultSourceWorkspaceService
import com.androidperformancestudio.source.IndexedSourceResolver
import com.androidperformancestudio.source.LocalSourceProvider
import com.androidperformancestudio.source.SourceProviderRegistry
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.studio.StudioTokens
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class AiSettingsGroupingTest {
    @Test
    fun `saved settings catalog is reused by chat and selecting its model updates the next request without navigation`() {
        val requests = CopyOnWriteArrayList<AiHttpRequest>()
        withFixture(AiHttpTransport { request ->
            requests += request
            check(request.method == com.androidperformancestudio.ai.AiHttpMethod.GET)
            AiHttpResponse(200, """{"data":[{"id":"gpt-configured"},{"id":"gateway/gpt-next"}]}""")
        }) { fixture ->
            fixture.runtime.saveProviderConnection(
                AiProviderConnection.CODEX, "gpt-configured", "https://codex.example/v1/responses", "",
                ImportedCredentialKind.API_KEY, "fixture-key",
            )
            fixture.settings.update { it.copy(model = "gpt-configured") }
            val before = fixture.settings.load()
            val originalRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CODEX)
            val root = Files.createTempDirectory("aps-settings-chat-model-").toRealPath()
            try {
                AiPlatformRuntime.open(
                    root.resolve("profile"),
                    openAiGateway = { error("Selecting a chat model must not execute a provider") },
                    openAiAvailable = { false },
                ).use { platform ->
                    runDesktopComposeUiTest(width = 1000, height = 900) {
                        var chat by mutableStateOf(false)
                        var navigations = 0
                        setContent {
                            ViewerTheme(false) {
                                Surface(Modifier.fillMaxSize()) {
                                    if (!chat) {
                                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, platformSettingsStore = fixture.settings)
                                    } else {
                                        AiConversationWorkspace(
                                            platform, null, UiLanguage.ENGLISH, "fallback-model",
                                            settingsStoreOverride = fixture.settings,
                                            onOpenSettings = { navigations++ },
                                            loadModels = fixture.runtime::savedProviderModels,
                                            selectModel = { providerId, model ->
                                                fixture.runtime.selectProviderModel(providerId, model) {
                                                    fixture.settings.update {
                                                        check(it.providerId == providerId)
                                                        it.copy(model = model)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        awaitProviderReady()
                        assertEquals(1, requests.size)
                        onNodeWithTag("ai-codex-connection-save").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            fixture.runtime.providerConnectionRevision(AiProviderConnection.CODEX) != originalRevision &&
                                SemanticsProperties.Disabled !in onNodeWithTag("ai-provider-selector").fetchSemanticsNode().config
                        }
                        runOnIdle { chat = true }
                        onNodeWithTag("ai-question").performTextReplacement("Use the chat selection")
                        onNodeWithTag("ai-model").performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithTag("ai-chat-model-option-gateway/gpt-next").fetchSemanticsNodes().isNotEmpty()
                        }
                        assertEquals(1, requests.size, "Chat must reuse the applied settings catalog")
                        onNodeWithTag("ai-chat-model-option-gateway/gpt-next").performClick()
                        waitUntil(timeoutMillis = 10_000) { fixture.settings.load().model == "gateway/gpt-next" }
                        assertEquals("gateway/gpt-next", fixture.runtime.providerModel(AiProviderConnection.CODEX))
                        assertEquals(before.copy(model = "gateway/gpt-next"), fixture.settings.load())
                        assertEquals(0, navigations)
                        assertEquals(null, platform.service.state.value.status)
                        onNodeWithTag("ai-send").performClick()
                        waitUntil(timeoutMillis = 10_000) { platform.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                        assertEquals("gateway/gpt-next", platform.service.state.value.prepared?.model)
                        assertEquals("codex", platform.service.state.value.prepared?.providerId)
                        assertEquals(1, requests.size, "Preflight must not execute an inference")
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `unconfigured selection keeps independent drafts without imports requests or persistence`() {
        val reads = CopyOnWriteArrayList<LocalAiConfigSource>()
        val requests = CopyOnWriteArrayList<String>()
        withFixture(AiHttpTransport { request ->
            requests += request.url
            error("Switching unconfigured providers must not load models")
        }) { fixture ->
            val savedPreferences = fixture.preferences.snapshot()
            val savedPlatformPreferences = fixture.platformPreferences.snapshot()
            val applied = fixture.settings.load()
            runDesktopComposeUiTest(width = 760, height = 1000) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader(reads), platformSettingsStore = fixture.settings)
                    }
                }
                assertSelectedOnly(AiProviderConnection.CODEX)
                AiProviderConnection.entries.forEach { connection ->
                    selectProvider(connection)
                    val id = connection.name.lowercase()
                    onNodeWithTag("ai-$id-connection-model").performScrollTo().performTextReplacement("$id-draft-model")
                    onNodeWithTag("ai-$id-connection-endpoint").performScrollTo().performTextReplacement(draftEndpoint(connection))
                    if (connection != AiProviderConnection.CLAUDE) {
                        onNodeWithTag("ai-$id-connection-models-endpoint").performScrollTo().performTextReplacement(draftCatalogEndpoint(connection))
                    }
                    onNodeWithTag("ai-$id-connection-key").performScrollTo().performTextReplacement("$id-draft-key")
                    onNodeWithTag("ai-$id-connection-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                }
                AiProviderConnection.entries.forEach { connection ->
                    selectProvider(connection)
                    val id = connection.name.lowercase()
                    onNodeWithTag("ai-$id-connection-model").assertEditableText("$id-draft-model")
                    onNodeWithTag("ai-$id-connection-endpoint").assertEditableText(draftEndpoint(connection))
                    if (connection != AiProviderConnection.CLAUDE) {
                        onNodeWithTag("ai-$id-connection-models-endpoint").assertEditableText(draftCatalogEndpoint(connection))
                    }
                    onNodeWithTag("ai-$id-connection-save").assertIsEnabled()
                }
                assertEquals(applied, fixture.settings.load())
                assertEquals(savedPreferences, fixture.preferences.snapshot())
                assertEquals(savedPlatformPreferences, fixture.platformPreferences.snapshot())
                assertTrue(reads.isEmpty())
                assertTrue(requests.isEmpty())
                assertTrue(fixture.credentialWrites.isEmpty())
            }
        }
    }

    @Test
    fun `imports use only the selected source and save applies its exact provider without changing the agent`() {
        val reads = CopyOnWriteArrayList<LocalAiConfigSource>()
        val requests = CopyOnWriteArrayList<AiHttpRequest>()
        withFixture(AiHttpTransport { request ->
            requests += request
            val id = if (request.url.contains("codex.example")) "gpt-codex-catalog" else "claude-catalog"
            AiHttpResponse(200, """{"data":[{"id":"$id"}]}""")
        }) { fixture ->
            fixture.settings.save(fixture.settings.load().copy(providerId = "openai"))
            val applied = fixture.settings.load()
            val savedPreferences = fixture.preferences.snapshot()
            val savedPlatformPreferences = fixture.platformPreferences.snapshot()
            val codexModel = fixture.runtime.aiModel()
            runDesktopComposeUiTest(width = 760, height = 1000) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsPageContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader(reads), platformSettingsStore = fixture.settings)
                    }
                }
                assertSelectedOnly(AiProviderConnection.CUSTOM)
                selectProvider(AiProviderConnection.CLAUDE)
                onNodeWithTag("ai-import-claude").performScrollTo().performClick()
                awaitModel("claude-imported-model", AiProviderConnection.CLAUDE)
                awaitProviderReady()
                onNodeWithTag("ai-claude-model-picker").assertIsEnabled()
                assertEquals(applied, fixture.settings.load())
                assertEquals(savedPreferences, fixture.preferences.snapshot())
                assertEquals(savedPlatformPreferences, fixture.platformPreferences.snapshot())
                assertTrue(fixture.credentialWrites.isEmpty())
                onNodeWithTag("ai-claude-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load() == applied.copy(providerId = "claude", model = "claude-imported-model") }
                awaitProviderReady()
                assertEquals("fixture-claude-key", fixture.runtime.credential("claude:api-key"))
                assertEquals(codexModel, fixture.runtime.aiModel())

                selectProvider(AiProviderConnection.CODEX)
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                awaitModel("codex-imported-model", AiProviderConnection.CODEX)
                awaitProviderReady()
                onNodeWithTag("ai-codex-connection-model").performScrollTo().performTextReplacement("codex-draft-model")
                selectProvider(AiProviderConnection.CUSTOM)
                selectProvider(AiProviderConnection.CODEX)
                onNodeWithTag("ai-codex-connection-model").assertEditableText("codex-draft-model")
                onNodeWithTag("ai-codex-model-picker").assertIsEnabled()
                assertEquals(applied.copy(providerId = "claude", model = "claude-imported-model"), fixture.settings.load())
                assertNull(fixture.runtime.credential("openai:api-key"))
                onNodeWithTag("ai-codex-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load() == applied.copy(providerId = "codex", model = "codex-draft-model") }
                awaitProviderReady()
                onNodeWithTag("ai-codex-connection-key").assertEditableText("")
                assertEquals("fixture-codex-key", fixture.runtime.credential("openai:api-key"))
                assertEquals("fixture-claude-key", fixture.runtime.credential("claude:api-key"))
                assertNull(fixture.runtime.credential("custom:api-key"))
                assertEquals("claude-imported-model", fixture.runtime.claudeModel())
                assertEquals("https://claude.example", fixture.runtime.claudeEndpoint())
                assertEquals(listOf(LocalAiConfigSource.CLAUDE, LocalAiConfigSource.CODEX), reads)
                assertEquals(listOf("https://claude.example/v1/models", "https://codex.example/v1/models"), requests.map { it.url })
                assertEquals(listOf("claude:api-key", "openai:api-key"), fixture.credentialWrites)
            }
        }
    }

    @Test
    fun `manual Custom save isolates its connection and restores the last applied panel`() {
        val reads = CopyOnWriteArrayList<LocalAiConfigSource>()
        val requests = CopyOnWriteArrayList<AiHttpRequest>()
        withFixture(AiHttpTransport { request ->
            requests += request
            AiHttpResponse(200, """{"data":[{"id":"gpt-custom-catalog"}]}""")
        }) { fixture ->
            fixture.runtime.saveProviderConnection(
                AiProviderConnection.CODEX, "saved-codex", "https://saved-codex.example/responses", "",
                ImportedCredentialKind.API_KEY, "saved-codex-key",
            )
            fixture.runtime.saveProviderConnection(
                AiProviderConnection.CLAUDE, "saved-claude", "https://saved-claude.example", "",
                ImportedCredentialKind.API_KEY, "saved-claude-key",
            )
            val unrelatedPreferences = fixture.preferences.snapshot()
            fixture.settings.save(fixture.settings.load().copy(providerId = "openai"))
            val applied = fixture.settings.load()
            runDesktopComposeUiTest(width = 760, height = 1000) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader(reads), platformSettingsStore = fixture.settings)
                    }
                }
                assertSelectedOnly(AiProviderConnection.CUSTOM)
                awaitProviderReady()
                onNodeWithTag("ai-custom-connection-endpoint").performScrollTo().performTextReplacement("https://custom.example/responses")
                onNodeWithTag("ai-custom-connection-models-endpoint").performScrollTo().performTextReplacement("https://custom.example/private/models")
                onNodeWithTag("ai-custom-connection-model").performScrollTo().performTextReplacement("custom-manual-model")
                onNodeWithTag("ai-custom-connection-key").performScrollTo().performTextReplacement("custom-manual-key")
                onNodeWithTag("ai-custom-model-refresh").performScrollTo().performClick()
                awaitProviderReady()
                onNodeWithTag("ai-custom-model-picker").assertIsEnabled()
                assertEquals("https://custom.example/private/models", requests.last().url)
                assertEquals("Bearer custom-manual-key", requests.last().headers["Authorization"])
                assertEquals(applied, fixture.settings.load())
                assertEquals(unrelatedPreferences, fixture.preferences.snapshot())
                onNodeWithTag("ai-custom-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { fixture.settings.load() == applied.copy(providerId = "openai", model = "custom-manual-model") }
                awaitProviderReady()
                onNodeWithTag("ai-custom-connection-key").assertEditableText("")
                assertEquals("custom-manual-key", fixture.runtime.credential("custom:api-key"))
                assertEquals("saved-codex-key", fixture.runtime.credential("openai:api-key"))
                assertEquals("saved-claude-key", fixture.runtime.credential("claude:api-key"))
                assertEquals(unrelatedPreferences.filterKeys { !it.startsWith("custom.") }, fixture.preferences.snapshot().filterKeys { !it.startsWith("custom.") })
                assertEquals("saved-codex", fixture.runtime.aiModel())
                assertEquals("saved-claude", fixture.runtime.claudeModel())
                assertTrue(reads.isEmpty())
            }
            val restoredSettings = AiPlatformSettingsStore(fixture.platformPreferences)
            assertEquals("openai", restoredSettings.load().providerId)
            runDesktopComposeUiTest(width = 760, height = 1000) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader(reads), platformSettingsStore = restoredSettings)
                    }
                }
                assertSelectedOnly(AiProviderConnection.CUSTOM)
                awaitProviderReady()
                onNodeWithTag("ai-custom-connection-model").assertEditableText("custom-manual-model")
                onNodeWithTag("ai-custom-connection-endpoint").assertEditableText("https://custom.example/responses")
                onNodeWithTag("ai-custom-connection-models-endpoint").assertEditableText("https://custom.example/private/models")
                assertEquals("https://custom.example/private/models", requests.last().url)
                assertEquals("Bearer custom-manual-key", requests.last().headers["Authorization"])
                assertTrue(reads.isEmpty())
            }
        }
    }

    @Test
    fun `a failed Custom save cannot apply its provider or persist a replacement credential`() {
        withFixture(AiHttpTransport { error("An unconfigured invalid draft must not load models") }) { fixture ->
            val applied = fixture.settings.load()
            val savedPreferences = fixture.preferences.snapshot()
            val savedPlatformPreferences = fixture.platformPreferences.snapshot()
            runDesktopComposeUiTest(width = 760, height = 1000) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader(CopyOnWriteArrayList()), platformSettingsStore = fixture.settings)
                    }
                }
                selectProvider(AiProviderConnection.CUSTOM)
                onNodeWithTag("ai-custom-connection-endpoint").performScrollTo().performTextReplacement("https://custom.example/responses")
                onNodeWithTag("ai-custom-connection-models-endpoint").performScrollTo().performTextReplacement("https://unrelated.example/models")
                onNodeWithTag("ai-custom-connection-model").performScrollTo().performTextReplacement("custom-model")
                onNodeWithTag("ai-custom-connection-key").performScrollTo().performTextReplacement("custom-key")
                onNodeWithTag("ai-custom-connection-save").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("ai-custom-save-error").fetchSemanticsNodes().size == 1 }
                awaitProviderReady()
                assertEquals(applied, fixture.settings.load())
                assertEquals(savedPreferences, fixture.preferences.snapshot())
                assertEquals(savedPlatformPreferences, fixture.platformPreferences.snapshot())
                assertTrue(fixture.credentialWrites.isEmpty())
                assertSelectedOnly(AiProviderConnection.CUSTOM)
            }
        }
    }

    @Test
    fun `a pending catalog locks the provider selector until the selected draft finishes`() {
        val releaseCatalog = CompletableDeferred<Unit>()
        val reads = CopyOnWriteArrayList<LocalAiConfigSource>()
        val requests = CopyOnWriteArrayList<String>()
        try {
            withFixture(AiHttpTransport { request ->
                requests += request.url
                releaseCatalog.await()
                AiHttpResponse(200, """{"data":[]}""")
            }) { fixture ->
                val applied = fixture.settings.load()
                runDesktopComposeUiTest(width = 760, height = 1000) {
                    setContent {
                        ViewerTheme(false) {
                            AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader(reads), platformSettingsStore = fixture.settings)
                        }
                    }
                    onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                    awaitModel("codex-imported-model", AiProviderConnection.CODEX)
                    waitUntil(timeoutMillis = 5000) { requests.size == 1 }
                    onNodeWithContentDescription("Provider").assertIsNotEnabled()
                    onNodeWithTag("ai-import-codex").assertIsNotEnabled()
                    onNodeWithTag("ai-codex-connection-save").assertIsNotEnabled()
                    onNodeWithTag("ai-provider-menu").assertDoesNotExist()
                    assertSelectedOnly(AiProviderConnection.CODEX)
                    assertEquals(listOf(LocalAiConfigSource.CODEX), reads)
                    runOnIdle { releaseCatalog.complete(Unit) }
                    awaitProviderReady()
                    selectProvider(AiProviderConnection.CLAUDE)
                    onNodeWithTag("ai-import-claude").assertIsEnabled()
                    assertEquals(listOf(LocalAiConfigSource.CODEX), reads)
                    assertEquals(listOf("https://codex.example/v1/models"), requests)
                    assertEquals(applied, fixture.settings.load())
                    assertTrue(fixture.credentialWrites.isEmpty())
                }
            }
        } finally {
            releaseCatalog.complete(Unit)
        }
    }

    @Test
    fun `wrong source imports stay in the selected draft without changing other connections`() {
        val reads = CopyOnWriteArrayList<LocalAiConfigSource>()
        withFixture(AiHttpTransport { error("A mismatched import must not load models") }) { fixture ->
            val reader = object : LocalAiConfigurationReader {
                override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport {
                    reads += source
                    return importedConfiguration(if (source == LocalAiConfigSource.CODEX) LocalAiConfigSource.CLAUDE else LocalAiConfigSource.CODEX)
                }
            }
            val codexModel = fixture.runtime.aiModel()
            val applied = fixture.settings.load()
            val savedPreferences = fixture.preferences.snapshot()
            val savedPlatformPreferences = fixture.platformPreferences.snapshot()
            runDesktopComposeUiTest(width = 760, height = 1000) {
                setContent {
                    ViewerTheme(false) {
                        AiSettingsContent(UiLanguage.ENGLISH, fixture.runtime, localConfigReader = reader, platformSettingsStore = fixture.settings)
                    }
                }
                listOf(AiProviderConnection.CODEX, AiProviderConnection.CLAUDE).forEach { connection ->
                    selectProvider(connection)
                    val id = connection.name.lowercase()
                    onNodeWithTag("ai-import-$id").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("ai-$id-import-error").fetchSemanticsNodes().size == 1 }
                    awaitProviderReady()
                    onNodeWithTag("ai-$id-connection-key").assertEditableText("")
                    onNodeWithTag("ai-$id-connection-model").assertEditableText(if (connection == AiProviderConnection.CODEX) codexModel else "")
                }
                selectProvider(AiProviderConnection.CODEX)
                onNodeWithTag("ai-codex-import-error").assertExists()
                onNodeWithTag("ai-codex-connection-model").assertEditableText(codexModel)
                assertEquals(listOf(LocalAiConfigSource.CODEX, LocalAiConfigSource.CLAUDE), reads)
                assertEquals(applied, fixture.settings.load())
                assertEquals(savedPreferences, fixture.preferences.snapshot())
                assertEquals(savedPlatformPreferences, fixture.platformPreferences.snapshot())
                assertTrue(fixture.credentialWrites.isEmpty())
            }
        }
    }

    @Test
    fun `saved provider opens alone at compact widths in English Chinese and both themes`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                AiProviderConnection.entries.forEach { connection ->
                    withFixture(AiHttpTransport { error("Unconfigured screenshot fixtures must not load models") }) { fixture ->
                        fixture.settings.save(fixture.settings.load().copy(providerId = connection.providerId))
                        val settings = fixture.settings.load()
                        val sourcePreferences = fixture.preferences.snapshot()
                        val platformPreferences = fixture.platformPreferences.snapshot()
                        val reads = CopyOnWriteArrayList<LocalAiConfigSource>()
                        runDesktopComposeUiTest(width = 720, height = 1000) {
                            setContent {
                                ViewerTheme(dark) {
                                    Surface(Modifier.fillMaxSize()) {
                                        AiSettingsPageContent(language, fixture.runtime, localConfigReader = reader(reads), platformSettingsStore = fixture.settings)
                                    }
                                }
                            }
                            assertSelectedOnly(connection)
                            onNodeWithTag("ai-provider-selector").assertIsDisplayed()
                            val id = connection.name.lowercase()
                            val card = onNodeWithTag("ai-connection-group-$id").fetchSemanticsNode().boundsInRoot
                            val model = onNodeWithTag("ai-$id-connection-model").fetchSemanticsNode().boundsInRoot
                            assertEquals(StudioTokens.compactContentPadding.value, model.left - card.left, 1f)
                            assertEquals(320f, model.width, 1f)
                            assertTrue(model.right <= card.right - StudioTokens.compactContentPadding.value + 1f)
                            val refreshButton = onNodeWithTag("ai-$id-model-refresh").fetchSemanticsNode().boundsInRoot
                            val saveButton = onNodeWithTag("ai-$id-connection-save").fetchSemanticsNode().boundsInRoot
                            assertEquals(ViewerDimensions.buttonHeight.value, refreshButton.height, 1f)
                            assertEquals(refreshButton.height, saveButton.height, 1f)
                            onNodeWithContentDescription("Provider").performClick()
                            AiProviderConnection.entries.forEach { option ->
                                onNode(hasText(providerLabel(option, language)) and hasAnyAncestor(hasTestTag("ai-provider-menu"))).assertIsDisplayed()
                            }
                            onNode(hasText(providerLabel(connection, language)) and hasAnyAncestor(hasTestTag("ai-provider-menu"))).performClick()
                            saveScreenshot("provider-$id-${if (language == UiLanguage.ENGLISH) "en" else "zh"}-${if (dark) "dark" else "light"}-720")
                            assertEquals(settings, fixture.settings.load())
                            assertEquals(sourcePreferences, fixture.preferences.snapshot())
                            assertEquals(platformPreferences, fixture.platformPreferences.snapshot())
                            assertTrue(reads.isEmpty())
                            assertTrue(fixture.credentialWrites.isEmpty())
                        }
                    }
                }
            }
        }
    }

    private fun reader(reads: MutableList<LocalAiConfigSource>) = object : LocalAiConfigurationReader {
        override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport {
            reads += source
            return importedConfiguration(source)
        }
    }

    private fun importedConfiguration(source: LocalAiConfigSource): LocalAiConfigurationImport.Success {
        val id = source.name.lowercase()
        val endpoint = if (source == LocalAiConfigSource.CODEX) "https://codex.example/v1/responses" else "https://claude.example"
        return LocalAiConfigurationImport.Success(ImportedAiConfiguration(source, "fixture-$id-key", endpoint, "$id-imported-model"))
    }

    private fun DesktopComposeUiTest.assertSelectedOnly(connection: AiProviderConnection) {
        AiProviderConnection.entries.forEach { candidate ->
            val id = candidate.name.lowercase()
            if (candidate == connection) {
                onNodeWithTag("ai-connection-group-$id").assertIsDisplayed()
                onNodeWithTag("ai-$id-connection-model").assertExists()
                if (candidate.localSource != null) {
                    onNodeWithTag("ai-import-$id").assert(hasAnyAncestor(hasTestTag("ai-connection-group-$id")))
                }
            } else {
                onNodeWithTag("ai-connection-group-$id").assertDoesNotExist()
                onNodeWithTag("ai-$id-connection-model").assertDoesNotExist()
                onNodeWithTag("ai-import-$id").assertDoesNotExist()
            }
        }
        onNodeWithTag("ai-import-custom").assertDoesNotExist()
    }

    private fun DesktopComposeUiTest.selectProvider(connection: AiProviderConnection, language: UiLanguage = UiLanguage.ENGLISH) {
        awaitProviderReady()
        onNodeWithContentDescription("Provider").performScrollTo().performClick()
        onNode(hasText(providerLabel(connection, language)) and hasAnyAncestor(hasTestTag("ai-provider-menu"))).performClick()
        assertSelectedOnly(connection)
        awaitProviderReady()
    }

    private fun DesktopComposeUiTest.awaitProviderReady() {
        waitUntil(timeoutMillis = 5000) {
            SemanticsProperties.Disabled !in onNodeWithContentDescription("Provider").fetchSemanticsNode().config
        }
    }

    private fun DesktopComposeUiTest.awaitModel(value: String, connection: AiProviderConnection) {
        waitUntil(timeoutMillis = 5000) {
            onNodeWithTag("ai-${connection.name.lowercase()}-connection-model")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text == value
        }
    }

    private fun SemanticsNodeInteraction.assertEditableText(value: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    private fun DesktopComposeUiTest.saveScreenshot(name: String) {
        val pixels = onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        }
        val output = Path.of("build/reports/ai-settings-import/$name.png")
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())
    }

    private fun draftEndpoint(connection: AiProviderConnection): String = "https://${connection.name.lowercase()}.example/responses"

    private fun draftCatalogEndpoint(connection: AiProviderConnection): String = "https://${connection.name.lowercase()}.example/catalog/models"

    private fun providerLabel(connection: AiProviderConnection, language: UiLanguage): String = when (connection) {
        AiProviderConnection.CODEX -> "Codex"
        AiProviderConnection.CLAUDE -> "Claude"
        AiProviderConnection.CUSTOM -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "自定义（OpenAI 兼容）" else "Custom (OpenAI compatible)"
    }

    private fun Preferences.snapshot(): Map<String, String?> = keys().associateWith { get(it, null) }

    private fun withFixture(transport: AiHttpTransport, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("aps-ai-group-settings-")
        val preferences = Preferences.userRoot().node("aps-ai-group-${UUID.randomUUID()}")
        val platformPreferences = Preferences.userRoot().node("aps-ai-group-provider-${UUID.randomUUID()}")
        val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
        val credentialWrites = CopyOnWriteArrayList<String>()
        val store = InMemoryCredentialStore()
        val credentials = object : CredentialStore by store {
            override fun write(key: String, value: String) {
                store.write(key, value)
                credentialWrites += key
            }
        }
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
            settings.save(AiPlatformSettings(providerId = "codex", model = "applied-model", agentId = "kept.agent", allowArtifacts = true, allowScripts = true))
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
