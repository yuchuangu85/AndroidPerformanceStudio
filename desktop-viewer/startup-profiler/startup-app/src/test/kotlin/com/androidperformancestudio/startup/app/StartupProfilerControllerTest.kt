@file:Suppress("MagicNumber")

package com.androidperformancestudio.startup.app

import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.analysis.StartupComparisonStatus
import com.androidperformancestudio.startup.export.StartupJsonExporter
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupCompilationEvidence
import com.androidperformancestudio.startup.model.StartupDevice
import com.androidperformancestudio.startup.model.StartupEnvironmentEvidence
import com.androidperformancestudio.startup.model.StartupMetricEvidence
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupRunContext
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.startup.model.StartupType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun `requires confirmation before changing package compilation artifacts`() =
        runBlocking {
            val controller =
                StartupProfilerController(
                    backend = FakeStartupBackend(),
                    databaseFile = createTempDirectory("startup-controller-confirm").resolve("startup.db"),
                )
            controller.refreshDevices()
            controller.selectCompilationMode(CompilationMode.SPEED)

            controller.runExperiment()

            assertEquals(true, controller.state.value.compilationConfirmationRequired)
            assertEquals(false, controller.state.value.isRunning)
        }

    @Test
    fun `first imported report selects its first run without inventing a baseline`() =
        runBlocking {
            val directory = createTempDirectory("startup-controller-first-import")
            val controller = StartupProfilerController(databaseFile = directory.resolve("startup.db"))

            assertTrue(controller.importJson(exportAnalysis(directory, "first", 100)))

            val state = controller.state.value
            assertEquals("first-1", state.selectedRunId)
            assertEquals(101.0, state.analysis?.firstFrame?.medianMs)
            assertNull(state.baseline)
            assertNull(state.comparison)
            assertNull(state.errorMessage)
            assertTrue(assertNotNull(state.completedDurationSeconds) >= 0.0)

            assertFalse(controller.importJson(directory.resolve("missing.json")))
            assertNull(controller.state.value.completedDurationSeconds)
        }

    @Test
    fun `second imported report compares with previous analysis using configured practical threshold`() =
        runBlocking {
            val directory = createTempDirectory("startup-controller-second-import")
            val controller = StartupProfilerController(databaseFile = directory.resolve("startup.db"))
            assertTrue(controller.importJson(exportAnalysis(directory, "baseline", 100)))
            val baseline = controller.state.value.analysis
            controller.updatePracticalThreshold(100.0)

            assertTrue(controller.importJson(exportAnalysis(directory, "current", 130)))

            val state = controller.state.value
            assertSame(baseline, state.baseline)
            assertEquals("current-1", state.selectedRunId)
            assertEquals(StartupComparisonStatus.NO_CHANGE, state.comparison?.status)
            assertEquals(30.0, state.comparison?.medianDifferenceMs)
            assertEquals(101.0, state.comparison?.practicalThresholdMs)
        }

    @Test
    fun `incompatible imported report retains baseline and exposes comparison reason`() =
        runBlocking {
            val directory = createTempDirectory("startup-controller-incompatible-import")
            val controller = StartupProfilerController(databaseFile = directory.resolve("startup.db"))
            assertTrue(controller.importJson(exportAnalysis(directory, "baseline", 100)))
            val baseline = controller.state.value.analysis

            assertTrue(controller.importJson(exportAnalysis(directory, "warm", 130, StartupType.WARM)))

            assertSame(baseline, controller.state.value.baseline)
            val comparison = assertNotNull(controller.state.value.comparison)
            assertEquals(StartupComparisonStatus.INCOMPATIBLE, comparison.status)
            assertTrue(comparison.reasons.any { "startup mode differs" in it })
        }

    @Test
    fun `failed import preserves current analysis baseline comparison and selected run`() =
        runBlocking {
            val directory = createTempDirectory("startup-controller-failed-import")
            val controller = StartupProfilerController(databaseFile = directory.resolve("startup.db"))
            assertTrue(controller.importJson(exportAnalysis(directory, "baseline", 100)))
            assertTrue(controller.importJson(exportAnalysis(directory, "current", 130)))
            controller.selectRun("current-2")
            val before = controller.state.value
            val invalid = Files.writeString(directory.resolve("invalid.json"), "not a startup report")

            assertFalse(controller.importJson(invalid))

            val state = controller.state.value
            assertSame(before.analysis, state.analysis)
            assertSame(before.baseline, state.baseline)
            assertSame(before.comparison, state.comparison)
            assertEquals(before.selectedRunId, state.selectedRunId)
            assertNotNull(state.errorMessage)
        }

    @Test
    fun `profile artifact input trims provenance and normalizes blank input only in speed profile mode`() {
        val controller = StartupProfilerController()
        controller.updateBaselineProfileArtifact("ignored#sha256")
        assertNull(controller.state.value.config.baselineProfileArtifact)

        controller.selectCompilationMode(CompilationMode.SPEED_PROFILE)
        controller.updateBaselineProfileArtifact("  baseline-prof.txt#sha256  ")
        assertEquals("baseline-prof.txt#sha256", controller.state.value.config.baselineProfileArtifact)

        controller.updateBaselineProfileArtifact(" \t\n ")
        assertNull(controller.state.value.config.baselineProfileArtifact)
        controller.updateBaselineProfileArtifact("baseline-prof.txt#sha256")
        controller.selectCompilationMode(CompilationMode.SPEED)
        controller.updateBaselineProfileArtifact("ignored-again#sha256")
        assertNull(controller.state.value.config.baselineProfileArtifact)
    }

    @Test
    fun `stale successful and failed imports cannot overwrite a newer imported analysis`() =
        runBlocking {
            val directory = createTempDirectory("startup-controller-stale-import")
            val valid = exportAnalysis(directory, "older", 100)
            val invalid = Files.writeString(directory.resolve("invalid.json"), "not a startup report")
            val newer = exportAnalysis(directory, "newer", 130)
            for (older in listOf(valid, invalid)) {
                val dispatcher = QueuedImportDispatcher()
                val controller =
                    StartupProfilerController(
                        databaseFile = directory.resolve("startup.db"),
                    )
                val pendingOlder =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.importJson(older, dispatcher)
                    }
                val pendingNewer =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.importJson(newer, dispatcher)
                    }

                dispatcher.completeLast()
                assertTrue(pendingNewer.await())
                val imported = controller.state.value.analysis
                dispatcher.completeLast()
                assertFalse(pendingOlder.await())

                assertSame(imported, controller.state.value.analysis)
                assertEquals("newer-1", controller.state.value.selectedRunId)
                assertNull(controller.state.value.baseline)
                assertNull(controller.state.value.errorMessage)
            }
        }

    private fun exportAnalysis(
        directory: Path,
        name: String,
        ttidMs: Long,
        type: StartupType = StartupType.COLD,
    ): Path {
        val runs =
            List(3) { index ->
                StartupRun(
                    id = "$name-${index + 1}",
                    sessionId = name,
                    iteration = index + 1,
                    requestedType = type,
                    observedType = type,
                    platform = PlatformLaunchMetrics(totalTimeMs = ttidMs + index, displayedTimeMs = ttidMs + index),
                    rawEvidence = StartupRawEvidence("Status: ok"),
                    context = StartupRunContext("controller-device", "dev.example", "dev.example/.MainActivity"),
                    ttidEvidence = StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT),
                    compilationEvidence =
                        StartupCompilationEvidence(
                            requestedMode = CompilationMode.SPEED,
                            compilerFilterAfter = "speed",
                            profileStateAfter = "install",
                            verified = true,
                        ),
                    environmentEvidence =
                        StartupEnvironmentEvidence("Pixel", 35, false, 80, true, 0, Instant.EPOCH),
                )
            }
        return directory.resolve("$name.json").also {
            StartupJsonExporter().export(StartupAnalyzer().analyze(runs), it)
        }
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

    private class QueuedImportDispatcher : CoroutineDispatcher() {
        private val pending = mutableListOf<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending += block
        }

        fun completeLast() {
            pending.removeAt(pending.lastIndex).run()
        }
    }
}
