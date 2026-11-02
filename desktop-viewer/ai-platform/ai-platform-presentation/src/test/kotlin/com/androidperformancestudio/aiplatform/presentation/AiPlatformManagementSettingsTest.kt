package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ConversationTurn
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionManifest
import com.androidperformancestudio.aiplatform.model.ExtensionOrigin
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.model.TrustStatus
import com.androidperformancestudio.aiplatform.storage.StoredAnalysisResult
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlinx.serialization.encodeToString
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AiPlatformManagementSettingsTest {
    @Test
    fun `management cards stay independent in every compact section language and theme without side effects`() {
        val cardMatcher =
            SemanticsMatcher("AI management card") { node ->
                SemanticsProperties.TestTag in node.config &&
                    node.config[SemanticsProperties.TestTag].startsWith("ai-management-card-")
            }
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            for (dark in listOf(false, true)) {
                ManagementFixture().use { fixture ->
                    ExtensionKind.entries.forEach { kind ->
                        fixture.runtime.registry.install(fixture.bundle(kind, "example.card.${kind.name.lowercase()}", "1.0.0"))
                    }
                    val entries =
                        fixture.runtime.registry
                            .scan()
                            .entries
                    val original = fixture.settings.load()
                    var section by mutableStateOf(AiManagementSection.AGENTS)
                    var available by mutableStateOf(true)
                    val variant = "560x760-${language.name.lowercase()}-${if (dark) "dark" else "light"}"
                    runDesktopComposeUiTest(width = 560, height = 760) {
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                Surface(Modifier.fillMaxSize().testTag("ai-management-cards")) {
                                    AiPlatformManagementSettings(
                                        if (available) fixture.runtime else null,
                                        section,
                                        language,
                                        settingsStoreOverride = fixture.settings,
                                    )
                                }
                            }
                        }
                        AiManagementSection.entries.forEach { next ->
                            runOnIdle { section = next }
                            val controlsByCard =
                                when (next) {
                                    AiManagementSection.AGENTS, AiManagementSection.SKILLS -> {
                                        val kind = if (next == AiManagementSection.AGENTS) ExtensionKind.AGENT else ExtensionKind.SKILL
                                        awaitEnabled("ai-management-import-path")
                                        buildMap {
                                            put(
                                                "ai-management-card-import-${kind.name.lowercase()}",
                                                listOf("ai-management-import-path", "ai-management-browse", "ai-management-import"),
                                            )
                                            entries.filter { it.key.kind == kind }.forEach { entry ->
                                                val token = "${entry.key.id}-${entry.key.version}-${entry.key.origin.name.lowercase()}"
                                                put(
                                                    "ai-management-card-package-$token",
                                                    buildList {
                                                        add("ai-management-status-$token")
                                                        add("ai-management-version-$token")
                                                        add("ai-management-enabled-$token")
                                                        add("ai-management-trust-$token")
                                                        add("ai-management-revoke-$token")
                                                        if (kind == ExtensionKind.AGENT) add("ai-management-select-agent-$token")
                                                        if (entry.key.origin == ExtensionOrigin.IMPORTED) add("ai-management-delete-$token")
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    AiManagementSection.POLICY ->
                                        mapOf(
                                            "ai-management-card-permissions" to
                                                listOf("ai-management-allow-artifacts", "ai-management-allow-scripts"),
                                            "ai-management-card-history" to listOf("ai-management-retain-history"),
                                        )
                                }
                            onAllNodes(cardMatcher, useUnmergedTree = true)
                                .assertCountEquals(controlsByCard.size)
                                .assertAll(!hasAnyAncestor(cardMatcher))
                            saveManagementScreenshot("${next.name.lowercase()}-$variant")
                            controlsByCard.forEach { (card, controls) ->
                                controls.forEach { control ->
                                    onNodeWithTag(control, useUnmergedTree = true)
                                        .assert(hasAnyAncestor(hasTestTag(card)))
                                        .performScrollTo()
                                        .assertIsDisplayed()
                                }
                            }
                            assertEquals(original, fixture.newSettingsStore().load())
                            fixture.assertNoProviderWork()
                        }
                        AiManagementSection.entries.forEach { next ->
                            runOnIdle {
                                available = false
                                section = next
                            }
                            onAllNodes(cardMatcher, useUnmergedTree = true)
                                .assertCountEquals(1)
                                .assertAll(!hasAnyAncestor(cardMatcher))
                            onNodeWithTag("ai-management-unavailable", useUnmergedTree = true)
                                .assert(hasAnyAncestor(hasTestTag("ai-management-card-unavailable")))
                                .assertIsDisplayed()
                            onNodeWithTag("ai-management-provider-model").assertDoesNotExist()
                            onNodeWithTag("ai-management-import-path").assertDoesNotExist()
                            onNodeWithTag("ai-management-allow-artifacts").assertDoesNotExist()
                            saveManagementScreenshot("unavailable-${next.name.lowercase()}-$variant")
                            assertEquals(original, fixture.newSettingsStore().load())
                            fixture.assertNoProviderWork()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `unavailable runtime is explicit for every section and does not edit shared settings`() =
        ManagementFixture().use { fixture ->
            var section by mutableStateOf(AiManagementSection.AGENTS)
            val original = fixture.settings.load()
            runDesktopComposeUiTest {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiPlatformManagementSettings(null, section, UiLanguage.ENGLISH, settingsStoreOverride = fixture.settings)
                    }
                }
                AiManagementSection.entries.forEach { next ->
                    runOnIdle { section = next }
                    onNodeWithTag("ai-management-unavailable").assertTextContains("AI runtime unavailable", substring = true)
                    onNodeWithTag("ai-management-provider-model").assertDoesNotExist()
                }
                assertEquals(original, fixture.settings.load())
                fixture.assertNoProviderWork()
            }
        }

    @Test
    fun `policy toggles save through shared store and observe external changes without provider work`() =
        ManagementFixture().use { fixture ->
            val external = fixture.newSettingsStore()
            runDesktopComposeUiTest(width = 480, height = 600) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiPlatformManagementSettings(
                            fixture.runtime,
                            AiManagementSection.POLICY,
                            UiLanguage.SIMPLIFIED_CHINESE,
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-management-allow-artifacts").performClick().assertIsOn()
                onNodeWithTag("ai-management-allow-scripts").performClick().assertIsOn()
                assertEquals(AiPlatformSettings("openai", "fixture-model", true, "aps.main", true), external.load())
                runOnIdle { external.save(external.load().copy(providerId = "claude", model = "external-model", allowScripts = false)) }
                onNodeWithTag("ai-management-allow-scripts").assertIsOff()
                onNodeWithTag("ai-management-allow-artifacts").performClick().assertIsOff()
                assertEquals(AiPlatformSettings("claude", "external-model"), external.load())
                fixture.assertNoProviderWork()
            }
        }

    @Test
    fun `builtin agent selection preserves provider model and permissions and skills stay separate`() =
        ManagementFixture().use { fixture ->
            var section by mutableStateOf(AiManagementSection.AGENTS)
            fixture.settings.save(AiPlatformSettings("codex", "selected-model", allowScripts = true))
            runDesktopComposeUiTest(width = 720, height = 600) {
                setContent {
                    ViewerTheme(darkTheme = true) {
                        AiPlatformManagementSettings(fixture.runtime, section, UiLanguage.ENGLISH, settingsStoreOverride = fixture.settings)
                    }
                }
                val report = fixture.runtime.registry.resolve(ExtensionKind.AGENT, "aps.report")
                val token = "${report.key.id}-${report.key.version}-builtin"
                awaitNode("ai-management-select-agent-$token")
                onNodeWithTag("ai-management-select-agent-$token").performScrollTo().performClick()
                waitUntil {
                    fixture
                        .newSettingsStore()
                        .settings.value.agentId == "aps.report"
                }
                onNodeWithTag("ai-management-select-agent-$token").assertIsSelected()
                assertEquals(
                    AiPlatformSettings("codex", "selected-model", agentId = "aps.report", allowScripts = true),
                    fixture.settings.load(),
                )
                onNodeWithTag("ai-management-delete-$token").assertDoesNotExist()
                val chat = fixture.runtime.registry.resolve(ExtensionKind.AGENT, "aps.chat")
                val chatToken = "${chat.key.id}-${chat.key.version}-builtin"
                onNodeWithTag("ai-management-select-agent-$chatToken").performScrollTo().assertIsNotEnabled()
                assertEquals("aps.report", fixture.settings.load().agentId)
                runOnIdle { section = AiManagementSection.SKILLS }
                awaitNode("ai-management-extensions-skill")
                onNodeWithTag("ai-management-select-agent-$token").assertDoesNotExist()
                fixture.assertNoProviderWork()
            }
        }

    @Test
    fun `agent and skill import trust enable version select revoke and exact delete remain available`() {
        ExtensionKind.entries.forEach { kind ->
            ManagementFixture().use { fixture ->
                val id = "example.${kind.name.lowercase()}"
                val firstSource = fixture.bundle(kind, id, "1.0.0")
                val nextSource = fixture.bundle(kind, id, "2.0.0")
                val first = "$id-1.0.0-imported"
                val next = "$id-2.0.0-imported"
                runDesktopComposeUiTest(width = 520, height = 700) {
                    setContent {
                        ViewerTheme(darkTheme = false) {
                            AiPlatformManagementSettings(
                                fixture.runtime,
                                if (kind == ExtensionKind.AGENT) AiManagementSection.AGENTS else AiManagementSection.SKILLS,
                                UiLanguage.ENGLISH,
                                settingsStoreOverride = fixture.settings,
                            )
                        }
                    }
                    importBundle(firstSource, first)
                    onNodeWithTag("ai-management-enabled-$first").performScrollTo().assertIsNotEnabled().assertIsOff()
                    onNodeWithTag("ai-management-trust-$first").performScrollTo().performClick()
                    awaitEnabled("ai-management-enabled-$first")
                    onNodeWithTag("ai-management-enabled-$first").performScrollTo().performClick()
                    awaitToggle("ai-management-enabled-$first", ToggleableState.On)
                    onNodeWithTag("ai-management-enabled-$first").performClick()
                    awaitToggle("ai-management-enabled-$first", ToggleableState.Off)
                    importBundle(nextSource, next)
                    onNodeWithTag("ai-management-version-$first").performScrollTo().performClick()
                    waitUntil { onNodeWithTag("ai-management-version-$first").fetchSemanticsNode().config[SemanticsProperties.Selected] }
                    assertEquals(
                        "1.0.0",
                        fixture.runtime.registry
                            .resolve(kind, id)
                            .key.version,
                    )
                    onNodeWithTag("ai-management-version-$next").performScrollTo().performClick()
                    waitUntil { onNodeWithTag("ai-management-version-$next").fetchSemanticsNode().config[SemanticsProperties.Selected] }
                    assertEquals(
                        "2.0.0",
                        fixture.runtime.registry
                            .resolve(kind, id)
                            .key.version,
                    )
                    onNodeWithTag("ai-management-revoke-$first").performScrollTo().performClick()
                    waitUntil {
                        onNodeWithTag("ai-management-status-$first")
                            .fetchSemanticsNode()
                            .config[SemanticsProperties.Text]
                            .any { it.text.contains("REVOKED") }
                    }
                    onNodeWithTag("ai-management-trust-$first").assertIsNotEnabled()
                    onNodeWithTag("ai-management-enabled-$first").assertIsNotEnabled().assertIsOff()
                    assertEquals(
                        TrustStatus.REVOKED,
                        fixture.runtime.registry
                            .scan()
                            .entries
                            .single {
                                it.key.id == id &&
                                    it.key.version == "1.0.0"
                            }.trustStatus,
                    )
                    onNodeWithTag("ai-management-delete-$next").performScrollTo().performClick()
                    waitUntil { onAllNodesWithTag("ai-management-status-$next").fetchSemanticsNodes().isEmpty() }
                    assertEquals(
                        listOf("1.0.0"),
                        fixture.runtime.registry
                            .scan()
                            .entries
                            .filter { it.key.id == id }
                            .map { it.key.version },
                    )
                    assertTrue(Files.exists(firstSource.resolve("manifest.json")))
                    assertTrue(Files.exists(nextSource.resolve("manifest.json")))
                    fixture.assertNoProviderWork()
                }
            }
        }
    }

    @Test
    fun `invalid imports surface stable errors without trusting enabling or leaking filesystem details`() =
        ManagementFixture().use { fixture ->
            runDesktopComposeUiTest {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiPlatformManagementSettings(
                            fixture.runtime,
                            AiManagementSection.SKILLS,
                            UiLanguage.ENGLISH,
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                awaitEnabled("ai-management-import-path")
                onNodeWithTag("ai-management-import-path").performTextReplacement(fixture.root.resolve("missing-private-value").toString())
                onNodeWithTag("ai-management-import").performClick()
                awaitNode("ai-management-extension-error")
                val error =
                    onNodeWithTag(
                        "ai-management-extension-error",
                    ).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                assertTrue(error.matches(Regex("E_[A-Z0-9_]{1,80}")))
                assertFalse(error.contains("missing-private-value"))
                assertTrue(
                    fixture.runtime.registry
                        .scan()
                        .entries
                        .all { it.key.id.startsWith("aps.") },
                )
                fixture.assertNoProviderWork()
            }
        }

    @Test
    fun `history retention requires a click and retains at most one hundred recent conversations`() =
        ManagementFixture().use { fixture ->
            val now = System.currentTimeMillis()
            repeat(102) { index ->
                val id = "conversation-$index"
                val record =
                    ConversationRecord(
                        id = id,
                        analysisSessionId = "session-$index",
                        contextHash = "a".repeat(64),
                        turns = listOf(ConversationTurn("run-$index", 1, RunStatus.SUCCEEDED, emptyList(), emptyList())),
                    )
                fixture.runtime.history.save(record.copy(turns = record.turns.map { it.copy(status = RunStatus.RUNNING) }))
                fixture.runtime.history.complete(
                    record,
                    StoredAnalysisResult(
                        id,
                        "run-$index",
                        1,
                        "session-$index",
                        "fixture-model",
                        "summary",
                        emptyList(),
                        if (index ==
                            0
                        ) {
                            now - 31L * 86_400_000
                        } else {
                            now + index
                        },
                    ),
                )
            }
            val database =
                fixture.runtime.directories.conversations
                    .resolve("platform.sqlite")
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.prepareStatement("UPDATE conversation_meta SET updated_at = ? WHERE id = ?").use { statement ->
                    statement.setLong(1, now - 31L * 86_400_000)
                    statement.setString(2, "conversation-0")
                    statement.executeUpdate()
                }
            }
            runDesktopComposeUiTest {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        AiPlatformManagementSettings(
                            fixture.runtime,
                            AiManagementSection.POLICY,
                            UiLanguage.ENGLISH,
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                assertEquals(
                    102,
                    fixture.runtime.history
                        .list(1000, 0)
                        .size,
                )
                onNodeWithTag("ai-management-retain-history").performScrollTo().performClick()
                awaitNode("ai-management-retention-dialog")
                onNodeWithTag("ai-management-retention-dialog").assertIsDisplayed()
                onNodeWithTag("ai-management-retention-result").assertDoesNotExist()
                assertEquals(
                    102,
                    fixture.runtime.history
                        .list(1000, 0)
                        .size,
                )
                assertTrue(fixture.runtime.history.load("conversation-0") != null)
                onNodeWithTag("ai-management-retention-cancel").performClick()
                onNodeWithTag("ai-management-retention-dialog").assertDoesNotExist()
                onNodeWithTag("ai-management-retention-result").assertDoesNotExist()
                assertEquals(
                    102,
                    fixture.runtime.history
                        .list(1000, 0)
                        .size,
                )
                assertTrue(fixture.runtime.history.load("conversation-0") != null)
                onNodeWithTag("ai-management-retain-history").performScrollTo().performClick()
                awaitNode("ai-management-retention-dialog")
                onNodeWithTag("ai-management-retention-dialog").assertIsDisplayed()
                onNodeWithTag("ai-management-retention-result").assertDoesNotExist()
                assertEquals(
                    102,
                    fixture.runtime.history
                        .list(1000, 0)
                        .size,
                )
                assertTrue(fixture.runtime.history.load("conversation-0") != null)
                onNodeWithTag("ai-management-retention-confirm").performClick()
                awaitNode("ai-management-retention-result")
                onNodeWithTag("ai-management-retention-dialog").assertDoesNotExist()
                onNodeWithTag("ai-management-retention-result").assertTextContains("2", substring = true)
                assertEquals(
                    100,
                    fixture.runtime.history
                        .list(1000, 0)
                        .size,
                )
                assertEquals(null, fixture.runtime.history.load("conversation-0"))
                fixture.assertNoProviderWork()
            }
        }

    private fun DesktopComposeUiTest.saveManagementScreenshot(name: String) {
        val pixels = onNodeWithTag("ai-management-cards").captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                image.setRGB(x, y, pixels[x, y].toArgb())
            }
        }
        val output = Path.of("build/reports/ai-management-cards/$name.png")
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(image, "png", output.toFile()))
    }

    private fun DesktopComposeUiTest.awaitNode(tag: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    }

    private fun DesktopComposeUiTest.awaitEnabled(tag: String) {
        waitUntil(timeoutMillis = 10_000) { SemanticsProperties.Disabled !in onNodeWithTag(tag).fetchSemanticsNode().config }
    }

    private fun DesktopComposeUiTest.awaitToggle(
        tag: String,
        value: ToggleableState,
    ) {
        waitUntil(timeoutMillis = 10_000) { onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == value }
    }

    private fun DesktopComposeUiTest.importBundle(
        source: Path,
        token: String,
    ) {
        awaitEnabled("ai-management-import-path")
        onNodeWithTag("ai-management-import-path").performScrollTo().performTextReplacement(source.toString())
        onNodeWithTag("ai-management-import").performScrollTo().performClick()
        awaitNode("ai-management-status-$token")
    }
}

private class ManagementFixture : AutoCloseable {
    val root: Path = Files.createTempDirectory("aps-ai-management-").toRealPath()
    private val preferences = Preferences.userRoot().node("com/androidperformancestudio/tests/${UUID.randomUUID()}")
    val settings = AiPlatformSettingsStore(preferences).also { it.save(AiPlatformSettings(model = "fixture-model")) }
    val availabilityChecks = AtomicInteger()
    val gatewayCreations = AtomicInteger()
    val providerRequests = AtomicInteger()
    val runtime =
        AiPlatformRuntime.open(
            root.resolve("profile"),
            openAiAvailable = {
                availabilityChecks.incrementAndGet()
                true
            },
            openAiGateway = {
                gatewayCreations.incrementAndGet()
                AiAnalysisGateway {
                    providerRequests.incrementAndGet()
                    error("Unexpected provider request")
                }
            },
        )

    fun newSettingsStore(): AiPlatformSettingsStore = AiPlatformSettingsStore(preferences)

    fun assertNoProviderWork() {
        assertEquals(0, availabilityChecks.get())
        assertEquals(0, gatewayCreations.get())
        assertEquals(0, providerRequests.get())
    }

    fun bundle(
        kind: ExtensionKind,
        id: String,
        version: String,
    ): Path {
        val source = Files.createTempDirectory(root, "source-")
        val text = "A bounded fixture package"
        Files.writeString(source.resolve("prompt.md"), text)
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val manifest =
            ExtensionManifest(
                kind = kind,
                id = id,
                version = version,
                name = "Management fixture",
                description = "fixture",
                entrypoint = "prompt.md",
                inputSchema = "aps.fixture.input.v1",
                outputSchema = "aps.fixture.output.v1",
                contextKinds = setOf("frame"),
                evidenceKinds = setOf("frame-selection"),
                files = mapOf("prompt.md" to hash),
            )
        Files.writeString(source.resolve("manifest.json"), PlatformJson.codec.encodeToString(manifest))
        return source
    }

    override fun close() {
        runtime.close()
        preferences.removeNode()
        root.toFile().deleteRecursively()
    }
}
