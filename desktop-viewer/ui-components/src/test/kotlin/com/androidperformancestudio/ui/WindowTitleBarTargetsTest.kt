package com.androidperformancestudio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WindowTitleBarTargetsTest {
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
