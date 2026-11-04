package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCapturedText
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidTargetMonitorTest {
    @Test
    fun `refresh broadcasts devices applications processes and threads then replays cached values`() =
        kotlinx.coroutines.test.runTest {
            val discovery = FakeDiscovery()
            val monitor = AndroidTargetMonitor(discovery)
            val events = mutableListOf<String>()
            val first =
                monitor.register(
                    object : AndroidTargetListener {
                        override fun onDevices(result: AndroidDeviceInfosResult) {
                            events += "devices:${(result as StudioResult.Success).value.single().serial}"
                        }

                        override fun onApplications(
                            serial: String,
                            result: StudioResult<List<AndroidPackage>>,
                        ) {
                            events += "applications:$serial:${(result as StudioResult.Success).value.single().packageName}"
                        }

                        override fun onProcesses(
                            serial: String,
                            result: StudioResult<List<AndroidProcess>>,
                        ) {
                            events += "processes:$serial:${(result as StudioResult.Success).value.single().pid}"
                        }

                        override fun onThreads(
                            serial: String,
                            pid: Int,
                            result: StudioResult<List<AndroidThread>>,
                        ) {
                            events += "threads:$serial:$pid:${(result as StudioResult.Success).value.single().tid}"
                        }
                    },
                )

            monitor.refreshDevices()
            monitor.refreshTargets("serial-1")
            monitor.refreshThreads("serial-1", 42)
            first.close()

            val replayed = mutableListOf<String>()
            monitor.register(
                object : AndroidTargetListener {
                    override fun onDevices(result: AndroidDeviceInfosResult) {
                        replayed += "devices"
                    }

                    override fun onApplications(
                        serial: String,
                        result: StudioResult<List<AndroidPackage>>,
                    ) {
                        replayed += "applications"
                    }

                    override fun onProcesses(
                        serial: String,
                        result: StudioResult<List<AndroidProcess>>,
                    ) {
                        replayed += "processes"
                    }

                    override fun onThreads(
                        serial: String,
                        pid: Int,
                        result: StudioResult<List<AndroidThread>>,
                    ) {
                        replayed += "threads"
                    }
                },
            )

            assertEquals(
                listOf(
                    "devices:serial-1",
                    "applications:serial-1:com.example.app",
                    "processes:serial-1:42",
                    "threads:serial-1:42:43",
                ),
                events,
            )
            assertEquals(listOf("devices", "applications", "processes", "threads"), replayed)
        }



    @Test
    fun `online device model is enriched by focused getprop even without complete device properties`() =
        kotlinx.coroutines.test.runTest {
            val monitor =
                AndroidTargetMonitors.create(Path.of("adb")) { request, _ ->
                    val stdout =
                        when {
                            request.arguments == listOf("devices", "-l") ->
                                "List of devices attached\nserial-1 device model:unknown product:unknown device:unknown\n"
                            request.arguments == listOf("-s", "serial-1", "shell", "getprop") ->
                                "[ro.product.manufacturer]: [TCL]\n[ro.product.model]: [unknown]\n"
                            else -> ""
                        }
                    HostCommandResult.Completed(
                        HostCommandOutput(
                            pid = 1L,
                            command = request.command,
                            exitCode = 0,
                            stdout = HostCapturedText(stdout, false),
                            stderr = HostCapturedText("", false),
                            startedAt = Instant.EPOCH,
                            finishedAt = Instant.EPOCH,
                        ),
                    )
                }

            val devices = monitor.refreshDevices()

            val device = (devices as StudioResult.Success).value.single()
            assertEquals("TCL", device.manufacturer)
            assertEquals("unknown", device.deviceName)
            assertEquals("TCL unknown(serial-1)", device.displayName)
        }

    @Test
    fun `device identity is resolved once per connection and re-resolved after reconnect`() =
        kotlinx.coroutines.test.runTest {
            var transportId = 1
            val getpropCalls = mutableListOf<Int>()
            val monitor =
                AndroidTargetMonitors.create(Path.of("adb")) { request, _ ->
                    val stdout =
                        if (request.arguments == listOf("devices", "-l")) {
                            "List of devices attached\nserial-1 device model:m transport_id:$transportId\n"
                        } else {
                            getpropCalls += transportId
                            "[ro.product.manufacturer]: [Acme]\n[ro.product.model]: [Phone]\n"
                        }
                    completed(request, stdout)
                }

            monitor.refreshDevices()
            val second = monitor.refreshDevices()
            transportId = 2
            monitor.refreshDevices()

            assertEquals("Acme Phone(serial-1)", (second as StudioResult.Success).value.single().displayName)
            assertEquals(listOf(1, 2), getpropCalls)
        }

    @Test
    fun `concurrent refreshes share one in-flight device discovery`() =
        kotlinx.coroutines.test.runTest {
            var discoveries = 0
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val monitor =
                AndroidTargetMonitor(
                    object : AndroidTargetDiscovery by FakeDiscovery() {
                        override suspend fun devices(cancellationSignal: HostCancellationSignal): AdbDevicesResult {
                            discoveries++
                            gate.await()
                            return StudioResult.Success(listOf(AdbDevice("serial-1", AdbDeviceState.ONLINE)))
                        }
                    },
                )

            val first = async { monitor.refreshDevices() }
            val second = async { monitor.refreshDevices() }
            testScheduler.runCurrent()
            gate.complete(Unit)

            assertEquals(first.await(), second.await())
            assertEquals(1, discoveries)
            assertEquals(first.await(), monitor.cachedDevices())
        }

    @Test
    fun `relative adb executable remains a PATH command when refreshing devices`() =
        kotlinx.coroutines.test.runTest {
            var executable: Path? = null
            val monitor =
                AndroidTargetMonitors.create(Path.of("adb")) { request, _ ->
                    executable = request.executable
                    HostCommandResult.Completed(
                        HostCommandOutput(
                            pid = 1L,
                            command = request.command,
                            exitCode = 0,
                            stdout = HostCapturedText("List of devices attached\nserial-1 device\n", false),
                            stderr = HostCapturedText("", false),
                            startedAt = Instant.EPOCH,
                            finishedAt = Instant.EPOCH,
                        ),
                    )
                }

            val devices = monitor.refreshDevices()

            assertEquals(Path.of("adb"), executable)
            assertEquals("serial-1", (devices as StudioResult.Success).value.single().serial)
        }

    private fun completed(
        request: HostProcessRequest,
        stdout: String,
    ): HostCommandResult =
        HostCommandResult.Completed(
            HostCommandOutput(
                pid = 1L,
                command = request.command,
                exitCode = 0,
                stdout = HostCapturedText(stdout, false),
                stderr = HostCapturedText("", false),
                startedAt = Instant.EPOCH,
                finishedAt = Instant.EPOCH,
            ),
        )

    private class FakeDiscovery : AndroidTargetDiscovery {
        override suspend fun devices(cancellationSignal: HostCancellationSignal): AdbDevicesResult =
            StudioResult.Success(listOf(AdbDevice("serial-1", AdbDeviceState.ONLINE)))

        override suspend fun targets(
            serial: String,
            cancellationSignal: HostCancellationSignal,
        ): StudioResult<AdbTargetSnapshot> =
            StudioResult.Success(
                AdbTargetSnapshot(
                    packages = listOf(AndroidPackage("com.example.app", debuggable = true)),
                    processes = listOf(AndroidProcess(42, 1, "u0_a1", "com.example.app")),
                ),
            )

        override suspend fun threads(
            serial: String,
            pid: Int,
            cancellationSignal: HostCancellationSignal,
        ): StudioResult<List<AndroidThread>> = StudioResult.Success(listOf(AndroidThread(pid, 43, "main")))
    }
}
