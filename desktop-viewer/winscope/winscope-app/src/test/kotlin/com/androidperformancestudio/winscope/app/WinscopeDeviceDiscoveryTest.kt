package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class WinscopeDeviceDiscoveryTest {
    @Test
    fun `uses the located ADB executable and lists only online devices`() =
        runBlocking {
            val executable = Path.of("/sdk/platform-tools/adb")
            val discovery =
                WinscopeDeviceDiscovery(
                    locateAdb = { StudioResult.Success(executable) },
                    listDevices = { resolved ->
                        assertEquals(executable, resolved)
                        listOf(
                            AdbDevice(serial = "online", state = AdbDeviceState.ONLINE, model = "Pixel_9"),
                            AdbDevice(serial = "offline", state = AdbDeviceState.OFFLINE, model = "Pixel_8"),
                        )
                    },
                )

            val result = assertIs<StudioResult.Success<WinscopeDeviceDiscoveryResult>>(discovery.discover(null))

            assertEquals(executable, result.value.adbExecutable)
            assertEquals(listOf(WinscopeDeviceOption("online", "Pixel 9")), result.value.devices)
        }

    @Test
    fun `surfaces ADB location failures without attempting device discovery`() =
        runBlocking {
            var listedDevices = false
            val discovery =
                WinscopeDeviceDiscovery(
                    locateAdb = {
                        StudioResult.Failure(
                            StudioError(
                                category = ErrorCategory.CONFIGURATION,
                                code = "ADB_NOT_FOUND",
                                message = "adb wasn't found",
                            ),
                        )
                    },
                    listDevices = {
                        listedDevices = true
                        emptyList()
                    },
                )

            val result = assertIs<StudioResult.Failure>(discovery.discover(null))

            assertEquals("ADB_NOT_FOUND", result.error.code)
            assertFalse(listedDevices)
        }
}
