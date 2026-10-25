package com.androidperformancestudio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WindowTitleBarTargetsTest {
    @Test
    fun `shared selection survives page switches and a new device clears the package`() {
        val host = TitleBarTargetHost()
        host.chooseDevice("serial-a", "Pixel A")
        host.choosePackage("com.example.app")

        assertTrue(host.deviceReady("serial-a"))
        assertTrue(host.packageReady("com.example.app"))
        assertFalse(host.deviceReady("serial-b"))
        assertFalse(host.packageReady("com.other.app"))

        host.chooseDevice("serial-b", "Pixel B")
        assertEquals("serial-b", host.selectedDeviceSerial)
        assertEquals("Pixel B", host.selectedDeviceLabel)
        assertEquals(null, host.selectedPackageName)
    }

    @Test
    fun `shared values remain visible when a page provides no selectors`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            host.chooseDevice("serial-a", "Pixel A")
            host.choosePackage("com.example.app")
            setContent {
                ViewerTheme(darkTheme = false) { TitleBarTargetControls(host) }
            }

            onNodeWithText("Pixel A").assertExists()
            onNodeWithText("com.example.app").assertExists()
        }

    @Test
    fun `refresh follows the active page and stays after the process slot`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            val showPage = mutableStateOf(true)
            val enabled = mutableStateOf(true)
            var refreshCount = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column {
                        CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                            if (showPage.value) {
                                TitleBarRefreshOrInline(enabled.value, { refreshCount++ }) {
                                    Text("Inline refresh")
                                }
                            }
                        }
                        TitleBarTargetControls(host)
                    }
                }
            }

            waitForIdle()
            onNodeWithText("Inline refresh").assertDoesNotExist()
            val processRight = onNodeWithTag("titlebar-process-slot").fetchSemanticsNode().boundsInRoot.right
            val refreshLeft = onNodeWithTag("titlebar-refresh-slot").fetchSemanticsNode().boundsInRoot.left
            assertTrue(processRight < refreshLeft)
            onNodeWithText("Refresh").performClick()
            assertEquals(1, refreshCount)

            enabled.value = false
            waitForIdle()
            onNodeWithText("Refresh").assertIsNotEnabled()
            showPage.value = false
            waitForIdle()
            assertEquals(null, host.refreshRegistration)
        }

    @Test
    fun `refresh remains inline without an active title bar`() =
        runDesktopComposeUiTest {
            var refreshCount = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalWindowMenuBarActive provides false) {
                        TitleBarRefreshOrInline(true, { refreshCount++ }) {
                            ProfilerCompactButton("Inline refresh", { refreshCount++ })
                        }
                    }
                }
            }

            onNodeWithText("Inline refresh").performClick()
            assertEquals(1, refreshCount)
        }

    @Test
    fun `hosted device and process dropdowns remain selectable`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            val device = mutableStateOf<String?>(null)
            val process = mutableStateOf<String?>(null)
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column {
                        CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                            TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {
                                DeviceDropdownSelector(
                                    items = listOf("Pixel 8"),
                                    selectedItem = device.value,
                                    onItemSelected = { device.value = it },
                                    itemLabel = { it },
                                    language = UiLanguage.ENGLISH,
                                )
                            }
                            TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) {
                                DropdownSelector(
                                    items = listOf("com.example"),
                                    selectedItem = process.value,
                                    onItemSelected = { process.value = it },
                                    itemLabel = { it },
                                    placeholder = "Process",
                                    selectorDescription = "Process selector",
                                )
                            }
                        }
                        TitleBarTargetControls(host)
                    }
                }
            }

            waitForIdle()
            onAllNodesWithTag("dropdown-selector-expand-icon", useUnmergedTree = true).assertCountEquals(2)
            onNodeWithContentDescription("Device selector").performClick()
            onNodeWithText("Pixel 8").performClick()
            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.example").performClick()
            assertTrue(device.value == "Pixel 8")
            assertTrue(process.value == "com.example")
        }

    @Test
    fun `active page publishes current device and inactive page falls back inline`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            val active = mutableStateOf(true)
            val device = mutableStateOf("Pixel 8")
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column {
                        CompositionLocalProvider(
                            LocalTitleBarTargetHost provides host,
                            LocalWindowMenuBarActive provides active.value,
                        ) {
                            TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) { Text(device.value) }
                        }
                        TitleBarTargetControls(host)
                    }
                }
            }

            waitForIdle()
            assertTrue(host.hasTargets)
            onNodeWithText("Pixel 8").assertExists()

            device.value = "Pixel 9"
            waitForIdle()
            onNodeWithText("Pixel 9").assertExists()
            onNodeWithText("Pixel 8").assertDoesNotExist()

            active.value = false
            waitForIdle()
            assertFalse(host.hasTargets)
            onNodeWithText("Pixel 9").assertExists()
        }

    @Test
    fun `retained inactive page cannot replace active device and process slots`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column {
                        CompositionLocalProvider(
                            LocalTitleBarTargetHost provides host,
                            LocalWindowMenuBarActive provides true,
                        ) {
                            TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) { Text("Active device") }
                            TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) { Text("Active process") }
                        }
                        CompositionLocalProvider(
                            LocalTitleBarTargetHost provides host,
                            LocalWindowMenuBarActive provides false,
                        ) {
                            TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) { Text("Inactive device") }
                        }
                        TitleBarTargetControls(host)
                    }
                }
            }

            waitForIdle()
            assertTrue(host.hasTargets)
            onNodeWithText("Active device").assertExists()
            onNodeWithText("Active process").assertExists()
            onNodeWithText("Inactive device").assertExists()
            val deviceLeft = onNodeWithText("Active device").fetchSemanticsNode().boundsInRoot.left
            val processLeft = onNodeWithText("Active process").fetchSemanticsNode().boundsInRoot.left
            assertTrue(deviceLeft < processLeft, "Device must precede process in the title bar")
        }

    @Test
    fun `device and process slots retain their widths when a page has no process selector`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            val hasProcess = mutableStateOf(false)
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column {
                        CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                            TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) { Text("Device") }
                            if (hasProcess.value) {
                                TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) { Text("Process") }
                            }
                        }
                        TitleBarTargetControls(host)
                    }
                }
            }

            waitForIdle()
            val deviceBefore = onNodeWithTag("titlebar-device-slot").fetchSemanticsNode().boundsInRoot
            val processBefore = onNodeWithTag("titlebar-process-slot").fetchSemanticsNode().boundsInRoot
            assertEquals(deviceBefore.width, processBefore.width)

            hasProcess.value = true
            waitForIdle()
            assertEquals(deviceBefore, onNodeWithTag("titlebar-device-slot").fetchSemanticsNode().boundsInRoot)
            assertEquals(processBefore, onNodeWithTag("titlebar-process-slot").fetchSemanticsNode().boundsInRoot)
        }
}
