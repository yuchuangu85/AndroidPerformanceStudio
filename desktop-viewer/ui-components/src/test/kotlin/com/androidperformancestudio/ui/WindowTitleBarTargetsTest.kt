package com.androidperformancestudio.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WindowTitleBarTargetsTest {
    @Test
    fun `application target clears the old pid and survives shell process refresh`() {
        val host = TitleBarTargetHost()
        host.updateDevices(listOf(TitleBarDeviceOption("serial-a", "Pixel A")))
        val processes = listOf(TitleBarProcessOption(42, "com.example", "com.example"))
        host.updateProcesses(processes)
        host.selectProcess(42)

        host.choosePackage("com.stopped", clearProcess = true)
        host.updateProcesses(processes)

        assertEquals(null, host.selectedProcessPid)
        assertEquals(null, host.selectedProcessName)
        assertEquals("com.stopped", host.selectedPackageName)

        host.selectDevice("serial-b")
        assertEquals(null, host.selectedPackageName)
    }

    @Test
    fun `Process slot searches application names and does not expose pids in app mode`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            host.updateDevices(listOf(TitleBarDeviceOption("serial-a", "Pixel A")))
            host.updateProcesses(listOf(TitleBarProcessOption(42, "com.example", "com.example")))
            host.pageTarget =
                TitleBarPageTarget(
                    serial = "serial-a",
                    options = listOf("com.example", "com.stopped").map { TitleBarPageTargetOption(it) },
                    selectedId = null,
                    enabled = true,
                    onSelectTarget = { host.choosePackage(it, clearProcess = true) },
                )
            setContent { ViewerTheme(darkTheme = false) { TitleBarTargetControls(host) } }

            onNodeWithText("Process").assertExists()
            onNodeWithText("App").assertDoesNotExist()
            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.example (42)").assertDoesNotExist()
            onNodeWithTag("dropdown-selector-search").performTextInput("STOPPED")
            onNodeWithText("com.example").assertDoesNotExist()
            onNodeWithText("com.stopped").performClick()
            assertEquals("com.stopped", host.selectedPackageName)
            assertEquals(null, host.selectedProcessPid)
        }

    @Test
    fun `empty application list disables Process instead of falling back to pids`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            host.updateDevices(listOf(TitleBarDeviceOption("serial-a", "Pixel A")))
            host.updateProcesses(listOf(TitleBarProcessOption(42, "com.example", "com.example")))
            host.pageTarget =
                TitleBarPageTarget(
                    serial = "serial-a",
                    options = emptyList(),
                    selectedId = null,
                    enabled = true,
                    onSelectTarget = {},
                )
            setContent { ViewerTheme(darkTheme = false) { TitleBarTargetControls(host) } }

            onNodeWithText("Process").assertExists()
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
        }

    @Test
    fun `open application menu cannot select the previous device before recomposition`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            host.updateDevices(listOf(TitleBarDeviceOption("serial-a", "Pixel A")))
            val selections = mutableListOf<String>()
            host.pageTarget =
                TitleBarPageTarget(
                    serial = "serial-a",
                    options = listOf(TitleBarPageTargetOption("com.stopped")),
                    selectedId = null,
                    enabled = true,
                    onSelectTarget = selections::add,
                )
            setContent { ViewerTheme(darkTheme = false) { TitleBarTargetControls(host) } }

            onNodeWithContentDescription("Process selector").performClick()
            val selectApplication =
                onNodeWithText("com.stopped")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            runOnIdle {
                host.selectDevice("serial-b")
                selectApplication()
            }

            assertEquals(emptyList(), selections)
            onNodeWithContentDescription("Process selector").assertIsNotEnabled()
        }

    @Test
    fun `retained app and component pages replace Process and reject callbacks from the previous page`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            host.updateDevices(listOf(TitleBarDeviceOption("serial-a", "Pixel A")))
            val selected = mutableListOf<String>()
            val targets =
                listOf(
                    TitleBarPageTarget(
                        "serial-a",
                        listOf(TitleBarPageTargetOption("com.app")),
                        null,
                        true,
                        selected::add,
                    ),
                    TitleBarPageTarget(
                        "serial-a",
                        listOf(TitleBarPageTargetOption("com.app/.Main", "com.app / .Main")),
                        null,
                        true,
                        selected::add,
                    ),
                )
            val active = mutableStateOf(0)
            setContent {
                ViewerTheme(darkTheme = false) {
                    TitleBarTargetControls(host)
                    targets.forEachIndexed { index, target ->
                        CompositionLocalProvider(
                            LocalTitleBarTargetHost provides host,
                            LocalWindowMenuBarActive provides (index == active.value),
                        ) { ActiveTitleBarTarget(target) }
                    }
                }
            }

            onNodeWithContentDescription("Process selector").performClick()
            val selectOldApp = onNodeWithText("com.app").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            runOnIdle {
                host.pageTarget = targets[1]
                selectOldApp()
                active.value = 1
            }
            waitForIdle()
            assertEquals(targets[1], host.pageTarget)
            assertEquals(emptyList(), selected)

            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.app / .Main").performClick()
            assertEquals(listOf("com.app/.Main"), selected)

            runOnIdle { active.value = 0 }
            waitForIdle()
            assertEquals(targets[0], host.pageTarget)
            runOnIdle { active.value = -1 }
            waitForIdle()
            assertEquals(null, host.pageTarget)
        }

    @Test
    fun `single online device is selected automatically and a device change clears the process`() {
        val host = TitleBarTargetHost()

        host.updateDevices(
            listOf(
                TitleBarDeviceOption("serial-a", "Pixel A"),
                TitleBarDeviceOption("serial-b", "Pixel B", online = false),
            ),
        )
        assertEquals("serial-a", host.selectedDeviceSerial)
        host.updateProcesses(listOf(TitleBarProcessOption(42, "com.example", "com.example", "u0_a1")))
        host.selectProcess(42)
        assertEquals(42, host.selectedProcessPid)
        assertEquals("com.example", host.selectedPackageName)

        host.selectDevice("serial-a")
        assertEquals(42, host.selectedProcessPid)

        host.selectDevice("serial-b")
        assertEquals("serial-b", host.selectedDeviceSerial)
        assertEquals(null, host.selectedProcessPid)
        assertEquals(null, host.selectedPackageName)

        host.updateDevices(emptyList())
        assertEquals(null, host.selectedDeviceSerial)
    }

    @Test
    fun `process refresh drops a selection that disappeared but keeps the device`() {
        val host = TitleBarTargetHost()
        host.updateDevices(listOf(TitleBarDeviceOption("serial-a", "Pixel A")))
        host.updateProcesses(listOf(TitleBarProcessOption(42, "com.example", "com.example")))
        host.selectProcess(42)

        host.updateProcesses(listOf(TitleBarProcessOption(43, "com.example:other", "com.example")))

        assertEquals(null, host.selectedProcessPid)
        assertEquals("serial-a", host.selectedDeviceSerial)
        assertEquals(listOf(43), host.processes.map(TitleBarProcessOption::pid))
    }

    @Test
    fun `fixed title bar selects the device and a device-wide process without a refresh action`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            host.updateDevices(
                listOf(
                    TitleBarDeviceOption("serial-a", "Pixel A"),
                    TitleBarDeviceOption("serial-b", "Pixel B"),
                ),
            )
            host.updateProcesses(
                listOf(
                    TitleBarProcessOption(42, "com.example", "com.example", "u0_a1"),
                    TitleBarProcessOption(7, "surfaceflinger", null, "root"),
                ),
            )
            setContent {
                ViewerTheme(darkTheme = false) {
                    TitleBarTargetControls(host, UiLanguage.SIMPLIFIED_CHINESE)
                }
            }

            onNodeWithTag("titlebar-device-slot").assertExists()
            onNodeWithTag("titlebar-process-slot").assertExists()
            onNodeWithText("刷新").assertDoesNotExist()

            onNodeWithContentDescription("设备选择器").performClick()
            onNodeWithText("Pixel B").performClick()
            assertEquals("serial-b", host.selectedDeviceSerial)

            // The shell reloads the process list for the newly selected device.
            runOnIdle {
                host.updateProcesses(
                    listOf(
                        TitleBarProcessOption(42, "com.example", "com.example", "u0_a1"),
                        TitleBarProcessOption(7, "surfaceflinger", null, "root"),
                    ),
                )
            }
            waitForIdle()

            onNodeWithContentDescription("进程选择器").performClick()
            onNodeWithText("surfaceflinger (7)").performClick()
            assertEquals(7, host.selectedProcessPid)
            assertEquals("surfaceflinger", host.selectedProcessName)
        }
}
