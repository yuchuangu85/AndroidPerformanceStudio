package com.androidperformancestudio.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.compose.inspection.host.ComposeInspectionPreflight
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ComposeAgentControlsTest {
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
            assertTrue(text.contains("需要下载：是"))
            assertTrue(text.contains("需要重启：否"))
            assertEquals("复制完整提示", message.config[SemanticsActions.CustomActions].single().label)
            onNodeWithTag("layout-compose-agent-authorize").assertExists()
        }
}
