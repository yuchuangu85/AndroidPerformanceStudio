@file:Suppress("MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.startup.model.StartupDevice
import com.androidperformancestudio.startup.model.StartupTarget
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
class StartupTargetSelectionTest {
    @Test
    fun `hosted Process replaces Application Component and keeps exact component selection across refresh`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val host = readyHost()
            val state = mutableStateOf(readyState())
            setContent { HostedStartup(state, host) }

            onNodeWithTag("startup-target").assertDoesNotExist()
            onNodeWithText("Application / Component").assertDoesNotExist()
            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.running (42)").assertDoesNotExist()
            onNodeWithText("com.launch / .Secondary").performClick()
            waitForIdle()
            assertEquals("com.launch/.Secondary", state.value.selectedComponentName)
            assertEquals("com.launch", host.selectedPackageName)
            assertEquals(null, host.selectedProcessPid)

            runOnIdle { host.updateProcesses(host.processes) }
            onNodeWithText("com.launch / .Secondary").assertExists()
            assertEquals("com.launch/.Secondary", state.value.selectedComponentName)
            onNodeWithTag("startup-start-stop").assertIsEnabled()

            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithTag("dropdown-selector-search").performTextInput("STOPPED")
            onNodeWithText("com.launch / .Main").assertDoesNotExist()
            onNodeWithText("com.stopped / .Main").performClick()
            waitForIdle()
            assertEquals("com.stopped/.Main", state.value.selectedComponentName)
            assertEquals("com.stopped", host.selectedPackageName)
        }

    @Test
    fun `leaving retained Startup restores processes and reentering restores the selected component`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val host = readyHost()
            val state = mutableStateOf(readyState())
            val active = mutableStateOf(true)
            setContent { HostedStartup(state, host, active = active.value) }

            runOnIdle { active.value = false }
            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.running (42)").performClick()
            assertEquals(42, host.selectedProcessPid)

            runOnIdle { active.value = true }
            onNodeWithTag("startup-target").assertDoesNotExist()
            onNodeWithText("com.launch / .Main").assertExists()
            assertEquals("com.launch/.Main", state.value.selectedComponentName)
            assertEquals("com.launch", host.selectedPackageName)
            assertEquals(null, host.selectedProcessPid)
        }

    @Test
    fun `hosted component selector is disabled while refreshing running empty or switching devices`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val host = readyHost()
            val state = mutableStateOf(readyState())
            setContent { HostedStartup(state, host) }
            onNodeWithContentDescription("Process selector").assertIsEnabled()

            runOnIdle { state.value = state.value.copy(isRefreshing = true) }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            runOnIdle { state.value = state.value.copy(isRefreshing = false, isRunning = true) }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            runOnIdle { state.value = readyState().copy(targets = emptyList(), selectedComponentName = null) }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            onNodeWithText("Process").assertExists()

            runOnIdle {
                state.value = readyState()
                host.selectDevice("other-phone")
            }
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
            onNodeWithTag("startup-start-stop").assertIsNotEnabled()
        }

    @Test
    fun `shared package only suggests an initial component when Startup has no explicit selection`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val host = readyHost()
            val state = mutableStateOf(readyState().copy(selectedComponentName = null))
            setContent { HostedStartup(state, host) }

            waitForIdle()
            assertEquals("com.running/.Main", state.value.selectedComponentName)
            assertEquals(null, host.selectedProcessPid)

            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.launch / .Secondary").performClick()
            runOnIdle { host.choosePackage("com.running") }
            waitForIdle()
            assertEquals("com.launch/.Secondary", state.value.selectedComponentName)
            assertEquals("com.launch", host.selectedPackageName)
        }

    @Test
    fun `standalone and native-frame Startup keep the inline Application Component selector`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val state = mutableStateOf(readyState())
            val host = readyHost()
            val hasHost = mutableStateOf(false)
            setContent {
                ViewerTheme(darkTheme = true) {
                    CompositionLocalProvider(
                        LocalTitleBarTargetHost provides host.takeIf { hasHost.value },
                        LocalWindowMenuBarActive provides false,
                    ) {
                        Row {
                            StartupTargetSelectors(
                                state.value,
                                StartupProfilerActions(
                                    onSelectTarget = { state.value = state.value.copy(selectedComponentName = it) },
                                ),
                                UiLanguage.ENGLISH,
                            )
                        }
                    }
                }
            }

            onNodeWithTag("startup-device").assertExists()
            onNodeWithTag("startup-target").performClick()
            onNodeWithText("com.launch / .Secondary").performClick()
            assertEquals("com.launch/.Secondary", state.value.selectedComponentName)

            // A retained/inactive host must not replace inline controls or take shared ownership.
            runOnIdle { hasHost.value = true }
            onNodeWithTag("startup-device").assertExists()
            onNodeWithTag("startup-target").assertExists()
            assertEquals("com.running", host.selectedPackageName)
        }

    @Test
    fun `startup stays disabled until its device matches the shared device`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val host = TitleBarTargetHost().apply { chooseDevice("other-phone", "Other Phone") }
            setContent {
                ViewerTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                        StartupScenarioControls(readyState(), StartupProfilerActions(), UiLanguage.ENGLISH)
                    }
                }
            }

            onNodeWithTag("startup-start-stop").assertIsNotEnabled()
            runOnIdle { host.chooseDevice("phone", "Phone") }
            onNodeWithTag("startup-start-stop").assertIsEnabled()
        }

    @Composable
    private fun HostedStartup(
        state: MutableState<StartupProfilerState>,
        host: TitleBarTargetHost,
        active: Boolean = true,
    ) {
        ViewerTheme(darkTheme = true) {
            Column {
                TitleBarTargetControls(host)
                CompositionLocalProvider(
                    LocalTitleBarTargetHost provides host.takeIf { active },
                    LocalWindowMenuBarActive provides active,
                ) {
                    val actions =
                        StartupProfilerActions(
                            onSelectTarget = { state.value = state.value.copy(selectedComponentName = it) },
                        )
                    Row { StartupTargetSelectors(state.value, actions, UiLanguage.ENGLISH) }
                    StartupScenarioControls(state.value, actions, UiLanguage.ENGLISH)
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
        StartupProfilerState(
            devices = listOf(StartupDevice("phone", "Phone")),
            selectedDeviceSerial = "phone",
            targets =
                listOf(
                    StartupTarget("com.running", "com.running/.Main", true),
                    StartupTarget("com.launch", "com.launch/.Main", true),
                    StartupTarget("com.launch", "com.launch/.Secondary", true),
                    StartupTarget("com.stopped", "com.stopped/.Main", true),
                ),
            selectedComponentName = "com.launch/.Main",
        )
}
