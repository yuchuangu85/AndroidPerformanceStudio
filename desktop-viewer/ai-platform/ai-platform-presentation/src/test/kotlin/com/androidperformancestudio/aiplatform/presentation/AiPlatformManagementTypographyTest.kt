package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.ViewerTypography
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AiPlatformManagementTypographyTest {
    @Test
    fun `unavailable management ignores ambient chat typography for every section`() {
        for (section in AiManagementSection.entries) {
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(darkTheme = false) {
                        CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 27.sp)) {
                            AiPlatformManagementSettings(null, section, UiLanguage.ENGLISH)
                        }
                    }
                }
                onNodeWithTag("ai-management-unavailable").assertFontSize(ViewerTypography.body.fontSize)
            }
        }
    }

    @Test
    fun `agent controls fields and supporting state use explicit shared font roles`() =
        TypographyFixture().use { fixture ->
            runDesktopComposeUiTest(width = 900, height = 720) {
                val agent = fixture.runtime.registry.resolve(ExtensionKind.AGENT, "aps.main")
                val token = "${agent.key.id}-${agent.key.version}-${agent.key.origin.name.lowercase()}"
                managementContent(fixture, AiManagementSection.AGENTS)
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("Selected version", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Import Agent", useUnmergedTree = true).assertFontSize(ViewerTypography.subsectionTitle.fontSize)
                onAllNodesWithText("Selected version", useUnmergedTree = true)[0].assertFontSize(ViewerTypography.bodyCompact.fontSize)
                onNodeWithTag(
                    "ai-management-status-$token",
                    useUnmergedTree = true,
                ).assertFontSize(ViewerTypography.secondary.fontSize)
                onNodeWithTag("ai-management-import-path").assertFontSize(ViewerTypography.body.fontSize)
                onNodeWithText("Package directory", useUnmergedTree = true).assertFontSize(ViewerTypography.secondary.fontSize)
                assertEquals(0, fixture.providerWork.get())
            }
        }

    @Test
    fun `extension import labels errors and hashes keep shared form and supporting sizes`() =
        TypographyFixture().use { fixture ->
            runDesktopComposeUiTest(width = 900, height = 1200) {
                managementContent(fixture, AiManagementSection.AGENTS)
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("SHA256", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("ai-management-import-path").assertFontSize(ViewerTypography.body.fontSize)
                onNodeWithText("Package directory", useUnmergedTree = true).assertFontSize(ViewerTypography.secondary.fontSize)
                onNodeWithText(
                    "Import verified package directory",
                    useUnmergedTree = true,
                ).assertFontSize(ViewerTypography.bodyCompact.fontSize)
                onAllNodesWithText(
                    "SHA256",
                    substring = true,
                    useUnmergedTree = true,
                )[0].assertFontSize(ViewerTypography.secondary.fontSize)
                onNodeWithTag("ai-management-import-path").performTextReplacement(fixture.missingPackage.toString())
                onNodeWithTag("ai-management-import").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    runCatching { onNodeWithTag("ai-management-extension-error").fetchSemanticsNode() }.isSuccess
                }
                onNodeWithTag("ai-management-extension-error").assertFontSize(ViewerTypography.body.fontSize)
                assertEquals(0, fixture.providerWork.get())
            }
        }

    @Test
    fun `policy controls help and explicit retention results use shared typography`() =
        TypographyFixture().use { fixture ->
            runDesktopComposeUiTest(width = 900, height = 720) {
                managementContent(fixture, AiManagementSection.POLICY)
                onNodeWithText(
                    "Allow bounded local report artifacts after confirmation",
                ).assertFontSize(ViewerTypography.bodyCompact.fontSize)
                onNodeWithText(
                    "Allow registered scripts after sandbox preflight and confirmation",
                ).assertFontSize(ViewerTypography.bodyCompact.fontSize)
                onNodeWithText("Source snippets require live workspace authorization.", substring = true)
                    .assertFontSize(ViewerTypography.secondary.fontSize)
                onNodeWithText("Report Output").assertFontSize(ViewerTypography.subsectionTitle.fontSize)
                onNodeWithText("HTML (.html)").assertFontSize(ViewerTypography.secondary.fontSize)
                onNodeWithText("Retain at most 100 conversations / 30 days").assertFontSize(ViewerTypography.secondary.fontSize)
                onNodeWithTag("ai-management-retain-history").performScrollTo().performClick()
                onNodeWithTag("ai-management-retention-result").assertDoesNotExist()
                onNodeWithTag("ai-management-retention-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    runCatching { onNodeWithTag("ai-management-retention-result").fetchSemanticsNode() }.isSuccess
                }
                onNodeWithTag("ai-management-retention-result").assertFontSize(ViewerTypography.body.fontSize)
                assertEquals(0, fixture.providerWork.get())
            }
        }

    private fun DesktopComposeUiTest.managementContent(
        fixture: TypographyFixture,
        section: AiManagementSection,
    ) {
        setContent {
            ViewerTheme(darkTheme = false) {
                CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 27.sp)) {
                    AiPlatformManagementSettings(
                        fixture.runtime,
                        section,
                        UiLanguage.ENGLISH,
                        settingsStoreOverride = fixture.settings,
                    )
                }
            }
        }
    }
}

private fun SemanticsNodeInteraction.assertFontSize(expected: TextUnit) {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(
        expected,
        layouts
            .single()
            .layoutInput.style.fontSize,
    )
}

private class TypographyFixture : AutoCloseable {
    private val root = Files.createTempDirectory("aps-ai-management-type-").toRealPath()
    private val preferences = Preferences.userRoot().node("com/androidperformancestudio/tests/${UUID.randomUUID()}")
    val settings = AiPlatformSettingsStore(preferences).also { it.save(AiPlatformSettings(model = "shared-model")) }
    val providerWork = AtomicInteger()
    val missingPackage = root.resolve("missing-package")
    val runtime =
        AiPlatformRuntime.open(
            root.resolve("profile"),
            openAiAvailable = {
                providerWork.incrementAndGet()
                false
            },
            openAiGateway = {
                providerWork.incrementAndGet()
                AiAnalysisGateway { error("Unexpected provider request") }
            },
        )

    override fun close() {
        runtime.close()
        preferences.removeNode()
        root.toFile().deleteRecursively()
    }
}
