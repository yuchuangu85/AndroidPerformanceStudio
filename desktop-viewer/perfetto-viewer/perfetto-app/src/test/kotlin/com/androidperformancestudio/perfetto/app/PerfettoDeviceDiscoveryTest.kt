package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.PerfettoDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.AndroidDeviceInfo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PerfettoDeviceDiscoveryTest {
    @Test
    fun `discovery retains detected unavailable devices while marking them non-selectable`() =
        runBlocking {
            val result =
                discoverPerfettoDevices("adb") { executable ->
                    assertEquals(Path.of("adb"), executable)
                    StudioResult.Success(
                        listOf(
                            AndroidDeviceInfo(
                                serial = "online",
                                deviceName = "Pixel 8",
                                displayName = "Pixel 8(online)",
                                manufacturer = null,
                                product = null,
                                device = null,
                                state = AdbDeviceState.ONLINE,
                                transportId = null,
                                rawState = "device",
                                statusDetail = null,
                            ),
                            AndroidDeviceInfo(
                                serial = "offline",
                                deviceName = "Pixel 7",
                                displayName = "Pixel 7(offline)",
                                manufacturer = null,
                                product = null,
                                device = null,
                                state = AdbDeviceState.UNAUTHORIZED,
                                transportId = null,
                                rawState = "unauthorized",
                                statusDetail = null,
                            ),
                        ),
                    )
                }

            val success = assertIs<StudioResult.Success<*>>(result)
            assertEquals(
                listOf(
                    PerfettoDevice(
                        serial = "online",
                        model = "Pixel 8",
                        displayName = "Pixel 8(online)",
                    ),
                    PerfettoDevice(
                        serial = "offline",
                        model = "Pixel 7",
                        displayName = "Pixel 7(offline)",
                        online = false,
                    ),
                ),
                success.value,
            )
        }

    @Test
    fun `discovery keeps the canonical device label separate from model and serial`() =
        runBlocking {
            val result =
                discoverPerfettoDevices("adb") {
                    StudioResult.Success(
                        listOf(
                            AndroidDeviceInfo(
                                serial = "UCSCU8AQWG6LJFQS",
                                deviceName = "unknown",
                                displayName = "TCL unknown(UCSCU8AQWG6LJFQS)",
                                manufacturer = "TCL",
                                product = "unknown",
                                device = "unknown",
                                state = AdbDeviceState.ONLINE,
                                transportId = 4,
                                rawState = "device",
                                statusDetail = null,
                            ),
                        ),
                    )
                }

            val success = assertIs<StudioResult.Success<*>>(result)
            assertEquals(
                PerfettoDevice(
                    serial = "UCSCU8AQWG6LJFQS",
                    model = "unknown",
                    displayName = "TCL unknown(UCSCU8AQWG6LJFQS)",
                ),
                assertIs<List<PerfettoDevice>>(success.value).single(),
            )
        }

    @Test
    fun `discovery returns an ADB failure instead of silently reporting no devices`() =
        runBlocking {
            val result =
                discoverPerfettoDevices("adb") {
                    StudioResult.Failure(
                        com.androidperformancestudio.model.StudioError(
                            ErrorCategory.PROCESS_START,
                            "ADB_NOT_FOUND",
                            "adb was not found",
                        ),
                    )
                }

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals(ErrorCategory.PROCESS_START, failure.error.category)
            assertEquals("ADB_NOT_FOUND", failure.error.code)
        }

    @Test
    fun `configured SDK platform tools adb is preferred over PATH lookup`() {
        val sdk = Files.createTempDirectory("perfetto-sdk")
        try {
            val executable = Files.createDirectories(sdk.resolve("platform-tools")).resolve("adb")
            Files.createFile(executable)

            assertEquals(executable.toString(), defaultPerfettoAdbPath(sdk, isWindows = false))
            assertEquals("adb", defaultPerfettoAdbPath(null, isWindows = false))
        } finally {
            Files.walk(sdk).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
