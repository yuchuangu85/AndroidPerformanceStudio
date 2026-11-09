package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.compose.inspection.host.ComposeInspectionPreflight
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_toggle_hint
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ComposeAgentControlsTest {
    @Test
    fun `inspection label and hover explanation are localized even when enabling is unavailable`() {
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            for (canEnable in listOf(false, true)) {
                runDesktopComposeUiTest(width = 420, height = 320) {
                    var requests = 0
                    setContent {
                        ViewerTheme(darkTheme = false) {
                            Box(Modifier.fillMaxSize().padding(24.dp)) {
                                ComposeAgentToggle(false, canEnable, language) { requests++ }
                            }
                        }
                    }
                    val label = if (language == UiLanguage.ENGLISH) "Full Compose Inspection" else "完整 Compose 检查"
                    onNodeWithText(label).assertIsDisplayed()
                    onNodeWithText("Compose Agent").assertDoesNotExist()
                    val toggle = onNodeWithTag("layout-compose-agent-toggle").assertIsOff()
                    if (!canEnable) toggle.assertIsNotEnabled()

                    val tooltipTag = "layout-compose-inspection-tooltip"
                    onNodeWithTag(tooltipTag).assertDoesNotExist()
                    toggle.performMouseInput { moveTo(center) }
                    mainClock.advanceTimeBy(1_000)
                    waitUntil(timeoutMillis = 2_500) {
                        onAllNodesWithTag(tooltipTag).fetchSemanticsNodes().isNotEmpty()
                    }
                    val hint = onNodeWithTag(tooltipTag)
                    hint.assertIsDisplayed().assertTextEquals(
                        localizedStringResource(Res.string.compose_inspection_toggle_hint, language),
                    )
                    assertTrue(hint.fetchSemanticsNode().config[SemanticsActions.CustomActions].isNotEmpty())
                    assertEquals(0, requests, "Hovering must not request inspection")
                    toggle.performMouseInput { moveTo(Offset(-20f, -20f)) }
                    mainClock.advanceTimeBy(1_000)
                    waitUntil(timeoutMillis = 2_500) {
                        onAllNodesWithTag(tooltipTag).fetchSemanticsNodes().isEmpty()
                    }
                }
            }
        }
    }

    @Test
    fun `agent toggle is off by default and always permits disabling`() =
        runDesktopComposeUiTest {
            val checked = mutableStateOf(false)
            val ready = mutableStateOf(true)
            setContent {
                ViewerTheme(darkTheme = false) {
                    ComposeAgentToggle(checked.value, ready.value, UiLanguage.ENGLISH) { checked.value = it }
                }
            }
            val toggle = onNodeWithTag("layout-compose-agent-toggle")
            toggle.assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            toggle.performClick().assertIsOn()
            runOnIdle { ready.value = false }
            toggle.performClick().assertIsOff().assertIsNotEnabled()
        }

    @Test
    fun `preflight requires explicit consent for the displayed target`() =
        runDesktopComposeUiTest {
            var confirmed = 0
            var cancelled = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    ComposeAgentAuthorizationDialog(
                        ComposeInspectionPreflight("device", "dev.sample", 1234, 35, "arm64-v8a", "hash", "1.11.4"),
                        UiLanguage.ENGLISH,
                        onConfirm = { confirmed++ },
                        onCancel = { cancelled++ },
                    )
                }
            }
            onNodeWithText("Enable Full Compose Inspection").assertIsDisplayed()
            onNodeWithText("Allow Inspection").assertIsDisplayed()
            onNodeWithText("dev.sample", substring = true).assertExists()
            assertEquals(0, confirmed)
            onNodeWithText("Cancel").performClick()
            assertEquals(1, cancelled)
            assertEquals(0, confirmed)
            onNodeWithTag("layout-compose-agent-authorize").performClick()
            assertEquals(1, confirmed)
        }

    @Test
    fun `preparation is gated before any target lookup and no longer authorizes implicitly`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val preparation =
            source
                .substringAfter("suspend fun prepareFullComposeForForeground(")
                .substringBefore("val updateComposeAgentRequested")
        assertTrue(source.contains("var composeAgentRequested by remember { mutableStateOf(false) }"))
        assertTrue(preparation.indexOf("if (!composeAgentRequested) return false") < preparation.indexOf("currentCaptureTarget()"))
        assertTrue(preparation.contains("pendingComposeAuthorization = ready"))
        assertFalse(preparation.contains("ComposeInspectionAuthorization.authorize"))
        assertTrue(
            source.contains(
                "LaunchedEffect(manualRefreshRequest, autoScanEnabled, composeAgentRequested, selectedDeviceSerial, captureTargetMode, manualRefreshInvalidation)",
            ),
        )
        assertTrue(source.contains("isCurrentRequest = { composeAgentRequested && pendingComposeAuthorization === ready }"))
    }

    @Test
    fun `manual failure disables the agent only after fallback capture finishes`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val manual =
            source
                .substringAfter("LaunchedEffect(manualRefreshRequest, autoScanEnabled, composeAgentRequested,")
                .substringBefore("var paneWidths")
        val fallback = manual.substringAfter("catch (error: Exception)").substringBefore("manualRefreshRequests.finish(")
        assertFalse(fallback.contains("composeAgentRequested = false"))
        assertTrue(manual.substringAfter("manualRefreshRequests.finish(").contains("composeAgentRequested = false"))
        assertTrue(source.contains("if (!manualRefreshInProgress) composeAgentRequested = false"))
    }

    @Test
    fun `live session is owned before returning across the cancellable dispatcher boundary`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val automatic =
            source
                .substringAfter("LaunchedEffect(autoScanEnabled, selectedDeviceSerial")
                .substringBefore("LaunchedEffect(state.selectedNodeId")
        assertTrue(automatic.contains(".also { fullSession = it }"))
        assertTrue(automatic.contains("if (composeSession === fullSession) composeSession = null"))
        assertTrue(automatic.contains("fullSession?.close()"))
        assertTrue(automatic.contains("injected.close()"))
    }

    @Test
    fun `shared device changes reset consent and disabling preserves imported evidence`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val targetChange =
            source
                .substringAfter("LaunchedEffect(selectedDeviceSerial, captureTargetMode)")
                .substringBefore("LaunchedEffect(selectedDeviceSerial, availableDevices")
        assertTrue(targetChange.contains("updateComposeAgentRequested(false)"))
        val toggle =
            source
                .substringAfter("val updateComposeAgentRequested:")
                .substringBefore("LaunchedEffect(selectedDeviceSerial, captureTargetMode)")
        assertTrue(toggle.contains("store.state.connectionStatus != ConnectionStatus.ARCHIVE"))
        assertTrue(toggle.contains("composePreparation.invalidate()"))
        assertTrue(toggle.contains("composeAuthorization = null"))
        assertTrue(toggle.contains("recompositionActive = false"))
    }

    @Test
    fun `target changes and imports discard manual work and cancelled attempts cannot disable newer consent`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val targetChange =
            source
                .substringAfter("LaunchedEffect(selectedDeviceSerial, captureTargetMode)")
                .substringBefore("LaunchedEffect(selectedDeviceSerial, availableDevices")
        assertTrue(targetChange.contains("manualRefreshRequests.discardThrough(manualRefreshRequest)"))
        val archiveImport = source.substringAfter("val openCaptureArchive:").substringBefore("val clearRecentArchives")
        assertTrue(archiveImport.contains("manualRefreshRequests.discardThrough(manualRefreshRequest)"))
        assertTrue(archiveImport.contains("manualRefreshInvalidation += 1"))
        assertTrue(source.contains("if (!refreshCancelled && !fullComposeEnabled && composeUnavailableReason != null)"))
    }

    @Test
    fun `Chinese consent shows localized requirements and a full message copy action`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = false) {
                    ComposeAgentAuthorizationDialog(
                        ComposeInspectionPreflight("device", "dev.sample", 1234, 35, "arm64-v8a", "hash", "1.11.4"),
                        UiLanguage.SIMPLIFIED_CHINESE,
                        onConfirm = {},
                        onCancel = {},
                    )
                }
            }
            val message = onNodeWithText("dev.sample", substring = true).fetchSemanticsNode()
            val text = message.config[SemanticsProperties.Text].single().text
            onNodeWithText("启用完整 Compose 检查").assertIsDisplayed()
            onNodeWithText("允许检查").assertIsDisplayed()
            assertTrue(text.contains("需要下载：是"))
            assertTrue(text.contains("需要重启：否"))
            assertTrue(text.contains("将在此目标进程中加载 Compose 检查组件"))
            assertTrue(text.contains("CPU 和内存开销"))
            assertTrue(text.contains("恢复兼容采集"))
            assertFalse(text.contains("Compose Agent"))
            assertEquals("复制完整提示", message.config[SemanticsActions.CustomActions].single().label)
            onNodeWithTag("layout-compose-agent-authorize").assertExists()
        }
}
