package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
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
                        override fun onDevices(result: AdbDevicesResult) {
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
                    override fun onDevices(result: AdbDevicesResult) {
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
