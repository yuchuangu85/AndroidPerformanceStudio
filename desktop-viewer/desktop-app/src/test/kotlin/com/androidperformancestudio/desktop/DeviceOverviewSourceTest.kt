package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop.dashboard.DeviceOverviewSource
import com.androidperformancestudio.desktop.dashboard.StudioDeviceOverview
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.AndroidDeviceInfo
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeviceOverviewSourceTest {
    @Test
    fun `successful empty discovery is zero connected devices rather than unavailable`() = runBlocking {
        val overview = DeviceOverviewSource(
            configuredSdkPath = null,
            locateAdb = { Path.of("adb") },
            discover = { StudioResult.Success(emptyList()) },
        ).load()

        assertEquals(0, assertInstanceOf(StudioDeviceOverview.Available::class.java, overview).connectedCount)
    }

    @Test
    fun `dashboard preserves canonical device labels and counts online devices only`() = runBlocking {
        val online = device("serial-1", "TCL(serial-1)", AdbDeviceState.ONLINE)
        val offline = device("serial-2", "Pixel(serial-2)", AdbDeviceState.OFFLINE)
        val overview = DeviceOverviewSource(
            configuredSdkPath = "/configured/sdk",
            locateAdb = { configured ->
                assertEquals("/configured/sdk", configured)
                Path.of("adb")
            },
            discover = { StudioResult.Success(listOf(online, offline)) },
        ).load()

        val available = assertInstanceOf(StudioDeviceOverview.Available::class.java, overview)
        assertEquals(1, available.connectedCount)
        assertEquals(listOf("TCL(serial-1)", "Pixel(serial-2)"), available.devices.map { it.displayName })
        assertEquals(listOf("serial-1", "serial-2"), available.devices.map { it.serial })
    }

    @Test
    fun `missing adb never starts discovery and stays unavailable`() = runBlocking {
        val overview = DeviceOverviewSource(
            configuredSdkPath = null,
            locateAdb = { null },
            discover = { error("must not discover") },
        ).load()

        assertEquals(StudioDeviceOverview.Unavailable, overview)
    }

    @Test
    fun `discovery failure is unavailable but cancellation is propagated`() = runBlocking {
        val failed = DeviceOverviewSource(
            configuredSdkPath = null,
            locateAdb = { Path.of("adb") },
            discover = { error("adb failed") },
        )
        assertEquals(StudioDeviceOverview.Unavailable, failed.load())

        val cancelled = DeviceOverviewSource(
            configuredSdkPath = null,
            locateAdb = { Path.of("adb") },
            discover = { throw CancellationException("cancelled") },
        )
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.load() } }
        Unit
    }

    private fun device(serial: String, displayName: String, state: AdbDeviceState): AndroidDeviceInfo =
        AndroidDeviceInfo(
            serial = serial,
            deviceName = "unused",
            displayName = displayName,
            manufacturer = null,
            product = null,
            device = null,
            state = state,
            transportId = null,
            rawState = state.name.lowercase(),
            statusDetail = null,
        )
}
