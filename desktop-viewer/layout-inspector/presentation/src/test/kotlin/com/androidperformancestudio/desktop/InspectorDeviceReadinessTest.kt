package com.androidperformancestudio.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ConnectionStatus
import com.androidperformancestudio.application.InspectorState
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.AndroidDeviceInfo
import com.androidperformancestudio.ui.TitleBarDeviceOption
import com.androidperformancestudio.ui.TitleBarTargetHost
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
class InspectorDeviceReadinessTest {
    @Test
    fun `host device coming online enables Compose without a second device discovery`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost()
            val checked = mutableStateOf(false)
            val discoveryCalls = AtomicInteger()
            setContent {
                val devices = rememberInspectorDeviceOptions(host, 0) {
                    discoveryCalls.incrementAndGet()
                    emptyList()
                }
                val online = devices.any { it.serial == host.selectedDeviceSerial && it.online }
                ViewerTheme(darkTheme = false) {
                    ComposeAgentToggle(checked.value, online, UiLanguage.ENGLISH) { checked.value = it }
                }
            }
            val toggle = onNodeWithTag("layout-compose-agent-toggle")
            toggle.assertIsOff().assertIsNotEnabled()
            runOnIdle { host.updateDevices(listOf(TitleBarDeviceOption("device", "T702W"))) }
            toggle.assertIsEnabled().performClick().assertIsOn()
            runOnIdle { host.updateDevices(emptyList()) }
            // Lost availability never prevents withdrawing the user's request.
            toggle.assertIsEnabled().performClick().assertIsOff().assertIsNotEnabled()
            runOnIdle { assertEquals(0, discoveryCalls.get()) }
        }

    @Test
    fun `multiple devices require a selected online device`() =
        runDesktopComposeUiTest {
            val host = TitleBarTargetHost().apply {
                updateDevices(listOf(TitleBarDeviceOption("first", "First"), TitleBarDeviceOption("second", "Second")))
            }
            setContent {
                val devices = rememberInspectorDeviceOptions(host, 0) { error("Unexpected discovery") }
                ViewerTheme(darkTheme = false) {
                    ComposeAgentToggle(false, devices.any { it.serial == host.selectedDeviceSerial && it.online }, UiLanguage.ENGLISH) {}
                }
            }
            val toggle = onNodeWithTag("layout-compose-agent-toggle")
            toggle.assertIsNotEnabled()
            runOnIdle { host.selectDevice("second") }
            toggle.assertIsEnabled()
            runOnIdle {
                host.updateDevices(listOf(TitleBarDeviceOption("first", "First", false), TitleBarDeviceOption("second", "Second", false)))
            }
            toggle.assertIsNotEnabled()
        }

    @Test
    fun `standalone discovery notices late connections and disconnections without manual refresh`() =
        runDesktopComposeUiTest {
            val discovered = AtomicReference(emptyList<AndroidDeviceInfo>())
            setContent {
                val devices = rememberInspectorDeviceOptions(null, 0) { discovered.get() }
                Text(devices.joinToString { it.label }.ifEmpty { "No device" })
            }
            onNodeWithText("No device").assertExists()
            discovered.set(listOf(device("physical")))
            mainClock.advanceTimeBy(INSPECTOR_DEVICE_POLL_MILLIS)
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("T702W").fetchSemanticsNodes().isNotEmpty() }
            discovered.set(emptyList())
            mainClock.advanceTimeBy(INSPECTOR_DEVICE_POLL_MILLIS)
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("No device").fetchSemanticsNodes().isNotEmpty() }
        }

    @Test
    fun `standalone selection excludes offline devices and preserves explicit selection`() {
        val devices = listOf(TitleBarDeviceOption("offline", "Offline", false), TitleBarDeviceOption("online", "Online"))
        assertEquals("online", selectedInspectorDeviceSerial(null, devices))
        assertEquals("online", selectedInspectorDeviceSerial("offline", devices))
        val multiple = devices + TitleBarDeviceOption("second", "Second")
        assertEquals(null, selectedInspectorDeviceSerial(null, multiple))
        assertEquals("second", selectedInspectorDeviceSerial("second", multiple))
        assertEquals(null, selectedInspectorDeviceSerial("online", emptyList()))
    }

    @Test
    fun `online device without a capture is not reported disconnected or live`() {
        val state = InspectorState()
        val english = InspectorPresenter.present(state, UiLanguage.ENGLISH, deviceOnline = true)
        val chinese = InspectorPresenter.present(state, UiLanguage.SIMPLIFIED_CHINESE, deviceOnline = true)
        assertEquals("Device online", english.connectionLabel)
        assertEquals("设备在线", chinese.connectionLabel)
        assertEquals(ConnectionTone.SUCCESS, english.connectionTone)
        assertEquals(null, state.snapshot)
        assertEquals(ConnectionStatus.DISCONNECTED, state.connectionStatus)
        assertEquals("Disconnected", InspectorPresenter.present(state, deviceOnline = false).connectionLabel)
    }

    @Test
    fun `device availability does not hide archive capture or error status`() {
        val archive = InspectorState(connectionStatus = ConnectionStatus.ARCHIVE)
        val failed = InspectorState(connectionStatus = ConnectionStatus.ERROR, connectionError = "Capture failed")
        val live = InspectorState(connectionStatus = ConnectionStatus.CONNECTED)
        assertEquals("Offline archive", InspectorPresenter.present(archive, deviceOnline = true).connectionLabel)
        assertEquals("Capture failed", InspectorPresenter.present(failed, deviceOnline = true).connectionLabel)
        assertEquals(ConnectionTone.ERROR, InspectorPresenter.present(failed, deviceOnline = true).connectionTone)
        assertEquals("Live", InspectorPresenter.present(live, deviceOnline = true).connectionLabel)
    }

    @Test
    fun `presentation cache tracks device changes without a new capture`() {
        val cache = InspectorPresentationCache()
        val state = InspectorState()
        val offline = cache.present(state, UiLanguage.ENGLISH, false)
        val online = cache.present(state, UiLanguage.ENGLISH, true)
        assertNotSame(offline, online)
        assertEquals("Device online", online.connectionLabel)
        assertSame(online, cache.present(state, UiLanguage.ENGLISH, true))
        assertEquals("Disconnected", cache.present(state, UiLanguage.ENGLISH, false).connectionLabel)
    }

    private fun device(serial: String) =
        AndroidDeviceInfo(serial, "T702W", "T702W", null, null, null, AdbDeviceState.ONLINE, null, "device", null)
}
