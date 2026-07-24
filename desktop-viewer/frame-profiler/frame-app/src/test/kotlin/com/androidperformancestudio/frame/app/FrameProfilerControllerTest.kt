package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.capture.GfxInfoPollBatch
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.presentation.FrameDeviceOption
import com.androidperformancestudio.frame.presentation.FrameProcessOption
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameProfilerControllerTest {
    @Test
    fun `imports framestats and exposes analysis without replacing the workspace`() =
        runBlocking {
            val directory = createTempDirectory("frame-controller")
            val input = directory.resolve("framestats.txt")
            input.writeText(FRAMESTATS)
            val controller = FrameProfilerController(databaseFile = directory.resolve("frames.db"))

            controller.importFrameStats(input)

            val state = controller.state.value
            assertNull(state.errorMessage)
            assertEquals("framestats.txt", state.importedFileName)
            assertEquals(2, assertNotNull(state.analysis).summary.totalFrames)
            assertNotNull(state.selectedFrameId)
        }

    @Test
    fun `reports malformed imports as recoverable state`() =
        runBlocking {
            val directory = createTempDirectory("frame-controller-invalid")
            val input = directory.resolve("invalid.txt")
            input.writeText("not framestats")
            val controller = FrameProfilerController(databaseFile = directory.resolve("frames.db"))

            controller.importFrameStats(input)

            assertNotNull(controller.state.value.errorMessage)
            assertNull(controller.state.value.analysis)
        }

    @Test
    fun `captures selected online process and incrementally aggregates polled frames`() =
        runBlocking {
            val directory = createTempDirectory("frame-controller-online")
            val process = FrameProcessOption(pid = 321, name = "dev.example.app", packageName = "dev.example.app")
            val capture =
                FakeOnlineFrameCapture(
                    packageName = process.packageName,
                    batches =
                        ArrayDeque(
                            listOf(
                                GfxInfoPollBatch(listOf(frame(0, 10_000_000L)), listOf("poll warning")),
                                GfxInfoPollBatch(listOf(frame(1, 20_000_000L))),
                            ),
                        ),
                )
            val backend = FakeFrameOnlineBackend(process, capture)
            val controller =
                FrameProfilerController(
                    onlineBackend = backend,
                    databaseFile = directory.resolve("frames.db"),
                )

            controller.refreshDevices()
            assertEquals(
                listOf("device-1"),
                controller.state.value.devices
                    .map { it.serial },
            )

            controller.selectDevice("device-1")
            assertEquals(process.pid, controller.state.value.selectedProcessId)

            controller.startOnlineCapture()
            assertTrue(controller.state.value.isCapturing)
            assertEquals(1, capture.startCalls)

            controller.pollOnlineCapture()
            assertEquals(1, assertNotNull(controller.state.value.analysis).summary.totalFrames)
            assertEquals(listOf("start warning", "poll warning"), controller.state.value.warnings)

            controller.pollOnlineCapture()
            assertEquals(2, assertNotNull(controller.state.value.analysis).summary.totalFrames)
            assertEquals("Capturing dev.example.app: 2 frames", controller.state.value.operationMessage)

            controller.stopOnlineCapture()
            assertFalse(controller.state.value.isCapturing)
            assertEquals("Capture stopped: 2 frames.", controller.state.value.operationMessage)
        }

    private fun frame(
        id: Long,
        durationNs: Long,
    ): FrameSample =
        FrameSample(
            frameId = id,
            sessionId = "online-session",
            source = FrameSource.GFXINFO,
            packageName = "dev.example.app",
            processId = 321,
            intendedVsyncNs = id * 8_333_333L,
            frameCompletedNs = id * 8_333_333L + durationNs,
            expectedDurationNs = 8_333_333L,
            expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
            totalDurationNs = durationNs,
        )

    private class FakeFrameOnlineBackend(
        private val process: FrameProcessOption,
        private val capture: OnlineFrameCapture,
    ) : FrameOnlineBackend {
        override suspend fun listDevices(): FrameBackendResult<List<FrameDeviceOption>> =
            FrameBackendResult.Success(listOf(FrameDeviceOption("device-1", "Test device")))

        override suspend fun listProcesses(serial: String): FrameBackendResult<List<FrameProcessOption>> =
            FrameBackendResult.Success(listOf(process))

        override fun openCapture(
            serial: String,
            process: FrameProcessOption,
            sessionId: String,
        ): FrameBackendResult<OnlineFrameCapture> = FrameBackendResult.Success(capture)
    }

    private class FakeOnlineFrameCapture(
        packageName: String,
        private val batches: ArrayDeque<GfxInfoPollBatch>,
    ) : OnlineFrameCapture {
        var startCalls: Int = 0
            private set

        override val metadata =
            FrameCaptureSession(
                id = "online-session",
                source = FrameSource.GFXINFO,
                startedAt = Instant.EPOCH,
                packageName = packageName,
                deviceSerial = "device-1",
            )

        override suspend fun start(): List<String> {
            startCalls += 1
            return listOf("start warning")
        }

        override suspend fun poll(): GfxInfoPollBatch = batches.removeFirst()
    }

    private companion object {
        val FRAMESTATS =
            """
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted
            0,100000000,100000000,101000000,102000000,103000000,105000000,108333333,8333333,106000000,106500000,107000000,108000000,110000000
            0,108333333,108333333,109000000,110000000,111000000,112000000,116666666,8333333,113000000,113500000,114000000,115000000,116000000
            ---PROFILEDATA---
            """.trimIndent()
    }
}
