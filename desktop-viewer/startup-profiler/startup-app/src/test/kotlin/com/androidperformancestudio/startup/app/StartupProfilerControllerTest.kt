package com.androidperformancestudio.startup.app

import com.androidperformancestudio.startup.model.StartupDevice
import com.androidperformancestudio.startup.model.StartupTarget
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class StartupProfilerControllerTest {
    @Test
    fun `refreshing devices selects the only online device and loads its targets`() =
        runBlocking {
            val backend = FakeStartupBackend()
            val controller =
                StartupProfilerController(
                    backend = backend,
                    databaseFile = createTempDirectory("startup-controller").resolve("startup.db"),
                )

            controller.refreshDevices()

            assertEquals("online", controller.state.value.selectedDeviceSerial)
            assertEquals("dev.example/.MainActivity", controller.state.value.selectedComponentName)
            assertEquals(listOf("listDevices", "listTargets:online"), backend.events)
        }

    private class FakeStartupBackend : StartupBackend {
        val events = mutableListOf<String>()

        override suspend fun listDevices(): StartupBackendResult<List<StartupDevice>> {
            events += "listDevices"
            return StartupBackendResult.Success(
                listOf(
                    StartupDevice("online", "Online device"),
                    StartupDevice("offline", "Offline device", online = false),
                ),
            )
        }

        override suspend fun listTargets(serial: String): StartupBackendResult<List<StartupTarget>> {
            events += "listTargets:$serial"
            return StartupBackendResult.Success(
                listOf(
                    StartupTarget(
                        packageName = "dev.example",
                        componentName = "dev.example/.MainActivity",
                        debuggable = true,
                    ),
                ),
            )
        }

        override fun openRunner(
            serial: String,
            target: StartupTarget,
        ) = StartupBackendResult.Failure("not used")
    }
}
