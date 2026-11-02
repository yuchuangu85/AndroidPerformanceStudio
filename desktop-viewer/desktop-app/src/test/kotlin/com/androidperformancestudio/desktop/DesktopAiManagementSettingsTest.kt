package com.androidperformancestudio.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiHttpMethod
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.ImportedAiConfiguration
import com.androidperformancestudio.ai.LocalAiConfigSource
import com.androidperformancestudio.ai.LocalAiConfigurationImport
import com.androidperformancestudio.ai.LocalAiConfigurationReader
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.source.ContentAddressedSourceCache
import com.androidperformancestudio.source.DefaultSourceWorkspaceService
import com.androidperformancestudio.source.IndexedSourceResolver
import com.androidperformancestudio.source.LocalSourceProvider
import com.androidperformancestudio.source.SourceProviderRegistry
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.rememberViewerThemeContext
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DesktopAiManagementSettingsTest {
    @Test
    fun `saved connection and legacy provider selections both restore the connection form under the single Provider tab`() {
        listOf("CONNECTION", "PROVIDER").forEach { saved ->
            DesktopManagementFixture().use { fixture ->
                val values = mutableMapOf("application.aiSettingsCategory" to saved)
                val restored = ApplicationUiSettingsStore(values::get, values::set).load()
                val before = fixture.settings.load()
                runDesktopComposeUiTest(width = 1000, height = 760) {
                    setContent {
                        ViewerTheme(darkTheme = false) {
                            ManagementSettingsScreen(fixture, UiLanguage.ENGLISH, restored)
                        }
                    }
                    onNodeWithText("Provider").assertIsSelected()
                    onAllNodes(hasText("Provider") and hasAnyAncestor(hasTestTag("ai-settings-category")))
                        .assertCountEquals(1)
                    onNodeWithText("Connection").assertDoesNotExist()
                    assertConnectionGroups()
                    onNodeWithTag("ai-import-codex").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("ai-import-claude").assertDoesNotExist()
                    onNodeWithTag("ai-management-providers").assertDoesNotExist()
                    onNodeWithTag("ai-management-provider-model").assertDoesNotExist()
                    assertEquals(saved, values["application.aiSettingsCategory"])
                    assertEquals(before, fixture.settings.load())
                    assertTrue(fixture.credentials.values.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test
    fun `top categories stay below a persistent title above one panel with shared typography across languages and themes`() {
        val modes = listOf(
            Triple(UiLanguage.ENGLISH, true, 1000),
            Triple(UiLanguage.ENGLISH, false, 560),
            Triple(UiLanguage.SIMPLIFIED_CHINESE, false, 560),
            Triple(UiLanguage.SIMPLIFIED_CHINESE, true, 1000),
        )
        modes.forEach { (language, dark, width) ->
            DesktopManagementFixture().use { fixture ->
                runDesktopComposeUiTest(width = width, height = 760) {
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            ManagementSettingsScreen(fixture, language)
                        }
                    }
                    val labels =
                        if (language == UiLanguage.ENGLISH) {
                            listOf("Provider", "Agent", "Skill", "Policy")
                        } else {
                            listOf("Provider", "Agent", "Skill", "执行策略")
                        }
                    val title = if (language == UiLanguage.ENGLISH) "AI Settings" else "AI 设置"
                    assertAiSettingsHeader(title, labels)
                    onNodeWithText("Connection").assertDoesNotExist()
                    onNodeWithText("连接").assertDoesNotExist()
                    onNodeWithTag("ai-management-providers").assertDoesNotExist()
                    onNodeWithText(labels.first()).assertIsSelected()
                    saveSettingsScreenshot("header-${language.name}-$width")
                    val panelTags =
                        listOf(
                            "ai-import-codex",
                            "ai-management-extensions-agent",
                            "ai-management-extensions-skill",
                            "ai-management-allow-artifacts",
                        )
                    labels.forEachIndexed { index, label ->
                        selectCategory(label)
                        onNodeWithText(label).assertIsSelected()
                        awaitNode(panelTags[index])
                        if (index == 1 || index == 2) {
                            waitUntil(timeoutMillis = 10_000) {
                                SemanticsProperties.Disabled !in onNodeWithTag("ai-management-browse").fetchSemanticsNode().config
                            }
                        }
                        if (index == 0) {
                            assertConnectionGroups()
                            val titleBounds = onNodeWithTag("ai-settings-title").fetchSemanticsNode().boundsInRoot
                            val rowBounds = onNodeWithTag("ai-settings-category-row").fetchSemanticsNode().boundsInRoot
                            selectProvider("Claude")
                            onNodeWithTag("ai-import-claude").performScrollTo().assertIsDisplayed()
                            onNodeWithTag("ai-claude-connection-save").performScrollTo().assertIsDisplayed()
                            assertAiSettingsHeader(title, labels)
                            assertEquals(titleBounds, onNodeWithTag("ai-settings-title").fetchSemanticsNode().boundsInRoot)
                            assertEquals(rowBounds, onNodeWithTag("ai-settings-category-row").fetchSemanticsNode().boundsInRoot)
                            val saveBounds = onNodeWithTag("ai-claude-connection-save").fetchSemanticsNode().boundsInRoot
                            assertTrue(saveBounds.top > rowBounds.bottom, "Scrolled Claude content must stay below the selector")
                            saveSettingsScreenshot("${language.name}-$width-PROVIDER-claude-scrolled")
                            selectProvider("Codex")
                            onNodeWithTag("ai-import-codex").performScrollTo()
                        }
                        assertAiSettingsHeader(title, labels)
                        onNodeWithTag(panelTags[index]).assertIsDisplayed()
                        panelTags.filterIndexed { other, _ -> other != index }.forEach { onNodeWithTag(it).assertDoesNotExist() }
                        val rowBounds = onNodeWithTag("ai-settings-category-row").fetchSemanticsNode().boundsInRoot
                        val headerBounds = onNodeWithTag("ai-settings-category").fetchSemanticsNode().boundsInRoot
                        val panelBounds = onNodeWithTag(panelTags[index]).fetchSemanticsNode().boundsInRoot
                        assertTrue(panelBounds.top > rowBounds.bottom, "$label content must stay below the category row")
                        assertTrue(panelBounds.top > headerBounds.bottom, "$label content must stay below the selector")
                        assertSharedTextSizes()
                        saveSettingsScreenshot("${language.name}-$width-${AiSettingsCategory.entries[index].name}")
                    }
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test
    fun `selected category survives leaving settings and a fresh preferences store without changing provider settings`() =
        DesktopManagementFixture().use { fixture ->
            val values = mutableMapOf<String, String>()
            var flushes = 0
            val store = ApplicationUiSettingsStore(values::get, values::set) { flushes++ }
            val settings = mutableStateOf(store.load())
            val visible = mutableStateOf(true)
            val providerBefore = fixture.settings.load()
            runDesktopComposeUiTest(width = 1000, height = 760) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        if (visible.value) {
                            ManagementSettingsScreen(fixture, UiLanguage.ENGLISH, settings.value) {
                                settings.value = it
                                assertTrue(store.save(it))
                            }
                        } else {
                            Text("Away from settings")
                        }
                    }
                }
                onNodeWithText("Provider").assertIsSelected()
                selectCategory("Agent")
                awaitNode("ai-management-import")
                runOnIdle {
                    assertEquals(AiSettingsCategory.AGENT, store.load().aiSettingsCategory)
                    assertEquals(1, flushes)
                    visible.value = false
                }
                onNodeWithText("Away from settings").assertIsDisplayed()
                runOnIdle { visible.value = true }
                onNodeWithText("Agent").assertIsSelected()
                onNodeWithTag("ai-import-codex").assertDoesNotExist()
                selectCategory("Policy")
                runOnIdle { visible.value = false }
                onNodeWithText("Away from settings").assertIsDisplayed()
                runOnIdle {
                    settings.value = ApplicationUiSettingsStore(values::get, values::set).load()
                    visible.value = true
                }
                onNodeWithText("Policy").assertIsSelected()
                onNodeWithTag("ai-management-allow-scripts").assertIsDisplayed()
                assertEquals(2, flushes)
                assertEquals(providerBefore, fixture.settings.load())
                assertTrue(fixture.credentials.values.isEmpty())
                fixture.assertNoRequests()
            }
        }

    @Test
    fun `unified AI settings exposes provider agent skill policy and both local read buttons without the old provider page`() =
        DesktopManagementFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1000, height = 760) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        DesktopAppSettingsPage(
                            selectedPage = SettingsPage.AI,
                            applicationSettings = ApplicationUiSettings(),
                            simpleperfSettings = SimpleperfUiSettings(),
                            simpleperfCaptureSettingsContext = null,
                            simpleperfInitialSection = CaptureSettingsSection.SAMPLING_TEMPLATE,
                            viewerThemeContext = rememberViewerThemeContext(),
                            language = UiLanguage.ENGLISH,
                            simpleperfLocale = Locale.US,
                            sourceWorkspaceRuntime = fixture.source,
                            onPageSelected = {},
                            onApplicationSettingsChanged = {},
                            onSimpleperfSettingsChanged = {},
                            onLayoutInspectorSettingsChanged = {},
                            onOpenUserGuide = {},
                            persistenceErrorPage = null,
                            aiPlatformRuntime = fixture.platform,
                            aiPlatformSettingsStore = fixture.settings,
                        )
                    }
                }
                assertConnectionGroups()
                onNodeWithTag("ai-import-codex").performScrollTo().assertIsDisplayed()
                onNodeWithTag("ai-import-claude").assertDoesNotExist()
                onNodeWithTag("ai-management-provider-model").assertDoesNotExist()
                selectCategory("Agent")
                awaitNode("ai-management-import")
                onNodeWithTag("ai-management-extensions-agent").assertIsDisplayed()
                selectCategory("Skill")
                awaitNode("ai-management-import")
                onNodeWithTag("ai-management-extensions-skill").assertIsDisplayed()
                selectCategory("Policy")
                onNodeWithTag("ai-management-allow-artifacts").assertIsDisplayed()
                onNodeWithTag("ai-management-allow-scripts").assertIsDisplayed()
                onNodeWithTag("ai-management-retain-history").assertIsDisplayed()
                selectCategory("Provider")
                assertConnectionGroups()
                onNodeWithTag("ai-import-codex").performScrollTo().assertIsDisplayed()
                onNodeWithTag("ai-import-claude").assertDoesNotExist()
                fixture.assertNoRequests()
            }
        }

    @Test
    fun `settings management and independent local connection imports share existing store without inference requests`() =
        DesktopManagementFixture().use { fixture ->
            val readSources = CopyOnWriteArrayList<LocalAiConfigSource>()
            val reader =
                object : LocalAiConfigurationReader {
                    override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport {
                        readSources += source
                        return LocalAiConfigurationImport.Success(
                            ImportedAiConfiguration(source, "fixture-key", fixture.endpoint, "imported-${source.name.lowercase()}"),
                        )
                    }
                }
            val external = fixture.newSettingsStore()
            runDesktopComposeUiTest(width = 720, height = 760) {
                setContent {
                    ViewerTheme(darkTheme = true) {
                        AiSettingsPageContent(
                            UiLanguage.ENGLISH,
                            fixture.source,
                            fixture.platform,
                            localConfigReader = reader,
                            platformSettingsStore = fixture.settings,
                        )
                    }
                }
                assertTrue(readSources.isEmpty())
                assertConnectionGroups()
                fixture.assertNoRequests()
                onNodeWithTag("ai-import-codex").performScrollTo().performClick()
                awaitConnectionModel(LocalAiConfigSource.CODEX, "imported-codex")
                waitUntil(timeoutMillis = 10_000) {
                    SemanticsProperties.Disabled !in onNodeWithTag("ai-provider-selector").fetchSemanticsNode().config
                }
                selectProvider("Claude")
                onNodeWithTag("ai-import-claude").performScrollTo().performClick()
                awaitConnectionModel(LocalAiConfigSource.CLAUDE, "imported-claude")
                onNodeWithTag("ai-codex-connection-model").assertDoesNotExist()
                assertTrue(fixture.credentials.values.isEmpty())
                assertEquals(listOf(LocalAiConfigSource.CODEX, LocalAiConfigSource.CLAUDE), readSources)
                waitUntil(timeoutMillis = 10_000) {
                    SemanticsProperties.Disabled !in onNodeWithTag("ai-claude-model-refresh").fetchSemanticsNode().config
                }
                fixture.assertCatalogRequests(2)
                fixture.assertNoInferenceRequests()
                onNodeWithTag("ai-claude-connection-save").performScrollTo().performClick()
                waitUntil { external.settings.value.model == "imported-claude" }
                assertEquals("claude", external.load().providerId)
                assertEquals("fixture-key", fixture.source.credential("claude:api-key"))
                onNodeWithTag("ai-management-provider-codex").assertDoesNotExist()
                onNodeWithTag("ai-management-provider-model").assertDoesNotExist()
                selectCategory("Policy")
                onNodeWithTag("ai-management-allow-artifacts").performClick().assertIsOn()
                onNodeWithTag("ai-management-allow-scripts").performClick().assertIsOn()
                selectCategory("Agent")
                val report = fixture.platform.registry.resolve(ExtensionKind.AGENT, "aps.report")
                val tag = "ai-management-select-agent-${report.key.id}-${report.key.version}-builtin"
                awaitNode(tag)
                onNodeWithTag(tag).performScrollTo().performClick()
                waitUntil { external.settings.value.agentId == "aps.report" }
                assertEquals(AiPlatformSettings("claude", "imported-claude", true, "aps.report", true), external.load())
                selectCategory("Skill")
                awaitNode("ai-management-import")
                onNodeWithTag("ai-management-browse").assertIsDisplayed()
                assertEquals("imported-claude", fixture.source.claudeModel())
                assertEquals("fixture-key", fixture.source.credential("claude:api-key"))
                fixture.assertCatalogRequests(2)
                fixture.assertNoInferenceRequests()
            }
        }

    @Test
    fun `runtime unavailable keeps all management categories explicit and connection reads reachable`() =
        DesktopManagementFixture().use { fixture ->
            runDesktopComposeUiTest(width = 560, height = 760) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiSettingsPageContent(UiLanguage.SIMPLIFIED_CHINESE, fixture.source, platformSettingsStore = fixture.settings)
                    }
                }
                listOf("Agent", "Skill", "执行策略").forEach { title ->
                    selectCategory(title)
                    onNodeWithTag("ai-management-unavailable").assertIsDisplayed()
                }
                selectCategory("Provider")
                assertConnectionGroups()
                onNodeWithTag("ai-import-codex").performScrollTo().assertIsDisplayed()
                onNodeWithTag("ai-import-claude").assertDoesNotExist()
                fixture.assertNoRequests()
            }
        }

    private fun DesktopComposeUiTest.selectCategory(title: String) {
        onNodeWithText(title).assertIsDisplayed().performClick()
    }

    private fun DesktopComposeUiTest.assertConnectionGroups() {
        listOf("codex", "claude").forEach { source ->
            selectProvider(if (source == "codex") "Codex" else "Claude")
            onNodeWithTag("ai-connection-group-$source").assertExists()
            onNodeWithTag("ai-$source-connection-model").assertExists()
            onNodeWithTag("ai-$source-connection-save").assertExists()
        }
        selectProvider("Codex")
    }

    private fun DesktopComposeUiTest.selectProvider(label: String) {
        onNodeWithTag("ai-provider-selector").performScrollTo().performClick()
        onNode(hasText(label) and hasAnyAncestor(hasTestTag("ai-provider-menu"))).performClick()
    }

    private fun DesktopComposeUiTest.assertAiSettingsHeader(title: String, labels: List<String>) {
        onAllNodesWithTag("ai-settings-title", useUnmergedTree = true).assertCountEquals(1)
        onAllNodes(
            hasText(title) and hasAnyAncestor(hasTestTag("ai-settings-page")),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        val titleNode = onNodeWithTag("ai-settings-title", useUnmergedTree = true)
        titleNode.assertIsDisplayed().assertTextEquals(title)
        val titleLayouts = mutableListOf<TextLayoutResult>()
        titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        val titleFontSize = titleLayouts.single().layoutInput.style.fontSize
        assertEquals(16.sp, titleFontSize)
        assertEquals(ViewerTypography.pageTitle.fontSize, titleFontSize)
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val headerBounds = onNodeWithTag("ai-settings-category").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rowBounds = onNodeWithTag("ai-settings-category-row").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.bottom < rowBounds.top, "AI Settings title must stay above the category row")
        assertTrue(titleBounds.bottom < headerBounds.top, "AI Settings title must stay above the selector")
        assertTrue(headerBounds.top >= rowBounds.top && headerBounds.bottom <= rowBounds.bottom)
        assertEquals(rowBounds.center.x, headerBounds.center.x, 1f)
        assertTrue(headerBounds.width < rowBounds.width)
        assertEquals(30f, headerBounds.height, 1f)
        labels.forEach { label ->
            val bounds = onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= headerBounds.top && bounds.bottom <= headerBounds.bottom)
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(titleFontSize, layouts.single().layoutInput.style.fontSize, "$label must match the title font size")
            val textBounds = onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(layouts.single().getBoundingBox(label.lastIndex).right <= textBounds.width + 1f)
            assertTrue(layouts.single().multiParagraph.height <= headerBounds.height)
            assertFalse(layouts.single().isLineEllipsized(0))
        }
    }

    private fun DesktopComposeUiTest.assertSharedTextSizes() {
        val nodes =
            onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
        val layouts = mutableListOf<TextLayoutResult>()
        runOnIdle { nodes.forEach { it.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) } }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertTrue(
                layout.layoutInput.style.fontSize in setOf(11.sp, 13.sp, 16.sp),
                "Unexpected AI settings font: ${layout.layoutInput}",
            )
        }
    }

    private fun DesktopComposeUiTest.saveSettingsScreenshot(name: String) {
        val pixels = onRoot().captureToImage().toPixelMap()
        val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                screenshot.setRGB(x, y, pixels[x, y].toArgb())
            }
        }
        val path =
            java.nio.file.Path
                .of("build/reports/ai-settings-tabs/$name.png")
        Files.createDirectories(path.parent)
        ImageIO.write(screenshot, "png", path.toFile())
    }

    private fun DesktopComposeUiTest.awaitNode(tag: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    }

    private fun DesktopComposeUiTest.awaitConnectionModel(source: LocalAiConfigSource, model: String) {
        val tag = "ai-${source.name.lowercase(Locale.ROOT)}-connection-model"
        onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        waitUntil(timeoutMillis = 10_000) {
            onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text == model
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun ManagementSettingsScreen(
    fixture: DesktopManagementFixture,
    language: UiLanguage,
    settings: ApplicationUiSettings = ApplicationUiSettings(),
    onSettingsChanged: (ApplicationUiSettings) -> Unit = {},
) {
    DesktopAppSettingsPage(
        selectedPage = SettingsPage.AI,
        applicationSettings = settings,
        simpleperfSettings = SimpleperfUiSettings(),
        simpleperfCaptureSettingsContext = null,
        simpleperfInitialSection = CaptureSettingsSection.SAMPLING_TEMPLATE,
        viewerThemeContext = rememberViewerThemeContext(),
        language = language,
        simpleperfLocale = Locale.US,
        sourceWorkspaceRuntime = fixture.source,
        onPageSelected = {},
        onApplicationSettingsChanged = onSettingsChanged,
        onSimpleperfSettingsChanged = {},
        onLayoutInspectorSettingsChanged = {},
        onOpenUserGuide = {},
        persistenceErrorPage = null,
        aiPlatformRuntime = fixture.platform,
        aiPlatformSettingsStore = fixture.settings,
    )
}

private class DesktopManagementFixture : AutoCloseable {
    private val root = Files.createTempDirectory("aps-desktop-ai-management-").toRealPath()
    private val sourcePreferences = Preferences.userRoot().node("aps-management-source-${UUID.randomUUID()}")
    private val platformPreferences = Preferences.userRoot().node("aps-management-platform-${UUID.randomUUID()}")
    val settings = AiPlatformSettingsStore(platformPreferences).also { it.save(AiPlatformSettings(providerId = "codex")) }
    private val providerCalls = AtomicInteger()
    private val availabilityChecks = AtomicInteger()
    private val networkRequests = AtomicInteger()
    private val catalogRequests = CopyOnWriteArrayList<Pair<AiHttpMethod, String>>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http ->
            http.createContext("/") { exchange ->
                networkRequests.incrementAndGet()
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
            http.start()
        }
    val endpoint = "http://127.0.0.1:${server.address.port}/v1"
    val credentials = ManagementCredentials()
    private val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
    val source =
        SourceWorkspaceRuntime(
            repository,
            DefaultSourceWorkspaceService(
                SourceProviderRegistry(listOf(LocalSourceProvider())),
                repository,
                ContentAddressedSourceCache(root.resolve("cache")),
            ),
            IndexedSourceResolver(repository),
            SqliteAnalysisSessionRepository(root.resolve("analysis.db")),
            credentials,
            sourcePreferences,
            modelCatalogTransport = AiHttpTransport { request ->
                catalogRequests += request.method to request.url
                AiHttpResponse(200, """{"data":[{"id":"fixture-model"}]}""")
            },
        ).also { it.saveAiConfiguration("fixture-model", endpoint) }
    val platform =
        AiPlatformRuntime.open(
            root.resolve("profile"),
            openAiAvailable = {
                availabilityChecks.incrementAndGet()
                true
            },
            openAiGateway = {
                providerCalls.incrementAndGet()
                AiAnalysisGateway { error("Unexpected provider execution") }
            },
        )

    fun newSettingsStore(): AiPlatformSettingsStore = AiPlatformSettingsStore(platformPreferences)

    fun assertNoRequests() {
        assertNoInferenceRequests()
        assertCatalogRequests(0)
    }

    fun assertNoInferenceRequests() {
        assertEquals(0, availabilityChecks.get())
        assertEquals(0, providerCalls.get())
        assertEquals(0, networkRequests.get())
    }

    fun assertCatalogRequests(count: Int) {
        assertEquals(List(count) { AiHttpMethod.GET to "$endpoint/models" }, catalogRequests)
    }

    override fun close() {
        platform.close()
        source.close()
        server.stop(0)
        sourcePreferences.removeNode()
        platformPreferences.removeNode()
        root.toFile().deleteRecursively()
    }
}

private class ManagementCredentials : CredentialStore {
    val values = ConcurrentHashMap<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}
