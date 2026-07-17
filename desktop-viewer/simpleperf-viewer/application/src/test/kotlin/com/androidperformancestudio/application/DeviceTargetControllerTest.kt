package com.androidperformancestudio.application

import com.androidperformancestudio.capture.CallGraphMode
import com.androidperformancestudio.capture.CaptureRequest
import com.androidperformancestudio.capture.CaptureSession
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.EventScope
import com.androidperformancestudio.capture.SamplingRate
import com.androidperformancestudio.capture.SamplingTemplate
import com.androidperformancestudio.capture.SimpleperfTarget
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceTargetControllerTest {
    @Test
    fun `refreshes devices and selects an online device`() =
        runBlocking {
            val gateway = FakeDeviceTargetGateway()
            val controller = DeviceTargetController(gateway)

            controller.refreshDevices()
            controller.selectDevice("serial-1")

            assertEquals(
                listOf("serial-1", "offline-1"),
                controller.state.value.devices
                    .map(DeviceOption::serial),
            )
            assertEquals("serial-1", controller.state.value.selectedSerial)
            assertEquals(
                CapabilityStatus.LIMITED,
                controller.state.value.selection
                    ?.capabilities
                    ?.status,
            )
            assertEquals(
                2,
                controller.state.value.selection
                    ?.packages
                    ?.size,
            )
            assertFalse(controller.state.value.isLoading)
            assertNull(controller.state.value.error)
        }

    @Test
    fun `searches targets locally without reloading the selected device`() =
        runBlocking {
            val gateway = FakeDeviceTargetGateway()
            val controller = DeviceTargetController(gateway)
            controller.refreshDevices()
            controller.selectDevice("serial-1")

            controller.updateSearch("camera")

            assertEquals(
                listOf("com.example.camera"),
                controller.state.value.visiblePackages
                    .map(PackageOption::packageName),
            )
            assertEquals(
                listOf(321),
                controller.state.value.visibleProcesses
                    .map(ProcessOption::pid),
            )
            assertEquals(1, gateway.selectionLoads)
        }

    @Test
    fun `selecting a process loads threads and prepares capture in the target workspace`() =
        runBlocking {
            val controller = DeviceTargetController(FakeDeviceTargetGateway())
            controller.refreshDevices()
            controller.selectDevice("serial-1")
            controller.selectPackage("com.example.camera")

            controller.selectProcess(321)

            assertEquals(
                listOf(321, 333),
                controller.state.value.threads
                    .map(ThreadOption::tid),
            )
            assertIs<CaptureTarget.Process>(controller.state.value.selectedTarget)
            assertEquals(WorkspacePage.DEVICE_TARGET, controller.state.value.page)
            assertEquals(
                SamplingTemplate.APP_CPU_BASIC,
                controller.state.value.captureSetup
                    ?.template,
            )
            assertTrue(controller.enterCapture())
            assertEquals(WorkspacePage.CAPTURE, controller.state.value.page)
            assertEquals(
                SamplingTemplate.APP_CPU_BASIC,
                controller.state.value.captureSetup
                    ?.template,
            )
            assertEquals(
                SimpleperfTarget.Process(321, appPackage = "com.example.camera"),
                controller.state.value.captureSetup
                    ?.parameters
                    ?.target,
            )
            assertEquals(
                EventScope.USER,
                controller.state.value.captureSetup
                    ?.parameters
                    ?.scope,
            )
        }

    @Test
    fun `process selection is constrained to the previously selected app`() =
        runBlocking {
            val controller = DeviceTargetController(FakeDeviceTargetGateway())
            controller.refreshDevices()
            controller.selectDevice("serial-1")
            controller.selectPackage("com.example.camera")

            assertEquals(
                listOf(321),
                controller.state.value.processesForSelectedPackage
                    .map(ProcessOption::pid),
            )

            controller.selectProcess(654)

            assertIs<CaptureTarget.App>(controller.state.value.selectedTarget)
            assertEquals("com.example.camera", controller.state.value.selectedPackageName)
            assertTrue(
                controller.state.value.threads
                    .isEmpty(),
            )
        }

    @Test
    fun `uses parent app profiling context when a thread is selected on a non-root device`() =
        runBlocking {
            val controller = DeviceTargetController(FakeDeviceTargetGateway())
            controller.refreshDevices()
            controller.selectDevice("serial-1")
            controller.selectPackage("com.example.camera")
            controller.selectProcess(321)
            controller.selectThread(ThreadOption(pid = 654, tid = 655, name = "WrongProcessThread"))

            assertIs<CaptureTarget.Process>(controller.state.value.selectedTarget)

            controller.selectThread(ThreadOption(pid = 321, tid = 333, name = "RenderThread"))

            assertTrue(controller.enterCapture())

            assertEquals(
                SimpleperfTarget.Thread(333, appPackage = "com.example.camera"),
                controller.state.value.captureSetup
                    ?.parameters
                    ?.target,
            )
            assertEquals(
                EventScope.USER,
                controller.state.value.captureSetup
                    ?.parameters
                    ?.scope,
            )
        }

    @Test
    fun `switches capture templates while retaining the selected target`() =
        runBlocking {
            val controller = DeviceTargetController(FakeDeviceTargetGateway())
            controller.refreshDevices()
            controller.selectDevice("serial-1")
            controller.selectPackage("com.example.camera")
            controller.enterCapture()

            controller.selectSamplingTemplate(SamplingTemplate.LOW_OVERHEAD)

            val setup = controller.state.value.captureSetup
            assertEquals(SamplingTemplate.LOW_OVERHEAD, setup?.template)
            assertEquals(SamplingRate.Frequency(100), setup?.parameters?.rate)
            assertEquals(CallGraphMode.FRAME_POINTER, setup?.parameters?.callGraph)
            assertEquals(SimpleperfTarget.App("com.example.camera"), setup?.parameters?.target)
        }

    @Test
    fun `updates advanced sampling parameters used by automatic capture`() =
        runBlocking {
            val controller = DeviceTargetController(FakeDeviceTargetGateway())
            controller.refreshDevices()
            controller.selectDevice("serial-1")
            controller.selectPackage("com.example.camera")
            controller.selectProcess(321)
            controller.enterCapture()
            val parameters =
                requireNotNull(controller.state.value.captureSetup).parameters.copy(
                    event = "instructions",
                    rate = SamplingRate.Period(4_000),
                    durationSeconds = null,
                    callGraph = CallGraphMode.NONE,
                    scope = EventScope.USER,
                )

            controller.updateSamplingParameters(parameters)

            val setup = requireNotNull(controller.state.value.captureSetup)
            assertEquals(parameters, setup.parameters)
        }

    @Test
    fun `starts capture with selected device capability and configured session location`() =
        runBlocking {
            val sessionRoot = Files.createTempDirectory("aps-controller-capture-")
            val captureSession = FakeCaptureSession()
            val controller =
                DeviceTargetController(
                    gateway = FakeDeviceTargetGateway(),
                    captureSession = captureSession,
                    sessionRoot = sessionRoot,
                    sessionIdProvider = { "fixed-session" },
                )
            controller.refreshDevices()
            controller.selectDevice("serial-1")
            controller.selectPackage("com.example.camera")
            controller.selectProcess(321)
            controller.enterCapture()

            val result = controller.startCapture()

            assertIs<CaptureState.Completed>(result)
            val request = requireNotNull(captureSession.request)
            assertEquals("fixed-session", request.sessionId)
            assertEquals(sessionRoot, request.sessionRoot)
            assertEquals("serial-1", request.serial)
            assertEquals("simpleperf 1.0", request.availability.deviceVersion)
            assertEquals(listOf("arm64-v8a"), request.availability.abis)
            assertEquals(
                controller.state.value.captureSetup
                    ?.parameters,
                request.parameters,
            )
        }

    @Test
    fun `forwards capture cancellation to the active session`() {
        val captureSession = FakeCaptureSession()
        val controller = DeviceTargetController(FakeDeviceTargetGateway(), captureSession = captureSession)

        controller.cancelCapture()

        assertTrue(captureSession.cancelled)
    }

    @Test
    fun `forwards graceful capture stop to the active session`() =
        runBlocking {
            val captureSession = FakeCaptureSession()
            val controller = DeviceTargetController(FakeDeviceTargetGateway(), captureSession = captureSession)

            controller.stopCapture()

            assertTrue(captureSession.stopped)
        }

    @Test
    fun `does not enter capture without a selected target`() {
        val controller = DeviceTargetController(FakeDeviceTargetGateway())

        assertFalse(controller.enterCapture())
        assertEquals(WorkspacePage.DEVICE_TARGET, controller.state.value.page)
    }

    @Test
    fun `does not enter capture when device capability is blocked`() =
        runBlocking {
            val controller =
                DeviceTargetController(
                    FakeDeviceTargetGateway(capabilityStatus = CapabilityStatus.BLOCKED),
                )
            controller.refreshDevices()
            controller.selectDevice("serial-1")
            controller.selectPackage("com.example.camera")

            assertFalse(controller.enterCapture())
            assertEquals(WorkspacePage.DEVICE_TARGET, controller.state.value.page)
        }

    @Test
    fun `shows structured refresh failures and clears loading`() =
        runBlocking {
            val expected =
                StudioError(
                    category = ErrorCategory.PROCESS_EXIT,
                    code = "ADB_UNAUTHORIZED",
                    message = "Authorize the device",
                )
            val controller =
                DeviceTargetController(
                    FakeDeviceTargetGateway(deviceResult = StudioResult.Failure(expected)),
                )

            controller.refreshDevices()

            assertEquals(expected, controller.state.value.error)
            assertFalse(controller.state.value.isLoading)
        }

    private class FakeDeviceTargetGateway(
        private val deviceResult: StudioResult<List<DeviceOption>> =
            StudioResult.Success(
                listOf(
                    DeviceOption("serial-1", "Pixel 8", isOnline = true),
                    DeviceOption("offline-1", "Offline device", isOnline = false),
                ),
            ),
        private val capabilityStatus: CapabilityStatus = CapabilityStatus.LIMITED,
    ) : DeviceTargetGateway {
        var selectionLoads: Int = 0

        override suspend fun refreshDevices(): StudioResult<List<DeviceOption>> = deviceResult

        override suspend fun loadSelection(serial: String): StudioResult<DeviceSelection> {
            selectionLoads += 1
            return StudioResult.Success(
                DeviceSelection(
                    serial = serial,
                    model = "Pixel 8",
                    androidVersion = "15",
                    sdkInt = 35,
                    abis = listOf("arm64-v8a"),
                    capabilities =
                        CapabilitySummary(
                            status = capabilityStatus,
                            root = "Unavailable",
                            profilingScope = "Profileable or debuggable apps",
                            simpleperf = "simpleperf 1.0",
                            eventNames = listOf("cpu-clock", "cpu-cycles"),
                            limitations = listOf("Root unavailable"),
                        ),
                    packages = listOf(PackageOption("com.example.camera"), PackageOption("com.example.music")),
                    processes =
                        listOf(
                            ProcessOption(321, "com.example.camera", "u0_a1"),
                            ProcessOption(654, "surfaceflinger", "root"),
                        ),
                ),
            )
        }

        override suspend fun loadThreads(
            serial: String,
            pid: Int,
        ): StudioResult<List<ThreadOption>> =
            StudioResult.Success(
                listOf(
                    ThreadOption(pid, pid, "com.example.camera"),
                    ThreadOption(pid, 333, "RenderThread"),
                ),
            )
    }

    private class FakeCaptureSession : CaptureSession {
        private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
        override val state: StateFlow<CaptureState> = mutableState
        var request: CaptureRequest? = null
        var cancelled: Boolean = false
        var stopped: Boolean = false

        override suspend fun capture(request: CaptureRequest): CaptureState {
            this.request = request
            val completed =
                CaptureState.Completed(
                    request.sessionRoot.resolve(request.sessionId),
                    request.sessionRoot.resolve(request.sessionId).resolve("perf.data"),
                )
            mutableState.value = completed
            return completed
        }

        override fun cancel() {
            cancelled = true
        }

        override suspend fun stop() {
            stopped = true
        }
    }
}
