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
            assertEquals(listOf("com.example.camera", "com.example.debug"), value.packages.map { it.packageName })
            assertEquals(listOf(321), value.processes.map { it.pid })
        }

    @Test
    fun `preserves simpleperf availability when the device prints no version`() =
        runBlocking {
            val gateway = gateway(simpleperfVersion = null)

            val value = assertIs<StudioResult.Success<DeviceSelection>>(gateway.loadSelection("serial-1")).value

            assertEquals("Available", value.capabilities.simpleperf)
        }

    @Test
    fun `only exposes apps allowed by the device profiling scope`() =
        runBlocking {
            val profileable = assertIs<StudioResult.Success<DeviceSelection>>(gateway().loadSelection("serial-1")).value
            val debuggableOnly =
                assertIs<StudioResult.Success<DeviceSelection>>(
                    gateway(profilingScope = ProfilingScope.DEBUGGABLE_APPS).loadSelection("serial-1"),
                ).value
            val root =
                assertIs<StudioResult.Success<DeviceSelection>>(
                    gateway(profilingScope = ProfilingScope.ANY_PROCESS).loadSelection("serial-1"),
                ).value

            assertEquals(
                listOf("com.example.camera", "com.example.debug"),
                profileable.packages.map { it.packageName },
            )
            assertEquals(listOf("com.example.debug"), debuggableOnly.packages.map { it.packageName })
            assertEquals(
                listOf("com.example.camera", "com.example.debug", "com.example.private"),
                root.packages.map { it.packageName },
            )
        }

    @Test
    fun `exposes debuggable apps through a compatible bundled simpleperf fallback`() =
        runBlocking {
            val value =
                assertIs<StudioResult.Success<DeviceSelection>>(
                    gateway(
                        simpleperfVersion = null,
                        readiness = CapabilityReadiness.BLOCKED,
                        limitations = setOf(DeviceCapabilityLimitation.SIMPLEPERF_UNAVAILABLE),
                        bundledSimpleperfAbis = setOf("arm64-v8a"),
                    ).loadSelection("serial-1"),
                ).value

            assertEquals(CapabilityStatus.LIMITED, value.capabilities.status)
            assertEquals("Missing", value.capabilities.simpleperf)
            assertEquals(
                listOf("com.example.camera", "com.example.debug"),
                value.packages.map { it.packageName },
            )
        }

    @Test
    fun `exposes no apps when simpleperf is blocked without a compatible fallback`() =
        runBlocking {
            val value =
                assertIs<StudioResult.Success<DeviceSelection>>(
                    gateway(
                        readiness = CapabilityReadiness.BLOCKED,
                        limitations = setOf(DeviceCapabilityLimitation.SIMPLEPERF_UNAVAILABLE),
                    ).loadSelection("serial-1"),
                ).value

            assertEquals(emptyList(), value.packages)
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

    private fun gateway(
        simpleperfVersion: String? = "simpleperf 1.0",
        profilingScope: ProfilingScope = ProfilingScope.PROFILEABLE_OR_DEBUGGABLE_APPS,
        readiness: CapabilityReadiness = CapabilityReadiness.LIMITED,
        limitations: Set<DeviceCapabilityLimitation> = setOf(DeviceCapabilityLimitation.ROOT_UNAVAILABLE),
        bundledSimpleperfAbis: Set<String> = emptySet(),
    ): AdbDeviceTargetGateway =
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
                        readiness = readiness,
                        rootAccess = RootAccess.UNAVAILABLE,
                        profilingScope = profilingScope,
                        simpleperfVersion = simpleperfVersion,
                        eventNames = listOf("cpu-clock", "cpu-cycles"),
                        limitations = limitations,
                    ),
                )
            },
            refreshTargets = {
                StudioResult.Success(
                    AdbTargetSnapshot(
                        packages =
                            listOf(
                                AndroidPackage("com.example.camera", profileableByShell = true),
                                AndroidPackage("com.example.debug", debuggable = true),
                                AndroidPackage("com.example.private"),
                            ),
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
            bundledSimpleperfAbis = bundledSimpleperfAbis,
        )
}
