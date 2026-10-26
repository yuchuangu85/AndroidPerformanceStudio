@file:Suppress("LargeClass")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.memory.analysis.ArtSampledAllocationAnalysis
import com.androidperformancestudio.memory.analysis.ArtSampledCallSite
import com.androidperformancestudio.memory.analysis.MemoryHistogramAnalyzer
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.BitmapDumpSummary
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.LeakCanaryLeak
import com.androidperformancestudio.memory.model.LeakCanaryLiveSession
import com.androidperformancestudio.memory.model.LeakCanaryLiveStatus
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.presentation.MemoryClassifierColumn
import com.androidperformancestudio.memory.presentation.MemoryClassifierRow
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryHistogramSort
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.presentation.MemoryProfilerPresenter
import com.androidperformancestudio.memory.presentation.MemoryProfilerViewMode
import com.androidperformancestudio.memory.presentation.MemorySampledArtAnalysis
import com.androidperformancestudio.memory.presentation.MemorySampledArtOrigin
import com.androidperformancestudio.memory.presentation.MemorySampledArtTrace
import com.androidperformancestudio.memory.presentation.MemorySortDirection
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias TargetListener = AndroidTargetListener
private typealias TargetSubscription = AndroidTargetSubscription

class MemoryProfilerControllerTest {
    @Test
    fun `class-object selection keeps its represented class group and physical detail type`() =
        runTest {
            val heap =
                HeapDump(
                    classes = listOf(HeapClass(100, "java.lang.Class", 16), HeapClass(101, "com.example.Item", 8)),
                    instances = listOf(HeapInstance(1, 101, "com.example.Item", 8)),
                )
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = heap })
            controller.importHprof(Path.of("class-object.hprof"))
            controller.selectClass("com.example.Item")
            val classRows =
                controller.state.value.selectedClassInstances
                    .map { it.objectId }
            assertEquals(listOf(1L, 101L), classRows)

            controller.selectInstance(101)
            assertEquals("com.example.Item", controller.state.value.selectedClassName)
            val detailClassName =
                controller.state.value.selectedInstanceDetail
                    ?.className
            assertEquals("java.lang.Class", detailClassName)
            val selectedRows =
                controller.state.value.selectedClassInstances
                    .map { it.objectId }
            assertEquals(listOf(1L, 101L), selectedRows)

            controller.selectInstanceAsync(101)
            assertEquals("com.example.Item", controller.state.value.selectedClassName)
            val asyncDetailClassName =
                controller.state.value.selectedInstanceDetail
                    ?.className
            assertEquals("java.lang.Class", asyncDetailClassName)
        }

    @Test
    fun `sampled ART capture publishes verified rows separately from trace bytes`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)
            controller.refreshDevices()
            controller.selectProcess(42)

            controller.captureSampledArtAllocations()

            val trace = assertNotNull(controller.state.value.sampledArtTrace)
            assertEquals("perfetto/android.heapprofd/com.android.art", trace.source)
            assertEquals(42, trace.pid)
            assertEquals("com.example", trace.packageName)
            assertEquals(36, trace.sdkLevel)
            assertEquals(3L, trace.fileSizeBytes)
            val analysis = controller.state.value.sampledArtAnalysis as MemorySampledArtAnalysis.Analyzed
            assertEquals(2L, analysis.sampleRows)
            assertEquals(128L, analysis.sampledBytes)
            assertEquals(listOf("com.example.Work.allocate"), analysis.callSites.single().callStack)
            assertEquals(MemoryProfilerViewMode.SampledArtAllocations, controller.state.value.viewMode)
            assertFalse(controller.state.value.isDumping)
            assertEquals(listOf("sampledArt:serial-1:42"), backend.events.filter { it.startsWith("sampledArt:") })
        }

    @Test
    fun `sampled ART capture keeps trace receipt when analysis is empty or unavailable`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)
            controller.refreshDevices()
            controller.selectProcess(42)

            backend.sampledArtProcessing =
                SampledArtProcessingResult.Success(ArtSampledAllocationAnalysis(0, 0, 0, emptyList()))
            controller.captureSampledArtAllocations()
            val empty = controller.state.value.sampledArtAnalysis as MemorySampledArtAnalysis.Analyzed
            assertEquals(0L, empty.sampleRows)
            assertNotNull(controller.state.value.sampledArtTrace)

            backend.sampledArtProcessing = SampledArtProcessingResult.Unavailable("TRACE_PROCESSOR_NOT_FOUND")
            controller.captureSampledArtAllocations()
            assertTrue(controller.state.value.sampledArtAnalysis is MemorySampledArtAnalysis.Unavailable)
            assertNotNull(controller.state.value.sampledArtTrace)

            backend.sampledArtProcessing = SampledArtProcessingResult.Failure("TRACE_QUERY_FAILED")
            controller.captureSampledArtAllocations()
            assertTrue(controller.state.value.sampledArtAnalysis is MemorySampledArtAnalysis.Failed)
            assertNotNull(controller.state.value.sampledArtTrace)
        }

    @Test
    fun `local ART trace import preserves provenance and can export raw bytes`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)
            val source = Files.createTempFile("art-local", ".pb")
            Files.write(source, byteArrayOf(4, 5, 6))

            controller.importSampledArtAllocations(source)

            val trace = assertNotNull(controller.state.value.sampledArtTrace)
            assertEquals(MemorySampledArtOrigin.IMPORT, trace.origin)
            assertNull(trace.pid)
            assertEquals(source.toString(), trace.file)
            assertTrue(controller.state.value.sampledArtAnalysis is MemorySampledArtAnalysis.Analyzed)
            assertEquals(MemoryProfilerViewMode.SampledArtAllocations, controller.state.value.viewMode)
            val output = Files.createTempFile("art-local-export", ".pb")
            controller.exportSampledArtAllocations(output)
            assertEquals(listOf<Byte>(4, 5, 6), Files.readAllBytes(output).toList())
        }

    @Test
    fun `failed ART trace import retains the previously analyzed trace`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)
            val source = Files.createTempFile("art-good", ".pb")
            Files.write(source, byteArrayOf(1, 2, 3))
            controller.importSampledArtAllocations(source)
            val previousTrace = assertNotNull(controller.state.value.sampledArtTrace)
            val previousAnalysis = assertNotNull(controller.state.value.sampledArtAnalysis)

            backend.failSampledArtImport = true
            controller.importSampledArtAllocations(Path.of("missing.pb"))

            assertEquals(previousTrace, controller.state.value.sampledArtTrace)
            assertEquals(previousAnalysis, controller.state.value.sampledArtAnalysis)
            val errorTitle =
                controller.state.value.error
                    ?.title
            assertEquals("ART import failed", errorTitle)
            assertFalse(controller.state.value.isDumping)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `async class drilldown waits for worker and publishes only the latest selection`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val controller = MemoryProfilerController(FakeBackend(), selectionDispatcher = worker)
            controller.importHprof(Path.of("heap.hprof"))

            val obsolete = launch(start = CoroutineStart.UNDISPATCHED) { controller.selectClassAsync("Missing") }
            val latest =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    controller.selectClassAsync("com.example.Item")
                }
            assertNull(controller.state.value.selectedClassName)
            assertTrue(controller.state.value.isSelectionLoading)
            runCurrent()
            obsolete.join()
            latest.join()

            assertEquals("com.example.Item", controller.state.value.selectedClassName)
            assertEquals(3, controller.state.value.selectedClassInstances.size)
            assertFalse(controller.state.value.isSelectionLoading)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `async drilldown cannot restore a selection cleared by heap filter or snapshot switch`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val controller = MemoryProfilerController(FakeBackend(), selectionDispatcher = worker)
            controller.importHprof(Path.of("before.hprof"))
            controller.importHprof(Path.of("after.hprof"))

            val filtered =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    controller.selectClassAsync("com.example.Item")
                }
            controller.changeHeapFilter("App")
            assertFalse(controller.state.value.isSelectionLoading)
            runCurrent()
            filtered.join()
            assertNull(controller.state.value.selectedClassName)

            val oldObject =
                launch(start = CoroutineStart.UNDISPATCHED) { controller.selectInstanceAsync(1) }
            controller.selectSnapshot("before.hprof")
            runCurrent()
            oldObject.join()
            assertNull(controller.state.value.selectedInstanceDetail)

            val currentObject =
                launch(start = CoroutineStart.UNDISPATCHED) { controller.selectInstanceAsync(1) }
            runCurrent()
            currentObject.join()
            assertEquals(
                1L,
                controller.state.value.selectedInstanceDetail
                    ?.objectId,
            )
        }

    @Test
    fun `offline analysis needs no device and follows the selected snapshot rather than last import`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("a.hprof"))
            controller.importHprof(Path.of("b.hprof"))
            controller.selectSnapshot("a.hprof")

            controller.analyzeLeaks()

            assertNull(controller.state.value.selectedDeviceSerial)
            assertEquals(listOf("analyze:a.hprof"), backend.events)
            assertEquals(LeakCanaryStatus.COMPLETED, controller.state.value.leakCanaryReport.status)
            val report = controller.state.value.leakCanaryReport
            val objectId = assertNotNull(report.applicationLeaks.single().leakingObjectId)
            assertEquals(1L, objectId)
            assertEquals(100, controller.state.value.loadProgress)
            assertFalse(controller.state.value.isDumping)
            controller.selectInstance(objectId)
            val detail = assertNotNull(controller.state.value.selectedInstanceDetail)
            assertEquals(1L, detail.objectId)

            controller.selectSnapshot("b.hprof")
            assertEquals(LeakCanaryStatus.NOT_RUN, controller.state.value.leakCanaryReport.status)
            controller.selectSnapshot("a.hprof")
            assertEquals(LeakCanaryStatus.COMPLETED, controller.state.value.leakCanaryReport.status)
        }

    @Test
    fun `offline analysis ignores duplicate starts and can be cancelled without losing snapshot evidence`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backend =
                FakeBackend().apply {
                    analysisStarted = started
                    analysisRelease = release
                }
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("a.hprof"))
            val originalReport = controller.state.value.leakCanaryReport
            val analyzing = launch { controller.analyzeLeaks() }
            started.await()

            assertTrue(controller.state.value.isDumping)
            assertEquals(LeakCanaryStatus.ANALYZING, controller.state.value.leakCanaryReport.status)
            assertEquals(35, controller.state.value.loadProgress)
            controller.analyzeLeaks()
            assertEquals(listOf("analyze:a.hprof"), backend.events)
            controller.cancelActiveOperation()
            analyzing.join()
            release.complete(Unit)

            assertTrue(analyzing.isCancelled)
            assertEquals(originalReport, controller.state.value.leakCanaryReport)
            assertEquals("a.hprof", controller.state.value.activeSnapshotId)
            assertFalse(controller.state.value.isDumping)
        }

    @Test
    fun `offline analysis failure is recoverable and retry preserves the same heap`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("a.hprof"))
            backend.failAnalysis = true

            controller.analyzeLeaks()

            assertEquals(LeakCanaryStatus.FAILED, controller.state.value.leakCanaryReport.status)
            assertEquals("Unreadable source", controller.state.value.leakCanaryReport.message)
            assertNotNull(controller.state.value.error)
            assertFalse(controller.state.value.isDumping)
            backend.failAnalysis = false
            controller.importHprof(Path.of("b.hprof"))
            controller.selectSnapshot("a.hprof")
            assertEquals(LeakCanaryStatus.FAILED, controller.state.value.leakCanaryReport.status)
            assertEquals("Unreadable source", controller.state.value.leakCanaryReport.message)
            controller.analyzeLeaks()
            assertNull(controller.state.value.error)
            assertEquals(LeakCanaryStatus.COMPLETED, controller.state.value.leakCanaryReport.status)
            assertEquals(listOf("analyze:a.hprof", "analyze:a.hprof"), backend.events)
        }

    @Test
    fun `device refresh failure does not abandon an offline analysis`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backend =
                FakeBackend().apply {
                    analysisStarted = started
                    analysisRelease = release
                    failDevices = true
                }
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("a.hprof"))
            val analyzing = launch { controller.analyzeLeaks() }
            started.await()

            controller.refreshDevices()
            assertTrue(controller.state.value.isDumping)
            assertEquals(LeakCanaryStatus.ANALYZING, controller.state.value.leakCanaryReport.status)
            release.complete(Unit)
            analyzing.join()

            assertEquals(LeakCanaryStatus.COMPLETED, controller.state.value.leakCanaryReport.status)
            assertFalse(controller.state.value.isDumping)
        }

    @Test
    fun `cancel restores the report before another task can replace operation ownership`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val backend =
                FakeBackend().apply {
                    analysisStarted = started
                    analysisRelease = CompletableDeferred()
                }
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("a.hprof"))
            controller.refreshDevices()
            controller.selectProcess(42)
            val analyzing = launch { controller.analyzeLeaks() }
            started.await()

            controller.cancelActiveOperation()
            assertEquals(LeakCanaryStatus.NOT_RUN, controller.state.value.leakCanaryReport.status)
            controller.dumpBitmaps()
            analyzing.join()

            assertNotNull(controller.state.value.bitmapDumpSession)
            assertEquals(LeakCanaryStatus.NOT_RUN, controller.state.value.leakCanaryReport.status)
            assertFalse(controller.state.value.isDumping)
        }

    @Test
    fun `late cancellation cleanup cannot restore evidence after its snapshot is closed`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val backend =
                FakeBackend().apply {
                    analysisStarted = started
                    analysisRelease = CompletableDeferred()
                }
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("a.hprof"))
            val analyzing = launch { controller.analyzeLeaks() }
            started.await()

            controller.cancelActiveOperation()
            controller.closeSnapshot("a.hprof")
            analyzing.join()

            assertNull(controller.state.value.snapshotSummary)
            assertEquals(LeakCanaryReport(), controller.state.value.leakCanaryReport)
        }

    @Test
    fun `live monitor failure remains visible and does not overwrite offline evidence`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("a.hprof"))
            controller.analyzeLeaks()
            val report = controller.state.value.leakCanaryReport
            controller.refreshDevices()
            controller.selectProcess(42)

            controller.monitorLiveLeakCanary()

            assertEquals(LeakCanaryLiveStatus.FAILED, controller.state.value.leakCanaryLiveSession.status)
            assertEquals("Live connection lost", controller.state.value.leakCanaryLiveSession.message)
            assertEquals(report, controller.state.value.leakCanaryReport)
            assertFalse(controller.state.value.isDumping)
            assertTrue(backend.events.contains("closeLive"))
        }

    @Test
    fun `stopping live monitoring does not cancel a simultaneous offline analysis`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backend =
                FakeBackend().apply {
                    analysisStarted = started
                    analysisRelease = release
                }
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("a.hprof"))
            controller.refreshDevices()
            controller.selectProcess(42)
            val live = launch { controller.monitorLiveLeakCanary() }
            val analyzing = launch { controller.analyzeLeaks() }
            started.await()

            controller.stopLiveLeakCanary()
            live.join()
            assertTrue(controller.state.value.isDumping)
            assertTrue(analyzing.isActive)
            release.complete(Unit)
            analyzing.join()

            assertFalse(analyzing.isCancelled)
            assertEquals(LeakCanaryStatus.COMPLETED, controller.state.value.leakCanaryReport.status)
            assertEquals(LeakCanaryLiveStatus.STOPPED, controller.state.value.leakCanaryLiveSession.status)
        }

    @Test
    fun `comparison keeps explicit snapshot identities across selection swap and close`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend())
            controller.importHprof(Path.of("a.hprof"))
            controller.importHprof(Path.of("b.hprof"))
            controller.importHprof(Path.of("c.hprof"))
            controller.compareSnapshots("a.hprof", "c.hprof")
            controller.selectSnapshot("b.hprof")
            assertEquals(
                "a.hprof",
                controller.state.value.heapDiff
                    ?.beforeSnapshot
                    ?.id,
            )
            assertEquals(
                "c.hprof",
                controller.state.value.heapDiff
                    ?.afterSnapshot
                    ?.id,
            )
            controller.compareSnapshots("c.hprof", "a.hprof")
            assertEquals(
                "c.hprof",
                controller.state.value.heapDiff
                    ?.beforeSnapshot
                    ?.id,
            )
            controller.compareSnapshots("a.hprof", "a.hprof")
            assertEquals(
                "c.hprof",
                controller.state.value.heapDiff
                    ?.beforeSnapshot
                    ?.id,
            )
            controller.closeSnapshot("c.hprof")
            assertNull(controller.state.value.heapDiff)
            assertEquals("b.hprof", controller.state.value.activeSnapshotId)
        }

    @Test
    fun `closing the final snapshot clears Java heap evidence and object identity`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend())
            controller.importHprof(Path.of("a.hprof"))
            controller.selectInstance(1)
            controller.togglePinnedInstance(1)
            controller.closeSnapshot("a.hprof")
            val state = controller.state.value
            assertEquals(HeapSummary(), state.summary)
            assertTrue(state.classes.isEmpty())
            assertTrue(state.pinnedInstanceIds.isEmpty())
            assertTrue(state.instanceHistory.isEmpty())
            assertNull(state.selectedInstanceDetail)
            assertNull(state.snapshotSummary)
            assertNull(state.heapDiff)
        }

    @Test
    fun `same object ids in different dumps do not carry navigation or pin identity`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend())
            controller.importHprof(Path.of("a.hprof"))
            controller.selectInstance(1)
            controller.togglePinnedInstance(1)
            controller.importHprof(Path.of("b.hprof"))
            assertTrue(
                controller.state.value.instanceHistory
                    .isEmpty(),
            )
            assertTrue(
                controller.state.value.pinnedInstanceIds
                    .isEmpty(),
            )
            assertNull(controller.state.value.selectedInstanceDetail)
            assertEquals(
                "a.hprof",
                controller.state.value.heapDiff
                    ?.beforeSnapshot
                    ?.id,
            )
            assertEquals(
                "b.hprof",
                controller.state.value.heapDiff
                    ?.afterSnapshot
                    ?.id,
            )
        }

    @Test
    fun `cancellation preserves the previously published snapshot`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                MemoryProfilerController(
                    FakeBackend(importStarted = started, importRelease = release, blockingImport = "b.hprof"),
                )
            controller.importHprof(Path.of("a.hprof"))
            val snapshot = controller.state.value.snapshotSummary
            val importing = launch { controller.importHprof(Path.of("b.hprof")) }
            started.await()
            controller.cancelActiveOperation()
            importing.join()
            assertEquals(snapshot, controller.state.value.snapshotSummary)
            assertEquals("a.hprof", controller.state.value.activeSnapshotId)
        }

    @Test
    fun `the only online device is selected and its processes are loaded`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)

            controller.refreshDevices()
            controller.selectProcess(42)
            controller.dumpHeap()

            val state = controller.state.value
            assertEquals("serial-1", state.selectedDeviceSerial)
            assertEquals(42, state.selectedProcessId)
            assertEquals(3, state.summary.objectCount)
            assertEquals("conversion warning", state.warning)
            assertEquals("cleanup warning", state.cleanupWarning)
            assertFalse(state.isDumping)
            assertNull(state.error)
            assertTrue(state.classes.all { it.retainedSize == null })
            assertEquals(listOf("listDevices", "listProcesses:serial-1", "capture:serial-1:42"), backend.events)
        }

    @Test
    fun `saves and restores a complete class-list filter preset`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend())
            controller.importHprof(Path.of("filters.hprof"))
            controller.changeHeapFilter("App")
            controller.changeClassScope(com.androidperformancestudio.memory.presentation.MemoryClassScope.PROJECT)
            controller.changeLeakFilter(com.androidperformancestudio.memory.presentation.MemoryLeakFilter.ALL_ISSUE)
            controller.changeArrangeBy(com.androidperformancestudio.memory.presentation.MemoryArrangeBy.PACKAGE)
            controller.changeSearchText("Widget")
            controller.changeMatchCase(true)
            controller.changeUseRegex(true)
            controller.saveFilterPreset()

            val preset =
                controller.state.value.savedFilterPresets
                    .single()
            controller.changeSearchText("")
            controller.applyFilterPreset(preset)

            assertEquals("App", controller.state.value.heapFilter)
            assertEquals("Widget", controller.state.value.searchText)
            assertTrue(controller.state.value.matchCase)
            assertTrue(controller.state.value.useRegex)
            assertEquals("filter-1", controller.state.value.activeFilterPresetId)
        }

    @Test
    fun `import failure is structured and retry remains available`() =
        runTest {
            val backend = FakeBackend(importFails = true)
            val controller = MemoryProfilerController(backend)

            controller.importHprof(Path.of("broken.hprof"))

            assertEquals(
                "Unable to analyze HPROF",
                controller.state.value.error
                    ?.title,
            )
            assertFalse(controller.state.value.isDumping)
        }

    @Test
    fun `selected hprof immediately exposes import progress before parsing finishes`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                MemoryProfilerController(
                    FakeBackend(importStarted = started, importRelease = release),
                )

            val importJob = launch { controller.importHprof(Path.of("selected.hprof")) }
            started.await()

            assertTrue(controller.state.value.isDumping)
            assertEquals("Importing selected.hprof…", controller.state.value.operationMessage)

            release.complete(Unit)
            importJob.join()
            assertFalse(controller.state.value.isDumping)
            assertNull(controller.state.value.operationMessage)
        }

    @Test
    fun `cancelling an active hprof import publishes cancelled state`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller = MemoryProfilerController(FakeBackend(importStarted = started, importRelease = release))

            val importJob = launch { controller.importHprof(Path.of("cancelled.hprof")) }
            started.await()
            controller.cancelActiveOperation()
            importJob.join()

            assertFalse(controller.state.value.isDumping)
            assertNull(controller.state.value.operationMessage)
            assertEquals(
                com.androidperformancestudio.memory.model.HeapLoadPhase.CANCELLED,
                controller.state.value.loadPhase,
            )
        }

    @Test
    fun `unexpected import exception becomes visible error and clears busy state`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend(importThrows = true))

            controller.importHprof(Path.of("crashing.hprof"))

            val error = assertNotNull(controller.state.value.error)
            assertEquals("Unable to analyze HPROF", error.title)
            assertEquals("parser crashed", error.detail)
            assertFalse(controller.state.value.isDumping)
            assertNull(controller.state.value.operationMessage)
        }

    @Test
    fun `import out of memory becomes actionable visible error`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend(importOutOfMemory = true))

            controller.importHprof(Path.of("huge.hprof"))

            val error = assertNotNull(controller.state.value.error)
            assertEquals("Unable to analyze HPROF", error.title)
            assertTrue(error.detail.contains("increase the desktop application's maximum heap size"))
            assertFalse(controller.state.value.isDumping)
            assertNull(controller.state.value.operationMessage)
        }

    @Test
    fun `class view defers dominator projection until dominators are opened`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend())

            controller.importHprof(Path.of("heap.hprof"))

            val index =
                assertNotNull(controller.loadedHeap)
                    .investigationIndex
            assertFalse(index.dominatorsInitialized)
            val initialRows = controller.state.value.dominatorRows
            assertTrue(initialRows.isEmpty())

            controller.changeViewMode(MemoryProfilerViewMode.Dominators)

            assertTrue(index.dominatorsInitialized)
            assertEquals(index.dominators, controller.state.value.dominatorRows)

            controller.changeViewMode(MemoryProfilerViewMode.ClassList)
            val classListRows = controller.state.value.dominatorRows
            assertTrue(classListRows.isEmpty())
        }

    @Test
    fun `sorting is state only and does not fabricate phase two values`() {
        val controller = MemoryProfilerController(FakeBackend())

        controller.sort(MemoryHistogramSort.Shallow)

        assertEquals(MemoryHistogramSort.Shallow, controller.state.value.sort)
        assertTrue(
            controller.state.value.leakSuspects
                .isEmpty(),
        )
    }

    @Test
    fun `selecting a classifier set shows instances from all descendant classes`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend())
            controller.importHprof(Path.of("heap.hprof"))

            assertTrue(
                controller.state.value.snapshotSummary
                    ?.loadedAt
                    ?.isAfter(Instant.EPOCH) == true,
            )

            controller.selectClassifier(
                MemoryClassifierRow(
                    id = "group:com.example",
                    label = "com.example",
                    children =
                        listOf(
                            MemoryClassifierRow(
                                id = "class:com.example.Item",
                                label = "Item",
                                className = "com.example.Item",
                            ),
                        ),
                ),
            )

            assertEquals("group:com.example", controller.state.value.selectedClassifierId)
            assertEquals("com.example", controller.state.value.selectedClassifierLabel)
            assertEquals(3, controller.state.value.selectedClassInstances.size)
        }

    @Test
    fun `all heaps retain independent classifier trees and App filtering retains its heap parent`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = sharedClassHeap() })
            assertTrue(
                controller.state.value.perHeapClasses
                    .isEmpty(),
            )
            controller.importHprof(Path.of("shared-heaps.hprof"))

            val defaults = MemoryProfilerPresenter.present(controller.state.value)
            assertEquals(MemoryClassifierColumn.RETAINED_SIZE, defaults.classifierSortColumn)
            assertEquals(MemorySortDirection.DESCENDING, defaults.classifierSortDirection)
            assertEquals(
                defaults.classifierRows.sortedWith(compareByDescending { it.retainedSize }),
                defaults.classifierRows,
            )
            defaults.classifierRows.forEach { parent ->
                assertEquals(parent.children.sortedWith(compareByDescending { it.retainedSize }), parent.children)
            }

            controller.sortClassifier(MemoryClassifierColumn.NAME)
            val all = MemoryProfilerPresenter.present(controller.state.value)
            assertEquals(MemoryClassifierColumn.NAME, all.classifierSortColumn)
            assertEquals(MemorySortDirection.ASCENDING, all.classifierSortDirection)
            assertNull(all.heapFilter)
            assertEquals(setOf("App", "Image", "Zygote"), all.perHeapClasses.keys)
            assertEquals(listOf("heap:App", "heap:Image", "heap:Zygote"), all.classifierRows.map { it.id })
            all.classifierRows.forEach { parent ->
                assertEquals("${parent.heapName} heap", parent.label)
                assertNull(parent.className)
                assertEquals(0, parent.depth)
                assertTrue(parent.children.isNotEmpty())
                parent.children.forEach { child ->
                    assertEquals(parent.heapName, child.heapName)
                    assertEquals("${parent.id}/class:${child.className}", child.id)
                    assertEquals(1, child.depth)
                }
            }
            val app = all.classifierRows.single { it.heapName == "App" }
            val zygote = all.classifierRows.single { it.heapName == "Zygote" }
            assertEquals(
                listOf("com.example.Other", "com.example.Shared"),
                app.children.map { it.className },
            )
            assertEquals(listOf("Other (com.example)", "Shared (com.example)"), app.children.map { it.label })
            assertEquals(2L, app.children.single { it.className == "com.example.Shared" }.totalCount)
            assertEquals(4L, zygote.children.single { it.className == "com.example.Shared" }.totalCount)

            controller.changeHeapFilter("App")
            val filtered = MemoryProfilerPresenter.present(controller.state.value)
            assertEquals(listOf(app), filtered.classifierRows)
            assertEquals(all.perHeapClasses, filtered.perHeapClasses)

            controller.changeHeapFilter(null)
            assertEquals(all.classifierRows, MemoryProfilerPresenter.present(controller.state.value).classifierRows)
        }

    @Test
    fun `classifier class rows and heap parents select only their own heap in All mode`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = sharedClassHeap() })
            controller.importHprof(Path.of("shared-heaps.hprof"))
            val parents = MemoryProfilerPresenter.present(controller.state.value).classifierRows
            listOf("App", "Zygote").forEach { heapName ->
                val parent = parents.single { it.heapName == heapName }
                val child = parent.children.single { it.className == "com.example.Shared" }
                listOf(child, parent).forEach { row ->
                    controller.selectClassifier(row)
                    val state = controller.state.value
                    val expectedIds =
                        when {
                            heapName == "Zygote" -> listOf(3L, 4L, 5L, 100L)
                            row.className != null -> listOf(1L, 2L)
                            else -> listOf(1L, 2L, 6L, 101L)
                        }
                    assertNull(state.heapFilter)
                    assertEquals(row.id, state.selectedClassifierId)
                    assertEquals(row.label, state.selectedClassifierLabel)
                    assertEquals(row.className, state.selectedClassName)
                    assertEquals(expectedIds, state.selectedClassInstances.map { it.objectId })
                }
            }
        }

    @Test
    fun `async classifier class rows and heap parents select only their own heap in All mode`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = sharedClassHeap() })
            controller.importHprof(Path.of("shared-heaps.hprof"))
            val parents = MemoryProfilerPresenter.present(controller.state.value).classifierRows
            listOf("App", "Zygote").forEach { heapName ->
                val parent = parents.single { it.heapName == heapName }
                val child = parent.children.single { it.className == "com.example.Shared" }
                listOf(child, parent).forEach { row ->
                    controller.selectClassifierAsync(row)
                    val state = controller.state.value
                    val expectedIds =
                        when {
                            heapName == "Zygote" -> listOf(3L, 4L, 5L, 100L)
                            row.className != null -> listOf(1L, 2L)
                            else -> listOf(1L, 2L, 6L, 101L)
                        }
                    assertNull(state.heapFilter)
                    assertEquals(row.id, state.selectedClassifierId)
                    assertEquals(row.label, state.selectedClassifierLabel)
                    assertEquals(row.className, state.selectedClassName)
                    assertEquals(expectedIds, state.selectedClassInstances.map { it.objectId })
                    assertFalse(state.isSelectionLoading)
                }
            }
        }

    @Test
    fun `instance selection and history keep the original object heap in All mode`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = sharedClassHeap() })
            controller.importHprof(Path.of("shared-heaps.hprof"))

            controller.selectInstance(1)
            assertSharedClassInstanceSelection(controller, 1, "App", listOf(1L, 2L))
            controller.selectInstance(3)
            assertSharedClassInstanceSelection(controller, 3, "Zygote", listOf(3L, 4L, 5L, 100L))
            controller.navigateInstanceBack()
            assertSharedClassInstanceSelection(controller, 1, "App", listOf(1L, 2L))
            controller.navigateInstanceForward()
            assertSharedClassInstanceSelection(controller, 3, "Zygote", listOf(3L, 4L, 5L, 100L))

            controller.selectInstance(100)
            assertSharedClassInstanceSelection(controller, 100, "Zygote", listOf(3L, 4L, 5L, 100L))
            assertEquals("java.lang.Class", assertNotNull(controller.state.value.selectedInstanceDetail).className)
            assertEquals(listOf(1L, 3L, 100L), controller.state.value.instanceHistory)
            assertEquals(2, controller.state.value.instanceHistoryIndex)
        }

    @Test
    fun `async instance selection and history keep the original object heap in All mode`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = sharedClassHeap() })
            controller.importHprof(Path.of("shared-heaps.hprof"))

            controller.selectInstanceAsync(1)
            assertSharedClassInstanceSelection(controller, 1, "App", listOf(1L, 2L))
            controller.selectInstanceAsync(3)
            assertSharedClassInstanceSelection(controller, 3, "Zygote", listOf(3L, 4L, 5L, 100L))
            controller.navigateInstanceBackAsync()
            assertSharedClassInstanceSelection(controller, 1, "App", listOf(1L, 2L))
            controller.navigateInstanceForwardAsync()
            assertSharedClassInstanceSelection(controller, 3, "Zygote", listOf(3L, 4L, 5L, 100L))

            controller.selectInstanceAsync(100)
            assertSharedClassInstanceSelection(controller, 100, "Zygote", listOf(3L, 4L, 5L, 100L))
            assertEquals("java.lang.Class", assertNotNull(controller.state.value.selectedInstanceDetail).className)
            assertEquals(listOf(1L, 3L, 100L), controller.state.value.instanceHistory)
            assertEquals(2, controller.state.value.instanceHistoryIndex)
            assertFalse(controller.state.value.isSelectionLoading)
        }

    @Test
    fun `closing the final snapshot clears populated per-heap histograms and classifier trees`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = sharedClassHeap() })
            controller.importHprof(Path.of("shared-heaps.hprof"))
            assertEquals(setOf("App", "Image", "Zygote"), controller.state.value.perHeapClasses.keys)

            controller.closeSnapshot("shared-heaps.hprof")

            assertTrue(
                controller.state.value.perHeapClasses
                    .isEmpty(),
            )
            assertTrue(MemoryProfilerPresenter.present(controller.state.value).classifierRows.isEmpty())
        }

    @Test
    fun `second heap load exposes diff and layout inspector class highlight`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend())

            controller.importHprof(Path.of("before.hprof"))
            controller.importHprof(Path.of("after.hprof"))
            controller.highlightClass("com.example.Sample")

            assertNotNull(controller.state.value.heapDiff)
            assertTrue(
                controller.state.value.heapDiff
                    ?.entries
                    .orEmpty()
                    .all { it.countDelta == 0 },
            )
            assertEquals("com.example.Sample", controller.state.value.highlightedClassName)
        }

    @Test
    fun `importing mapping does not fabricate a heap diff`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend())

            controller.importHprof(Path.of("same.hprof"))
            controller.importMapping(Path.of("mapping.txt"))

            assertNull(controller.state.value.heapDiff)
        }

    @Test
    fun `bitmap capture exposes session and compares consecutive dumps`() =
        runTest {
            val backend = FakeBackend()
            val controller = MemoryProfilerController(backend)
            controller.refreshDevices()
            controller.selectProcess(42)

            controller.dumpBitmaps()
            controller.dumpBitmaps()

            assertNotNull(controller.state.value.bitmapDumpSession)
            assertNotNull(controller.state.value.bitmapDumpComparison)
            assertFalse(controller.state.value.isDumping)
            assertTrue(backend.events.count { it.startsWith("captureBitmaps") } == 2)
        }

    @Test
    fun `refreshing sessions publishes the result to observers`() =
        runTest {
            val session =
                MemorySessionMetadata(
                    sessionId = "session-1",
                    packageName = "com.example.app",
                    deviceSerial = "device",
                    capturedAt = Instant.EPOCH,
                    rawHprofFile = Path.of("session-1.hprof"),
                )
            val backend = FakeBackend().apply { sessions = listOf(session) }
            val controller = MemoryProfilerController(backend)

            controller.refreshSessions()

            assertEquals(listOf(session), controller.recentSessions.value)
        }

    private fun sharedClassHeap(): HeapDump =
        HeapDump(
            classes =
                listOf(
                    HeapClass(100, "com.example.Shared", 8),
                    HeapClass(101, "com.example.Other", 16),
                    HeapClass(102, "java.lang.Class", 16),
                ),
            instances =
                listOf(
                    HeapInstance(1, 100, "com.example.Shared", 8),
                    HeapInstance(2, 100, "com.example.Shared", 8),
                    HeapInstance(3, 100, "com.example.Shared", 8),
                    HeapInstance(4, 100, "com.example.Shared", 8),
                    HeapInstance(5, 100, "com.example.Shared", 8),
                    HeapInstance(6, 101, "com.example.Other", 16),
                ),
            heapByObjectId =
                mapOf(
                    1L to "App",
                    2L to "App",
                    3L to "Zygote",
                    4L to "Zygote",
                    5L to "Zygote",
                    6L to "App",
                    100L to "Zygote",
                    101L to "App",
                    102L to "Image",
                ),
        )

    private fun assertSharedClassInstanceSelection(
        controller: MemoryProfilerController,
        objectId: Long,
        heapName: String,
        expectedIds: List<Long>,
    ) {
        val state = controller.state.value
        assertNull(state.heapFilter)
        assertEquals("com.example.Shared", state.selectedClassName)
        assertEquals("heap:$heapName/class:com.example.Shared", state.selectedClassifierId)
        assertEquals(objectId, assertNotNull(state.selectedInstanceDetail).objectId)
        assertEquals(expectedIds, state.selectedClassInstances.map { it.objectId })
    }

    private class FakeBackend(
        private val blockingImport: String? = null,
        private val importFails: Boolean = false,
        private val importThrows: Boolean = false,
        private val importOutOfMemory: Boolean = false,
        private val importStarted: CompletableDeferred<Unit>? = null,
        private val importRelease: CompletableDeferred<Unit>? = null,
    ) : MemoryProfilerBackend {
        val events = mutableListOf<String>()
        var failAnalysis = false
        var sampledArtProcessing: SampledArtProcessingResult =
            SampledArtProcessingResult.Success(
                ArtSampledAllocationAnalysis(
                    sampleRows = 2,
                    sampledObjectCount = 3,
                    sampledBytes = 128,
                    callSites =
                        listOf(ArtSampledCallSite(11, 2, 3, 128, listOf("com.example.Work.allocate"), true)),
                ),
            )
        var failDevices = false
        var failSampledArtImport = false
        var sessions: List<MemorySessionMetadata> = emptyList()
        var heapOverride: HeapDump? = null
        var analysisStarted: CompletableDeferred<Unit>? = null
        var analysisRelease: CompletableDeferred<Unit>? = null
        private val noOpTargetSubscription = TargetSubscription {}

        override fun registerTargetListener(listener: TargetListener): TargetSubscription = noOpTargetSubscription

        override suspend fun listDevices(): MemoryBackendResult<List<MemoryDeviceOption>> {
            events += "listDevices"
            if (failDevices) return MemoryBackendResult.Failure("ADB failed", "Offline device")
            return MemoryBackendResult.Success(listOf(MemoryDeviceOption("serial-1", "Pixel")))
        }

        override suspend fun listSessions() = MemoryBackendResult.Success(sessions)

        override suspend fun analyzeLeaks(
            snapshot: LoadedHeap,
            onProgress: (Int) -> Unit,
        ): MemoryBackendResult<LeakCanaryReport> {
            events += "analyze:${snapshot.heapDump.id}"
            onProgress(35)
            analysisStarted?.complete(Unit)
            analysisRelease?.await()
            return if (failAnalysis) {
                MemoryBackendResult.Failure("Analysis failed", "Unreadable source")
            } else {
                MemoryBackendResult.Success(
                    LeakCanaryReport(
                        status = LeakCanaryStatus.COMPLETED,
                        candidateObjectCount = 1,
                        applicationLeaks =
                            listOf(
                                LeakCanaryLeak("signature", "Retained Item", "com.example.Item", leakingObjectId = 1),
                            ),
                    ),
                )
            }
        }

        override suspend fun openLeakCanaryAgent(
            serial: String,
            process: MemoryProcessOption,
        ): MemoryBackendResult<LeakCanaryLiveSession> =
            MemoryBackendResult.Success(LeakCanaryLiveSession(status = LeakCanaryLiveStatus.RUNNING))

        override suspend fun pollLeakCanaryAgent(): MemoryBackendResult<LeakCanaryLiveSession> =
            MemoryBackendResult.Failure("Live failed", "Live connection lost")

        override fun closeLeakCanaryAgent() {
            events += "closeLive"
        }

        override suspend fun listProcesses(serial: String): MemoryBackendResult<List<MemoryProcessOption>> {
            events += "listProcesses:$serial"
            return MemoryBackendResult.Success(listOf(MemoryProcessOption(42, "example", "com.example")))
        }

        override suspend fun capture(
            serial: String,
            process: MemoryProcessOption,
        ): MemoryBackendResult<LoadedHeap> {
            events += "capture:$serial:${process.pid}"
            return MemoryBackendResult.Success(loadedHeap())
        }

        override suspend fun captureSampledArtAllocations(
            serial: String,
            process: MemoryProcessOption,
        ): MemoryBackendResult<LoadedSampledArt> {
            events += "sampledArt:$serial:${process.pid}"
            val file = Files.createTempFile("aps-art-sampled-test", ".pb")
            Files.write(file, byteArrayOf(1, 2, 3))
            return MemoryBackendResult.Success(
                LoadedSampledArt(
                    trace =
                        MemorySampledArtTrace(
                            file = file.toString(),
                            fileSizeBytes = Files.size(file),
                            packageName = process.packageName,
                            pid = process.pid,
                            sdkLevel = 36,
                            samplingIntervalBytes = 4096,
                            durationMs = 10_000,
                            source = "perfetto/android.heapprofd/com.android.art",
                            origin = MemorySampledArtOrigin.CAPTURE,
                        ),
                    processing = sampledArtProcessing,
                ),
            )
        }

        override suspend fun importSampledArtAllocations(file: Path): MemoryBackendResult<LoadedSampledArt> =
            if (failSampledArtImport) {
                MemoryBackendResult.Failure("ART import failed", "Unreadable trace")
            } else {
                MemoryBackendResult.Success(
                    LoadedSampledArt(
                        trace =
                            MemorySampledArtTrace(
                                file = file.toString(),
                                fileSizeBytes = Files.size(file),
                                source = "local Perfetto import; ART samples queried via Trace Processor",
                                origin = MemorySampledArtOrigin.IMPORT,
                            ),
                        processing = sampledArtProcessing,
                    ),
                )
            }

        override fun exportSampledArtAllocations(
            trace: MemorySampledArtTrace,
            output: Path,
        ) {
            Files.copy(Path.of(trace.file), output, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }

        override suspend fun captureBitmaps(
            serial: String,
            process: MemoryProcessOption,
            onProgress: (Int) -> Unit,
        ): MemoryBackendResult<LoadedBitmapDump> {
            events += "captureBitmaps:$serial:${process.pid}"
            onProgress(100)
            val session =
                BitmapDumpSession(
                    id = "bitmap-${events.size}",
                    packageName = process.packageName,
                    pid = process.pid,
                    deviceSerial = serial,
                    sdkLevel = 35,
                    capturedAt = Instant.EPOCH,
                    hprofFile = Path.of("bitmap.hprof"),
                    imagesDirectory = Path.of("images"),
                    images = emptyList(),
                    summary = BitmapDumpSummary(0, 0, 0, 0, 0, 0, 0),
                )
            return MemoryBackendResult.Success(LoadedBitmapDump(session))
        }

        override suspend fun importHprof(file: Path): MemoryBackendResult<LoadedHeap> {
            if (blockingImport == null || file.fileName.toString() == blockingImport) {
                importStarted?.complete(Unit)
                importRelease?.await()
            }
            return if (importOutOfMemory) {
                throw OutOfMemoryError("test")
            } else if (importThrows) {
                error("parser crashed")
            } else if (importFails) {
                MemoryBackendResult.Failure("Unable to analyze HPROF", "truncated")
            } else {
                val loaded = loadedHeap()
                MemoryBackendResult.Success(
                    loaded.copy(heapDump = loaded.heapDump.copy(id = file.fileName.toString(), rawHprofFile = file)),
                )
            }
        }

        @Suppress("MaxLineLength")
        override suspend fun importMapping(file: Path): MemoryBackendResult<LoadedHeap?> = MemoryBackendResult.Success(loadedHeap())

        override fun exportRaw(
            heapDump: HeapDump,
            output: Path,
        ) = Unit

        override fun exportConverted(
            heapDump: HeapDump,
            output: Path,
        ) = Unit

        override fun exportHistogram(
            histogram: HeapHistogram,
            output: Path,
        ) = Unit

        private fun loadedHeap(): LoadedHeap =
            LoadedHeap(
                heapDump =
                    heapOverride ?: HeapDump(
                        instances =
                            listOf(
                                HeapInstance(1, 10, "com.example.Item", 8),
                                HeapInstance(2, 10, "com.example.Item", 8),
                                HeapInstance(3, 10, "com.example.Item", 8),
                            ),
                    ),
                histogram =
                    heapOverride?.let { MemoryHistogramAnalyzer().histogram(it) } ?: HeapHistogram(
                        summary = HeapSummary(objectCount = 3, classCount = 1, shallowSize = 24),
                        classes = listOf(ClassStats("com.example.Item", 3, 24, retainedSize = null)),
                    ),
                availableHeaps = heapOverride?.let { MemoryHistogramAnalyzer().heapNamesOf(it) }.orEmpty(),
                perHeapClasses =
                    heapOverride
                        ?.let { heap ->
                            val analyzer = MemoryHistogramAnalyzer()
                            analyzer.heapNamesOf(heap).associateWith { heapName ->
                                analyzer.histogram(heap, heapName = heapName).classes
                            }
                        }.orEmpty(),
                warning = "conversion warning",
                cleanupWarning = "cleanup warning",
            )
    }
}
