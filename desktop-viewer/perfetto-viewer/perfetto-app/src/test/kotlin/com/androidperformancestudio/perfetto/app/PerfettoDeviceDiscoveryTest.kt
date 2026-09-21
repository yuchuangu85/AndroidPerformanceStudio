package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.PerfettoDevice
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
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
                            AdbDevice(serial = "online", state = AdbDeviceState.ONLINE, model = "Pixel_8"),
                            AdbDevice(serial = "offline", state = AdbDeviceState.UNAUTHORIZED, model = "Pixel_7"),
                        ),
                    )
                }

            val success = assertIs<StudioResult.Success<*>>(result)
            assertEquals(
                listOf(
                    PerfettoDevice(serial = "online", model = "Pixel 8"),
                    PerfettoDevice(serial = "offline", model = "Pixel 7", online = false),
                ),
                success.value,
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
