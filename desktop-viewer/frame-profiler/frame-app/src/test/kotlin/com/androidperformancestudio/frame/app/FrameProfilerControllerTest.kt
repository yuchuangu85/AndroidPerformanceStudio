package com.androidperformancestudio.frame.app

import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.ArtifactProducer
import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.frame.analysis.FrameTimelineResult
import com.androidperformancestudio.frame.capture.GfxInfoPollBatch
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.presentation.FrameDeviceOption
import com.androidperformancestudio.frame.presentation.FrameOperationStatus
import com.androidperformancestudio.frame.presentation.FrameProcessOption
import com.androidperformancestudio.platform.perfetto.TraceProcessorTool
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
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
    fun `associates an explicit Perfetto trace with the current frame session`() =
        runBlocking {
            val directory = createTempDirectory("frame-controller-trace")
            val input = directory.resolve("framestats.txt").also { it.writeText(FRAMESTATS) }
            val trace = directory.resolve("frame.perfetto-trace").also { it.writeText("trace") }
            val controller = FrameProfilerController(databaseFile = directory.resolve("frames.db"))

            controller.importFrameStats(input)
            controller.associatePerfettoTrace(trace)

            assertEquals(trace.toAbsolutePath(), controller.state.value.perfettoTraceFile)
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
            assertEquals("device-1", controller.state.value.selectedDeviceSerial)
            assertEquals(process.pid, controller.state.value.selectedProcessId)

            controller.startOnlineCapture()
            assertTrue(controller.state.value.isCapturing)
            assertEquals(1, capture.startCalls)

            controller.pollOnlineCapture()
            assertEquals(1, assertNotNull(controller.state.value.analysis).summary.totalFrames)
            assertEquals(listOf("start warning", "poll warning"), controller.state.value.warnings)

            controller.pollOnlineCapture()
            assertEquals(2, assertNotNull(controller.state.value.analysis).summary.totalFrames)
            assertEquals(
                FrameOperationStatus.Capturing("dev.example.app", "gfxinfo", 2),
                controller.state.value.operationStatus,
            )

            controller.stopOnlineCapture()
            assertFalse(controller.state.value.isCapturing)
            assertEquals(1, capture.stopCalls)
            assertEquals(FrameOperationStatus.CaptureStopped(2), controller.state.value.operationStatus)
        }

    @Test
    fun `bounded capture registers privacy safe Capture evidence and analyzes FrameTimeline rows`() =
        runBlocking {
            val directory = createTempDirectory("frame-controller-bounded")
            val trace = directory.resolve("frame.pftrace").also { it.writeText("trace bytes") }
            val process = FrameProcessOption(pid = 321, name = "dev.example.app", packageName = "dev.example.app")
            val controller =
                FrameProfilerController(
                    onlineBackend =
                        FakeFrameOnlineBackend(
                            process,
                            FakeOnlineFrameCapture(process.packageName, ArrayDeque()),
                        ),
                    databaseFile = directory.resolve("frames.db"),
                    timelineArtifactAnalyzer = SuccessfulTimelineAnalyzer,
                    boundedCaptureBackend = FakeBoundedCaptureBackend(trace),
                )
            controller.refreshDevices()

            controller.captureFrameTimeline(durationMillis = 1_000L)

            val state = controller.state.value
            assertNull(state.errorMessage)
            assertEquals(
                FrameSource.PERFETTO,
                assertNotNull(state.analysis)
                    .frames
                    .single()
                    .sample.source,
            )
            assertEquals(FrameOperationStatus.CaptureStopped(1), state.operationStatus)
            val artifact = assertNotNull(state.artifact)
            assertEquals(ArtifactAcquisitionKind.CAPTURE, artifact.provenance.acquisition.kind)
            assertEquals(ArtifactProducer.Known("Android Perfetto traced"), artifact.provenance.producer)
            assertEquals(ArtifactCompleteness.PARTIAL, artifact.completeness)
            assertFalse(artifact.availableCapabilities.any { it.value == "frame.surface_correlation" })
            assertFalse(CaptureArtifactJson.encode(artifact).contains("device-1"))
            assertEquals(trace.toAbsolutePath(), state.perfettoTraceFile)
        }

    @Test
    fun `failed bounded analysis preserves UNKNOWN raw evidence instead of fabricating COMPLETE`() =
        runBlocking {
            val directory = createTempDirectory("frame-controller-bounded-failure")
            val trace = directory.resolve("frame.pftrace").also { it.writeText("trace bytes") }
            val process = FrameProcessOption(pid = 321, name = "dev.example.app", packageName = "dev.example.app")
            val controller =
                FrameProfilerController(
                    onlineBackend =
                        FakeFrameOnlineBackend(
                            process,
                            FakeOnlineFrameCapture(process.packageName, ArrayDeque()),
                        ),
                    databaseFile = directory.resolve("frames.db"),
                    timelineArtifactAnalyzer =
                        FrameTimelineArtifactAnalyzer { _, _ ->
                            FrameTimelineProcessingResult.Failure("query failed")
                        },
                    boundedCaptureBackend = FakeBoundedCaptureBackend(trace),
                )
            controller.refreshDevices()

            controller.captureFrameTimeline(durationMillis = 1_000L)

            val state = controller.state.value
            assertEquals("query failed", state.errorMessage)
            assertNull(state.analysis)
            val artifact = assertNotNull(state.artifact)
            assertEquals(ArtifactCompleteness.UNKNOWN, artifact.completeness)
            val sidecar = directory.resolve("capture-artifacts/${artifact.id.value}.json")
            assertEquals(
                ArtifactCompleteness.UNKNOWN,
                CaptureArtifactJson.decode(Files.readString(sidecar)).completeness,
            )
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

        override suspend fun openCapture(
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
        var stopCalls: Int = 0
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

        override suspend fun stop(): List<String> {
            stopCalls += 1
            return emptyList()
        }
    }

    private class FakeBoundedCaptureBackend(
        private val trace: Path,
    ) : BoundedFrameTimelineCaptureBackend {
        override suspend fun capture(
            serial: String,
            process: FrameProcessOption,
            durationMillis: Long,
        ): FrameBackendResult<BoundedFrameTimelineCapture> =
            FrameBackendResult.Success(
                BoundedFrameTimelineCapture(
                    traceFile = trace,
                    startedAt = Instant.EPOCH,
                    androidApiLevel = 31,
                ),
            )
    }

    private companion object {
        val SuccessfulTimelineAnalyzer =
            FrameTimelineArtifactAnalyzer { _, _ ->
                FrameTimelineProcessingResult.Success(
                    result =
                        FrameTimelineResult(
                            frames =
                                listOf(
                                    FrameSample(
                                        frameId = 42,
                                        sessionId = "perfetto",
                                        source = FrameSource.PERFETTO,
                                        totalDurationNs = 18_000_000L,
                                    ),
                                ),
                            capabilities = emptySet(),
                        ),
                    tool =
                        TraceProcessorTool(
                            path = Path.of("trace_processor_shell"),
                            version = "v57.2",
                            sha256 = "a".repeat(64),
                        ),
                )
            }

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
