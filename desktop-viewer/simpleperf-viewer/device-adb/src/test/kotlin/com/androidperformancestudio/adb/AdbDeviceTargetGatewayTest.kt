package com.androidperformancestudio.adb

import com.androidperformancestudio.application.CapabilityStatus
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.ThreadOption
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AdbDeviceTargetGatewayTest {
    @Test
    fun `maps adb device states to device options`() =
        runBlocking {
            val gateway = gateway()

            val devices = assertIs<StudioResult.Success<List<DeviceOption>>>(gateway.refreshDevices()).value

            assertEquals(
                listOf("serial-1", "offline-1"),
                devices.map(DeviceOption::serial),
            )
        }

    @Test
    fun `combines properties capabilities and targets for an online selection`() =
        runBlocking {
            val gateway = gateway()

            val value = assertIs<StudioResult.Success<DeviceSelection>>(gateway.loadSelection("serial-1")).value

            assertEquals("Pixel 8", value.model)
            assertEquals(CapabilityStatus.LIMITED, value.capabilities.status)
            assertEquals(listOf("cpu-clock", "cpu-cycles"), value.capabilities.eventNames)
            assertEquals(listOf("com.example.camera"), value.packages.map { it.packageName })
            assertEquals(listOf(321), value.processes.map { it.pid })
        }

    @Test
    fun `maps thread results without changing pid or tid`() =
        runBlocking {
            val gateway = gateway()

            val values = assertIs<StudioResult.Success<List<ThreadOption>>>(gateway.loadThreads("serial-1", 321)).value

            assertEquals(listOf(321, 333), values.map(ThreadOption::tid))
        }

    @Test
    fun `preserves a properties failure and skips later selection stages`() =
        runBlocking {
            val expected =
                StudioError(
                    category = ErrorCategory.PROCESS_TIMEOUT,
                    code = "PROCESS_TIMEOUT",
                    message = "timed out",
                )
            var laterStages = 0
            val gateway =
                AdbDeviceTargetGateway(
                    refreshDevices = { StudioResult.Success(emptyList()) },
                    readProperties = { StudioResult.Failure(expected) },
                    detectCapabilities = {
                        laterStages += 1
                        error("unexpected capability stage")
                    },
                    refreshTargets = {
                        laterStages += 1
                        error("unexpected target stage")
                    },
                    readThreads = { _, _ -> StudioResult.Success(emptyList()) },
                )

            val result = assertIs<StudioResult.Failure>(gateway.loadSelection("serial-1"))

            assertSame(expected, result.error)
            assertEquals(0, laterStages)
        }

    private fun gateway(): AdbDeviceTargetGateway =
        AdbDeviceTargetGateway(
            refreshDevices = {
                StudioResult.Success(
                    listOf(
                        AdbDevice("serial-1", AdbDeviceState.ONLINE, "device", mapOf("model" to "Pixel_8")),
                        AdbDevice("offline-1", AdbDeviceState.OFFLINE, "offline"),
                    ),
                )
            },
            readProperties = {
                StudioResult.Success(
                    AndroidDeviceProperties("serial-1", "Pixel 8", listOf("arm64-v8a"), 35, "15"),
                )
            },
            detectCapabilities = {
                StudioResult.Success(
                    DeviceCapabilities(
                        serial = "serial-1",
                        readiness = CapabilityReadiness.LIMITED,
                        rootAccess = RootAccess.UNAVAILABLE,
                        profilingScope = ProfilingScope.PROFILEABLE_OR_DEBUGGABLE_APPS,
                        simpleperfVersion = "simpleperf 1.0",
                        eventNames = listOf("cpu-clock", "cpu-cycles"),
                        limitations = setOf(DeviceCapabilityLimitation.ROOT_UNAVAILABLE),
                    ),
                )
            },
            refreshTargets = {
                StudioResult.Success(
                    AdbTargetSnapshot(
                        packages = listOf(AndroidPackage("com.example.camera")),
                        processes = listOf(AndroidProcess(321, 1, "u0_a1", "com.example.camera")),
                    ),
                )
            },
            readThreads = { _, pid ->
                StudioResult.Success(
                    listOf(
                        AndroidThread(pid, pid, "com.example.camera"),
                        AndroidThread(pid, 333, "RenderThread"),
                    ),
                )
            },
        )
}
