@file:Suppress("MaxLineLength")

package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.model.BatteryDevice
import com.androidperformancestudio.battery.model.BatteryTarget
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryProfilerControllerTest {
    @Test
    fun `refreshing devices selects the only online device and loads its targets`() =
        runBlocking {
            val backend = FakeBatteryBackend()
            val controller =
                BatteryProfilerController(
                    backend = backend,
                    databaseFile = createTempDirectory("battery-controller").resolve("battery.db"),
                )

            controller.refreshDevices()

            assertEquals("online", controller.state.value.selectedDeviceSerial)
            assertEquals("dev.example", controller.state.value.selectedPackageName)
            assertEquals(listOf("listDevices", "listTargets:online"), backend.events)
        }

    private class FakeBatteryBackend : BatteryBackend {
        val events = mutableListOf<String>()

        override suspend fun listDevices(): BatteryBackendResult<List<BatteryDevice>> {
            events += "listDevices"
            return BatteryBackendResult.Success(
                listOf(
                    BatteryDevice("online", "Online device"),
                    BatteryDevice("offline", "Offline device", online = false),
                ),
            )
        }

        override suspend fun listTargets(serial: String): BatteryBackendResult<List<BatteryTarget>> {
            events += "listTargets:$serial"
            return BatteryBackendResult.Success(listOf(BatteryTarget("dev.example", uid = 10001)))
        }

        override fun openRunner(
            serial: String,
            target: BatteryTarget,
        ) = BatteryBackendResult.Failure("not used")

        override fun openHistorian(serial: String) = BatteryBackendResult.Failure("not used")

        override suspend fun resetStatistics(serial: String): BatteryBackendResult<Unit> = BatteryBackendResult.Success(Unit)
    }
}
