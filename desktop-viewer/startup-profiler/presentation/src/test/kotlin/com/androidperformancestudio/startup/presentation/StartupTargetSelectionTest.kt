@file:Suppress("MagicNumber")

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.startup.model.StartupDevice
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.TitleBarProcessOption
import com.androidperformancestudio.ui.TitleBarTargetHost
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StartupTargetSelectionTest {
    @Test
    fun `explicit startup activity survives process refresh without changing the process target`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val process = TitleBarProcessOption(42, "com.running", "com.running")
            val host =
                TitleBarTargetHost().apply {
                    chooseDevice("phone", "Phone")
                    updateProcesses(listOf(process))
                    selectProcess(process.pid)
                }
            val state =
                mutableStateOf(
                    StartupProfilerState(
                        devices = listOf(StartupDevice("phone", "Phone")),
                        selectedDeviceSerial = "phone",
                        targets =
                            listOf(
                                StartupTarget("com.running", "com.running/.Main", true),
                                StartupTarget("com.launch", "com.launch/.Main", true),
                            ),
                        selectedComponentName = "com.running/.Main",
                    ),
                )
            setContent {
                ViewerTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                        val actions =
                            StartupProfilerActions(
                                onSelectTarget = { state.value = state.value.copy(selectedComponentName = it) },
                            )
                        Row { StartupTargetSelectors(state.value, actions, UiLanguage.ENGLISH) }
                        StartupScenarioControls(state.value, actions, UiLanguage.ENGLISH)
                    }
                }
            }

            onNodeWithTag("startup-target").performClick()
            onNodeWithText("com.launch / .Main").performClick()
            waitForIdle()
            assertEquals("com.launch/.Main", state.value.selectedComponentName)
            assertEquals("com.running", host.selectedPackageName)

            runOnIdle {
                host.choosePackage("com.launch")
                host.updateProcesses(listOf(process))
            }
            waitForIdle()
            assertEquals("com.running", host.selectedPackageName)
            assertEquals("com.launch/.Main", state.value.selectedComponentName)
            onNodeWithTag("startup-start-stop").assertIsEnabled()
        }

    @Test
    fun `startup stays disabled until its device matches the shared device`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val host = TitleBarTargetHost().apply { chooseDevice("other-phone", "Other Phone") }
            setContent {
                ViewerTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                        StartupScenarioControls(
                            StartupProfilerState(
                                selectedDeviceSerial = "phone",
                                targets = listOf(StartupTarget("com.local", "com.local/.Main", true)),
                                selectedComponentName = "com.local/.Main",
                            ),
                            StartupProfilerActions(),
                            UiLanguage.ENGLISH,
                        )
                    }
                }
            }

            onNodeWithTag("startup-start-stop").assertIsNotEnabled()
            runOnIdle { host.chooseDevice("phone", "Phone") }
            onNodeWithTag("startup-start-stop").assertIsEnabled()
        }
}
