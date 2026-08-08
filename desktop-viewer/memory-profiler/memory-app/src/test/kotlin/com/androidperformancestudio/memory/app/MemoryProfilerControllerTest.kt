package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.BitmapDumpSummary
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.presentation.MemoryClassifierRow
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryHistogramSort
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryProfilerControllerTest {
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

    private class FakeBackend(
        private val importFails: Boolean = false,
        private val importThrows: Boolean = false,
        private val importOutOfMemory: Boolean = false,
        private val importStarted: CompletableDeferred<Unit>? = null,
        private val importRelease: CompletableDeferred<Unit>? = null,
    ) : MemoryProfilerBackend {
        val events = mutableListOf<String>()

        override suspend fun listDevices(): MemoryBackendResult<List<MemoryDeviceOption>> {
            events += "listDevices"
            return MemoryBackendResult.Success(listOf(MemoryDeviceOption("serial-1", "Pixel")))
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
            importStarted?.complete(Unit)
            importRelease?.await()
            return if (importOutOfMemory) {
                throw OutOfMemoryError("test")
            } else if (importThrows) {
                error("parser crashed")
            } else if (importFails) {
                MemoryBackendResult.Failure("Unable to analyze HPROF", "truncated")
            } else {
                MemoryBackendResult.Success(loadedHeap())
            }
        }

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
                    HeapDump(
                        instances =
                            listOf(
                                HeapInstance(1, 10, "com.example.Item", 8),
                                HeapInstance(2, 10, "com.example.Item", 8),
                                HeapInstance(3, 10, "com.example.Item", 8),
                            ),
                    ),
                histogram =
                    HeapHistogram(
                        summary = HeapSummary(objectCount = 3, classCount = 1, shallowSize = 24),
                        classes = listOf(ClassStats("com.example.Item", 3, 24, retainedSize = null)),
                    ),
                warning = "conversion warning",
                cleanupWarning = "cleanup warning",
            )
    }
}
