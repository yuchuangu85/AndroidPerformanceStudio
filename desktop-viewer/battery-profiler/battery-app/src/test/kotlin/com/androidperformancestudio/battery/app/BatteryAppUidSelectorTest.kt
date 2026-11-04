@file:Suppress("MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.battery.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.battery.model.BatteryTarget
import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.TitleBarDeviceOption
import com.androidperformancestudio.ui.TitleBarProcessOption
import com.androidperformancestudio.ui.TitleBarTargetControls
import com.androidperformancestudio.ui.TitleBarTargetHost
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BatteryAppUidSelectorTest {
    @Test
    fun `Process replaces App UID and keeps shared UID packages separately selectable in both languages`() {
        UiLanguage.entries.forEach { language ->
            runDesktopComposeUiTest {
                val host = readyHost()
                val state = mutableStateOf(readyState())
                val chinese = language == UiLanguage.SIMPLIFIED_CHINESE
                val processSelector = if (chinese) "进程选择器" else "Process selector"
                val sharedLabel = if (chinese) "共享" else "shared"
                setContent { HostedBattery(state, host, language = language) }

                onNodeWithContentDescription(if (chinese) "应用 / UID" else "App / UID").assertDoesNotExist()
                onNodeWithContentDescription(processSelector).performClick()
                onNodeWithText("com.running (42)").assertDoesNotExist()
                onNodeWithTag("dropdown-selector-search").performTextInput("10002")
                onNodeWithText("com.stopped · UID 10003").assertDoesNotExist()
                onNodeWithText("com.shared.one · UID 10002 · $sharedLabel").assertExists()
                onNodeWithText("com.shared.two · UID 10002 · $sharedLabel").performClick()
                waitForIdle()
                assertEquals("com.shared.two", state.value.selectedPackageName)
                assertEquals("com.shared.two", host.selectedPackageName)
                assertEquals(null, host.selectedProcessPid)

                runOnIdle { host.updateProcesses(host.processes) }
                onNodeWithText("com.shared.two · UID 10002 · $sharedLabel").assertExists()
                assertEquals("com.shared.two", state.value.selectedPackageName)

                onNodeWithContentDescription(processSelector).performClick()
                onNodeWithTag("dropdown-selector-search").performTextInput("STOPPED")
                onNodeWithText("com.stopped · UID 10003").performClick()
                waitForIdle()
                assertEquals("com.stopped", state.value.selectedPackageName)
                assertEquals("com.stopped", host.selectedPackageName)
            }
        }
    }

    @Test
    fun `leaving retained Battery restores process list and reentering restores App UID list`() =
        runDesktopComposeUiTest {
            val host = readyHost()
            val state = mutableStateOf(readyState())
            val active = mutableStateOf(true)
            setContent { HostedBattery(state, host, active = active.value) }

            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.stopped · UID 10003").performClick()
            waitForIdle()
            runOnIdle { active.value = false }
            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.running (42)").performClick()
            assertEquals(42, host.selectedProcessPid)
            assertEquals("com.stopped", state.value.selectedPackageName)

            runOnIdle { active.value = true }
            onNodeWithContentDescription("App / UID").assertDoesNotExist()
            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.shared.one · UID 10002 · shared").performClick()
            waitForIdle()
            assertEquals("com.shared.one", state.value.selectedPackageName)
            assertEquals(null, host.selectedProcessPid)
        }

    @Test
    fun `App UID selection is disabled while refreshing loading running empty or switching devices`() =
        runDesktopComposeUiTest {
            val host = readyHost()
            val state = mutableStateOf(readyState())
            setContent { HostedBattery(state, host) }
            onNodeWithContentDescription("Process selector").assertIsEnabled()

            runOnIdle { state.value = readyState().copy(isRefreshing = true) }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            runOnIdle { state.value = readyState().copy(isLoadingSession = true) }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            runOnIdle { state.value = readyState().copy(isRunning = true) }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            runOnIdle { state.value = readyState().copy(isRunning = true, isInteractiveActive = true) }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            runOnIdle { state.value = readyState().copy(targets = emptyList(), selectedPackageName = null) }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            onNodeWithText("Process").assertExists()

            runOnIdle {
                state.value = readyState()
                host.selectDevice("other-phone")
            }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
        }

    @Test
    fun `Battery follows valid shared package but ignores an unrelated process package`() =
        runDesktopComposeUiTest {
            val host = readyHost()
            val state = mutableStateOf(readyState().copy(selectedPackageName = "com.stopped"))
            setContent { HostedBattery(state, host) }

            waitForIdle()
            assertEquals("com.running", state.value.selectedPackageName)
            assertEquals(null, host.selectedProcessPid)
            runOnIdle { host.choosePackage("com.not.a.battery.target") }
            waitForIdle()
            assertEquals("com.running", state.value.selectedPackageName)
            assertEquals("com.running", host.selectedPackageName)
        }

    @Test
    fun `standalone App UID selector remains available without publishing into an inactive host`() =
        runDesktopComposeUiTest {
            val host = readyHost()
            val state = mutableStateOf(readyState())
            val hasHost = mutableStateOf(false)
            setContent {
                ViewerTheme(darkTheme = false) {
                    CompositionLocalProvider(
                        LocalTitleBarTargetHost provides host.takeIf { hasHost.value },
                        LocalWindowMenuBarActive provides false,
                    ) {
                        Row {
                            BatteryAppUidSelector(
                                state.value,
                                onSelectTarget = { state.value = state.value.copy(selectedPackageName = it) },
                                language = UiLanguage.ENGLISH,
                            )
                        }
                    }
                }
            }

            onNodeWithContentDescription("App / UID").performClick()
            onNodeWithText("com.stopped · UID 10003").performClick()
            assertEquals("com.stopped", state.value.selectedPackageName)
            runOnIdle { hasHost.value = true }
            onNodeWithContentDescription("App / UID").assertIsEnabled()
            assertEquals("com.running", host.selectedPackageName)
        }

    @Test
    fun `open App UID menu cannot retarget the old device before recomposition`() =
        runDesktopComposeUiTest {
            val host = readyHost()
            val state = mutableStateOf(readyState())
            setContent { HostedBattery(state, host) }
            onNodeWithContentDescription("Process selector").performClick()
            val selectOldApp =
                onNodeWithText("com.stopped · UID 10003").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!

            runOnIdle {
                host.selectDevice("other-phone")
                selectOldApp()
            }
            assertEquals("com.running", state.value.selectedPackageName)
            assertEquals(null, host.selectedPackageName)
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
        }

    @Composable
    private fun HostedBattery(
        state: MutableState<BatteryProfilerState>,
        host: TitleBarTargetHost,
        active: Boolean = true,
        language: UiLanguage = UiLanguage.ENGLISH,
    ) {
        ViewerTheme(darkTheme = false) {
            Column {
                TitleBarTargetControls(host, language)
                CompositionLocalProvider(
                    LocalTitleBarTargetHost provides host.takeIf { active },
                    LocalWindowMenuBarActive provides active,
                ) {
                    Row {
                        BatteryAppUidSelector(
                            state.value,
                            onSelectTarget = { state.value = state.value.copy(selectedPackageName = it) },
                            language = language,
                        )
                    }
                }
            }
        }
    }

    private fun readyHost() =
        TitleBarTargetHost().apply {
            updateDevices(listOf(TitleBarDeviceOption("phone", "Phone")))
            updateProcesses(listOf(TitleBarProcessOption(42, "com.running", "com.running")))
            selectProcess(42)
        }

    private fun readyState() =
        BatteryProfilerState(
            selectedDeviceSerial = "phone",
            selectedPackageName = "com.running",
            targets =
                listOf(
                    BatteryTarget("com.running", 10001),
                    BatteryTarget("com.shared.one", 10002, sharedUid = true),
                    BatteryTarget("com.shared.two", 10002, sharedUid = true),
                    BatteryTarget("com.stopped", 10003),
                ),
        )
}
