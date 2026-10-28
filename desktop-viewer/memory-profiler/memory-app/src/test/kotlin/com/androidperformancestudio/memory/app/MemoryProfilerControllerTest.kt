@file:Suppress("LargeClass")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.memory.analysis.ArtSampledAllocationAnalysis
import com.androidperformancestudio.memory.analysis.ArtSampledCallSite
import com.androidperformancestudio.memory.analysis.MemoryHistogramAnalyzer
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.importing
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.BitmapDumpSummary
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapField
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArrayValueSource
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.HeapRootKind
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.LeakCanaryLeak
import com.androidperformancestudio.memory.model.LeakCanaryLiveSession
import com.androidperformancestudio.memory.model.LeakCanaryLiveStatus
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.model.PrimitiveType
import com.androidperformancestudio.memory.presentation.MemoryClassifierColumn
import com.androidperformancestudio.memory.presentation.MemoryClassifierRow
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryHistogramSort
import com.androidperformancestudio.memory.presentation.MemoryInspectorObjectKey
import com.androidperformancestudio.memory.presentation.MemoryInspectorObjectState
import com.androidperformancestudio.memory.presentation.MemoryInstanceField
import com.androidperformancestudio.memory.presentation.MemoryInstanceRow
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.presentation.MemoryProfilerPresenter
import com.androidperformancestudio.memory.presentation.MemoryProfilerViewMode
import com.androidperformancestudio.memory.presentation.MemorySampledArtAnalysis
import com.androidperformancestudio.memory.presentation.MemorySampledArtOrigin
import com.androidperformancestudio.memory.presentation.MemorySampledArtTrace
import com.androidperformancestudio.memory.presentation.MemorySortDirection
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

private typealias TargetListener = AndroidTargetListener
private typealias TargetSubscription = AndroidTargetSubscription

class MemoryProfilerControllerTest {
    @Test
    fun `language changes preserve loaded snapshots query selection and cached inspector evidence`() =
        runTest {
            val backend = FakeBackend().apply { heapOverride = inspectorHeap() }
            val controller = MemoryProfilerController(backend)
            controller.refreshDevices()
            controller.importHprof(Path.of("before.hprof"))
            controller.importHprof(Path.of("after.hprof"))
            controller.selectInstance(1)
            controller.loadInspectorObject(2)
            controller.togglePinnedInstance(1)
            controller.changeSearchText("Target")
            val state = controller.state.value
            val heap = controller.loadedHeap
            val query = controller.instanceQuery
            val events = backend.events.toList()

            listOf(UiLanguage.SIMPLIFIED_CHINESE, UiLanguage.SIMPLIFIED_CHINESE, UiLanguage.ENGLISH).forEach {
                controller.changeLanguage(it)
                assertSame(state, controller.state.value)
                assertSame(heap, controller.loadedHeap)
                assertSame(query, controller.instanceQuery)
                assertEquals(events, backend.events)
            }
            assertEquals(listOf(UiLanguage.SIMPLIFIED_CHINESE, UiLanguage.ENGLISH), backend.languageChanges)
            assertEquals(2, controller.state.value.snapshotSummaries.size)
            controller.selectSnapshot("before.hprof")
            assertEquals("before.hprof", controller.state.value.activeSnapshotId)
            controller.close()
        }

    @Test
    fun `language changes preserve pending imports and localize new operation messages`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                MemoryProfilerController(
                    FakeBackend(blockingImport = "pending.hprof", importStarted = started, importRelease = release),
                )
            controller.importHprof(Path.of("before.hprof"))
            controller.changeLanguage(UiLanguage.SIMPLIFIED_CHINESE)
            val importing =
                launch(start = CoroutineStart.UNDISPATCHED) { controller.importHprof(Path.of("pending.hprof")) }
            started.await()
            val state = controller.state.value
            assertEquals(
                localizedStringResource(Res.string.importing, UiLanguage.SIMPLIFIED_CHINESE, Path.of("pending.hprof")),
                state.operationMessage,
            )

            controller.changeLanguage(UiLanguage.ENGLISH)
            assertSame(state, controller.state.value)
            assertTrue(importing.isActive)
            assertTrue(state.isDumping)
            release.complete(Unit)
            importing.join()
            assertFalse(importing.isCancelled)
            assertEquals("pending.hprof", controller.state.value.activeSnapshotId)
            assertEquals(2, controller.state.value.snapshotSummaries.size)
            controller.close()
        }

    @Test
    fun `appended presentation field metadata preserves legacy positional constructors`() {
        val field = MemoryInstanceField("value", "7", null, null, 2)

        assertEquals(HeapValueKind.UNKNOWN, field.valueKind)
        assertNull(field.shallowSize)
        assertNull(field.retainedSize)
        assertNull(field.nativeSize)
        assertNull(field.retainedNativeSize)
        assertNull(field.representedClassName)
        assertNull(field.stringPreview)
        assertNull(field.childCount)
    }

    @Test
    @Suppress("LongMethod")
    fun `query types field metrics previews and represented classes propagate to selected and cached details`() =
        runTest {
            val stringValues = "ok".map { it.code.toLong() }.toLongArray()
            val source =
                object : HeapPrimitiveArrayValueSource {
                    override fun read(
                        start: Int,
                        count: Int,
                    ): LongArray = stringValues.copyOfRange(start, start + count)
                }
            val heap =
                HeapDump(
                    classes =
                        listOf(
                            HeapClass(10, "java.lang.Class", 32),
                            HeapClass(
                                100,
                                "sample.Root",
                                instanceFields =
                                    listOf(
                                        HeapField("child", PrimitiveType.OBJECT),
                                        HeapField("count", PrimitiveType.INT),
                                        HeapField("type", PrimitiveType.OBJECT),
                                        HeapField("title", PrimitiveType.OBJECT),
                                    ),
                            ),
                            HeapClass(101, "sample.Child"),
                        ),
                    instances =
                        listOf(
                            HeapInstance(
                                1,
                                100,
                                "sample.Root",
                                16,
                                references =
                                    listOf(
                                        ObjectReference("child", 2),
                                        ObjectReference("type", 101),
                                        ObjectReference("title", 3),
                                    ),
                                primitiveFields = mapOf("count" to 7L),
                                nativeSizeBytes = 128,
                            ),
                            HeapInstance(2, 101, "sample.Child", 8, nativeSizeBytes = 64),
                            HeapInstance(
                                3,
                                102,
                                "java.lang.String",
                                8,
                                references = listOf(ObjectReference("value", 4)),
                            ),
                        ),
                    primitiveArrays = listOf(HeapPrimitiveArray(4, PrimitiveType.CHAR, 2, valueSource = source)),
                    gcRoots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
                    objectRetainedSizes = mapOf(1L to 160L, 2L to 80L),
                )
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = heap })
            controller.importHprof(Path.of("typed.hprof"))
            controller.selectInstance(1)

            val detail = assertNotNull(controller.state.value.selectedInstanceDetail)
            assertEquals(HeapValueKind.OBJECT, detail.valueKind)
            val fields = detail.fields.associateBy { it.name }
            val child = fields.getValue("child")
            assertEquals(HeapValueKind.OBJECT, child.valueKind)
            assertEquals(1, child.depth)
            assertEquals(8L, child.shallowSize)
            assertEquals(80L, child.retainedSize)
            assertEquals(64L, child.nativeSize)
            assertEquals(0, child.childCount)
            assertNull(child.retainedNativeSize)
            val primitive = fields.getValue("count")
            assertEquals(HeapValueKind.PRIMITIVE, primitive.valueKind)
            assertEquals(0, primitive.depth)
            assertEquals(4L, primitive.shallowSize)
            assertEquals(4L, primitive.retainedSize)
            assertEquals(0L, primitive.nativeSize)
            assertNull(primitive.retainedNativeSize)
            assertEquals(HeapValueKind.CLASS, fields.getValue("type").valueKind)
            assertEquals("java.lang.Class", fields.getValue("type").targetClassName)
            assertEquals("sample.Child", fields.getValue("type").representedClassName)
            assertEquals(HeapValueKind.STRING, fields.getValue("title").valueKind)
            assertEquals("\"ok\"", fields.getValue("title").stringPreview)

            controller.loadInspectorObject(2)
            val cachedState = controller.state.value.inspectorObjects[MemoryInspectorObjectKey(2)]
            val cached = cachedState as MemoryInspectorObjectState.Loaded
            val reference = cached.detail.references.single()
            assertEquals(HeapValueKind.OBJECT, reference.valueKind)
            assertEquals(0, reference.depth)
            assertEquals(16L, reference.shallowSize)
            assertEquals(160L, reference.retainedSize)
            assertEquals(128L, reference.nativeSize)
            assertNull(reference.retainedNativeSize)

            controller.selectInstance(101)
            val classDetail = assertNotNull(controller.state.value.selectedInstanceDetail)
            assertEquals(HeapValueKind.CLASS, classDetail.valueKind)
            assertEquals("sample.Child", classDetail.representedClassName)
            assertNull(classDetail.retainedNativeSize)
            controller.selectInstance(3)
            val stringDetail = assertNotNull(controller.state.value.selectedInstanceDetail)
            assertEquals(HeapValueKind.STRING, stringDetail.valueKind)
            assertEquals("\"ok\"", stringDetail.stringPreview)
            controller.close()
        }

    @Test
    fun `clearing instance selection preserves class list pins history filters and snapshot`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = inspectorHeap() })
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            controller.selectInstance(2)
            controller.navigateInstanceBack()
            controller.togglePinnedInstance(2)
            controller.loadInspectorObject(2)
            val before = controller.state.value
            assertTrue(before.inspectorObjects.isNotEmpty())

            controller.clearInstanceSelection()

            val cleared =
                before.copy(selectedInstanceDetail = null, inspectorObjects = emptyMap(), isSelectionLoading = false)
            assertEquals(cleared, controller.state.value)
            controller.clearInstanceSelection()
            controller.loadInspectorObject(2)
            assertEquals(cleared, controller.state.value)
            controller.close()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `close invalidates pending instance selection and inspector completion without changing class scope`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val controller =
                MemoryProfilerController(
                    FakeBackend().apply { heapOverride = inspectorHeap() },
                    selectionDispatcher = worker,
                )
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            controller.togglePinnedInstance(1)
            val before = controller.state.value
            val inspector = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            val selecting = launch(start = CoroutineStart.UNDISPATCHED) { controller.selectInstanceAsync(2) }
            assertTrue(controller.state.value.isSelectionLoading)

            controller.clearInstanceSelection()
            val cleared = before.copy(selectedInstanceDetail = null)
            assertEquals(cleared, controller.state.value)
            runCurrent()
            inspector.join()
            selecting.join()

            assertEquals(cleared, controller.state.value)
            controller.close()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `close and reopen of the same root rejects an earlier inspector request`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val controller =
                MemoryProfilerController(
                    FakeBackend().apply { heapOverride = inspectorHeap() },
                    selectionDispatcher = worker,
                )
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            val stale = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }

            controller.clearInstanceSelection()
            controller.selectInstance(1)
            val reopened = controller.state.value
            runCurrent()
            stale.join()

            assertEquals(reopened, controller.state.value)
            assertTrue(
                controller.state.value.inspectorObjects
                    .isEmpty(),
            )
            controller.close()
        }

    @Test
    fun `inspector objects and array pages never change selection tables history pins or loading`() =
        runTest {
            for (async in listOf(false, true)) {
                val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = inspectorHeap() })
                controller.importHprof(Path.of("inspector.hprof"))
                controller.selectInstance(1)
                controller.selectInstance(2)
                controller.navigateInstanceBack()
                controller.togglePinnedInstance(2)
                val selected = controller.state.value

                for ((objectId, start) in listOf(2L to 0, 3L to 0, 3L to 200, 999L to 0, 3L to -1)) {
                    if (async) {
                        controller.loadInspectorObjectAsync(objectId, start)
                    } else {
                        controller.loadInspectorObject(objectId, start)
                    }
                    assertEquals(selected, controller.state.value.copy(inspectorObjects = emptyMap()))
                }

                val objects = controller.state.value.inspectorObjects
                val child = objects[MemoryInspectorObjectKey(2)] as MemoryInspectorObjectState.Loaded
                val childFields = child.detail.fields
                val rootReference = child.detail.references.single { it.name == "child" }
                assertEquals("sample.Child", child.detail.className)
                assertEquals("7", childFields.first().displayValue)
                assertEquals(1, child.detail.depth)
                assertEquals(0, rootReference.depth)
                val firstPage = objects[MemoryInspectorObjectKey(3)] as MemoryInspectorObjectState.Loaded
                val nextPage = objects[MemoryInspectorObjectKey(3, 200)] as MemoryInspectorObjectState.Loaded
                val firstFields = firstPage.detail.fields
                val nextFields = nextPage.detail.fields
                assertEquals(450, firstPage.detail.elementCount)
                assertEquals(200, firstPage.detail.fields.size)
                assertEquals("[0]", firstFields.first().name)
                assertEquals("[200]", nextFields.first().name)
                assertEquals(200, nextPage.detail.arrayStart)
                assertEquals(2L, nextFields.first().targetObjectId)
                assertEquals(MemoryInspectorObjectState.Unavailable, objects[MemoryInspectorObjectKey(999)])
                assertEquals(4, objects.size)
                controller.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `inspector loading and loaded keys suppress duplicate detail queries`() =
        runTest {
            val fields = InspectorFields()
            val worker = StandardTestDispatcher(testScheduler)
            val controller =
                MemoryProfilerController(
                    FakeBackend().apply { heapOverride = inspectorHeap(fields) },
                    selectionDispatcher = worker,
                )
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            val selected = controller.state.value
            val key = MemoryInspectorObjectKey(2)

            val loading = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            assertEquals(MemoryInspectorObjectState.Loading, controller.state.value.inspectorObjects[key])
            assertEquals(selected, controller.state.value.copy(inspectorObjects = emptyMap()))
            controller.loadInspectorObject(2)
            controller.loadInspectorObjectAsync(2)
            assertEquals(0, fields.reads)

            runCurrent()
            loading.join()
            assertEquals(1, fields.reads)
            val loaded = controller.state.value.inspectorObjects[key]
            assertTrue(loaded is MemoryInspectorObjectState.Loaded)
            controller.loadInspectorObject(2)
            controller.loadInspectorObjectAsync(2)
            assertEquals(1, fields.reads)
            assertEquals(loaded, controller.state.value.inspectorObjects[key])
            assertEquals(selected, controller.state.value.copy(inspectorObjects = emptyMap()))
            controller.close()
        }

    @Test
    fun `inspector failures are unavailable without poisoning selection and can retry`() =
        runTest {
            for (async in listOf(false, true)) {
                val fields = InspectorFields()
                val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = inspectorHeap(fields) })
                controller.importHprof(Path.of("inspector.hprof"))
                controller.selectInstance(1)
                val selected = controller.state.value
                fields.failure = IllegalStateException("Unavailable object detail")

                if (async) controller.loadInspectorObjectAsync(2) else controller.loadInspectorObject(2)

                assertEquals(
                    MemoryInspectorObjectState.Unavailable,
                    controller.state.value.inspectorObjects[MemoryInspectorObjectKey(2)],
                )
                assertEquals(selected, controller.state.value.copy(inspectorObjects = emptyMap()))
                fields.failure = null
                if (async) controller.loadInspectorObjectAsync(2) else controller.loadInspectorObject(2)
                assertTrue(
                    controller.state.value.inspectorObjects[MemoryInspectorObjectKey(2)] is
                        MemoryInspectorObjectState.Loaded,
                )
                assertEquals(2, fields.reads)
                assertEquals(selected, controller.state.value.copy(inspectorObjects = emptyMap()))
                controller.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `inspector cancellation clears loading both on worker and while waiting for selection mutex`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val controller =
                MemoryProfilerController(
                    FakeBackend().apply { heapOverride = inspectorHeap() },
                    selectionDispatcher = worker,
                )
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            val selected = controller.state.value

            val loading = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(3) }
            assertEquals(2, controller.state.value.inspectorObjects.size)
            loading.cancel()
            waiting.cancel()
            runCurrent()
            loading.join()
            waiting.join()
            assertEquals(selected, controller.state.value)

            val retried = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            runCurrent()
            retried.join()
            assertTrue(
                controller.state.value.inspectorObjects[MemoryInspectorObjectKey(2)] is
                    MemoryInspectorObjectState.Loaded,
            )
            assertEquals(selected, controller.state.value.copy(inspectorObjects = emptyMap()))
            controller.close()
        }

    @Test
    fun `query cancellation never leaves inspector loading or changes selected object`() =
        runTest {
            val fields = InspectorFields()
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = inspectorHeap(fields) })
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            val selected = controller.state.value
            fields.failure = CancellationException("Cancelled detail")

            for (async in listOf(false, true)) {
                val cancelled =
                    launch {
                        if (async) controller.loadInspectorObjectAsync(2) else controller.loadInspectorObject(2)
                    }
                cancelled.join()
                assertTrue(cancelled.isCancelled)
                assertEquals(selected, controller.state.value)
            }
            controller.close()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `inspector loading never invalidates a pending main selection or changes its loading flag`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val controller =
                MemoryProfilerController(
                    FakeBackend().apply { heapOverride = inspectorHeap() },
                    selectionDispatcher = worker,
                )
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            val selection = launch(start = CoroutineStart.UNDISPATCHED) { controller.selectClassAsync("sample.Child") }
            val selecting = controller.state.value
            assertTrue(selecting.isSelectionLoading)
            val inspector = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            assertEquals(selecting, controller.state.value.copy(inspectorObjects = emptyMap()))

            runCurrent()
            selection.join()
            inspector.join()

            assertEquals("sample.Child", controller.state.value.selectedClassName)
            val selectedRows = controller.state.value.selectedClassInstances
            assertEquals(listOf(2L), selectedRows.map { it.objectId })
            assertNull(controller.state.value.selectedInstanceDetail)
            assertFalse(controller.state.value.isSelectionLoading)
            assertTrue(
                controller.state.value.inspectorObjects
                    .isEmpty(),
            )
            controller.close()
        }

    @Test
    fun `inspector cache resets with every root class classifier heap and preset reset`() =
        runTest {
            val resets =
                listOf("root", "async root", "class", "async class", "classifier", "async classifier", "heap", "preset")
            for (reset in resets) {
                val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = inspectorHeap() })
                controller.importHprof(Path.of("inspector.hprof"))
                controller.selectInstance(1)
                controller.loadInspectorObject(2)
                assertTrue(
                    controller.state.value.inspectorObjects
                        .isNotEmpty(),
                )
                val row = MemoryClassifierRow("child", "Child", className = "sample.Child")

                when (reset) {
                    "root" -> controller.selectInstance(2)
                    "async root" -> controller.selectInstanceAsync(2)
                    "class" -> controller.selectClass("sample.Root")
                    "async class" -> controller.selectClassAsync("sample.Root")
                    "classifier" -> controller.selectClassifier(row)
                    "async classifier" -> controller.selectClassifierAsync(row)
                    "heap" -> controller.changeHeapFilter("App")
                    "preset" -> {
                        controller.saveFilterPreset()
                        val presets = controller.state.value.savedFilterPresets
                        controller.applyFilterPreset(presets.single())
                    }
                }
                assertTrue(
                    controller.state.value.inspectorObjects
                        .isEmpty(),
                    reset,
                )
                controller.close()
            }
        }

    @Test
    fun `selected root array pagination preserves inspector cache`() =
        runTest {
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = inspectorHeap() })
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(3)
            controller.loadInspectorObject(2)
            val cache = controller.state.value.inspectorObjects
            val history = controller.state.value.instanceHistory

            controller.loadArrayRange(3, 200)
            assertEquals(cache, controller.state.value.inspectorObjects)
            val firstDetail = controller.state.value.selectedInstanceDetail
            assertEquals(200, firstDetail?.arrayStart)
            controller.loadArrayRangeAsync(3, 400)
            assertEquals(cache, controller.state.value.inspectorObjects)
            val nextDetail = controller.state.value.selectedInstanceDetail
            assertEquals(400, nextDetail?.arrayStart)
            assertEquals(history, controller.state.value.instanceHistory)
            controller.close()
        }

    @Test
    fun `snapshot replacement and final close clear cached objects despite reused ids`() =
        runTest {
            val backend = FakeBackend().apply { heapOverride = inspectorHeap() }
            val controller = MemoryProfilerController(backend)
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            controller.loadInspectorObject(2)
            val oldQuery = controller.instanceQuery

            backend.heapOverride = inspectorHeap(mapOf("value" to 99L))
            controller.importHprof(Path.of("inspector.hprof"))
            assertTrue(
                controller.state.value.inspectorObjects
                    .isEmpty(),
            )
            assertTrue(oldQuery !== controller.instanceQuery)
            controller.selectInstance(1)
            controller.loadInspectorObjectAsync(2)
            val loaded = controller.state.value.inspectorObjects[MemoryInspectorObjectKey(2)]
            assertTrue(loaded is MemoryInspectorObjectState.Loaded)
            val loadedFields = loaded.detail.fields
            assertEquals("99", loadedFields.first().displayValue)

            controller.closeSnapshot("inspector.hprof")
            assertTrue(
                controller.state.value.inspectorObjects
                    .isEmpty(),
            )
            controller.loadInspectorObject(2)
            controller.loadInspectorObjectAsync(2)
            assertTrue(
                controller.state.value.inspectorObjects
                    .isEmpty(),
            )
            controller.close()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stale inspector completion is discarded after switching away and back to the same root id`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val controller =
                MemoryProfilerController(
                    FakeBackend().apply { heapOverride = inspectorHeap() },
                    selectionDispatcher = worker,
                )
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            val stale = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            controller.selectInstance(2)
            controller.selectInstance(1)
            val selected = controller.state.value
            val fresh = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(3) }

            runCurrent()
            stale.join()
            fresh.join()

            assertEquals(setOf(MemoryInspectorObjectKey(3)), controller.state.value.inspectorObjects.keys)
            val cache = controller.state.value.inspectorObjects
            assertTrue(cache.values.single() is MemoryInspectorObjectState.Loaded)
            assertEquals(selected, controller.state.value.copy(inspectorObjects = emptyMap()))
            controller.close()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stale inspector completion is discarded after a snapshot round trip reuses query and root ids`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val controller =
                MemoryProfilerController(
                    FakeBackend().apply { heapOverride = inspectorHeap() },
                    selectionDispatcher = worker,
                )
            controller.importHprof(Path.of("before.hprof"))
            controller.importHprof(Path.of("after.hprof"))
            controller.selectSnapshot("before.hprof")
            controller.selectInstance(1)
            val originalQuery = controller.instanceQuery
            val stale = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            controller.selectSnapshot("after.hprof")
            controller.selectInstance(1)
            controller.selectSnapshot("before.hprof")
            controller.selectInstance(1)
            assertTrue(originalQuery === controller.instanceQuery)
            val selected = controller.state.value

            runCurrent()
            stale.join()

            assertEquals(selected, controller.state.value)
            controller.close()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stale inspector cleanup cannot replace a new load when snapshot and object ids are reused`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val backend = FakeBackend().apply { heapOverride = inspectorHeap() }
            val controller = MemoryProfilerController(backend, selectionDispatcher = worker)
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            val stale = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            backend.heapOverride = inspectorHeap(mapOf("value" to 99L))
            controller.importHprof(Path.of("inspector.hprof"))
            controller.selectInstance(1)
            val selected = controller.state.value
            val fresh = launch(start = CoroutineStart.UNDISPATCHED) { controller.loadInspectorObjectAsync(2) }
            stale.cancel()

            runCurrent()
            stale.join()
            fresh.join()

            val loaded = controller.state.value.inspectorObjects[MemoryInspectorObjectKey(2)]
            assertTrue(loaded is MemoryInspectorObjectState.Loaded)
            val loadedFields = loaded.detail.fields
            assertEquals("99", loadedFields.first().displayValue)
            assertEquals(selected, controller.state.value.copy(inspectorObjects = emptyMap()))
            controller.close()
        }

    @Test
    fun `instance row keeps legacy positional constructor compatible`() {
        val row = MemoryInstanceRow(1, 2, 10, null, null, false, 64, false, true)

        assertNull(row.className)
        assertEquals(64L, row.nativeSize)
        assertFalse(row.shallowSizeKnown)
        assertTrue(row.isLeakWarning)
    }

    @Test
    fun `instance selection publishes referring object depths for nearest root filtering`() =
        runTest {
            val heap =
                HeapDump(
                    instances =
                        listOf(
                            HeapInstance(1, 100, "sample.Root", 8, references = listOf(ObjectReference("child", 2))),
                            HeapInstance(2, 101, "sample.Item", 8, references = listOf(ObjectReference("self", 2))),
                            HeapInstance(3, 102, "sample.Orphan", 8, references = listOf(ObjectReference("owner", 2))),
                        ),
                    gcRoots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
                )
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = heap })
            controller.importHprof(Path.of("nearest-root.hprof"))

            controller.selectInstanceAsync(2)

            val detail = assertNotNull(controller.state.value.selectedInstanceDetail)
            assertEquals(1, detail.depth)
            assertEquals(
                mapOf("child" to 0, "self" to 1, "owner" to null),
                detail.references.associate { it.name to it.depth },
            )
            controller.close()
        }

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
            val expectedClassNames = listOf("com.example.Item", "java.lang.Class")
            assertEquals(
                expectedClassNames,
                controller.state.value.selectedClassInstances
                    .map { it.className },
            )

            controller.selectClassAsync("com.example.Item")
            assertEquals(
                classRows,
                controller.state.value.selectedClassInstances
                    .map { it.objectId },
            )
            assertEquals(
                expectedClassNames,
                controller.state.value.selectedClassInstances
                    .map { it.className },
            )

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
            assertEquals(
                expectedClassNames,
                controller.state.value.selectedClassInstances
                    .map { it.className },
            )

            controller.selectInstanceAsync(101)
            assertEquals("com.example.Item", controller.state.value.selectedClassName)
            val asyncDetailClassName =
                controller.state.value.selectedInstanceDetail
                    ?.className
            assertEquals("java.lang.Class", asyncDetailClassName)
            assertEquals(
                expectedClassNames,
                controller.state.value.selectedClassInstances
                    .map { it.className },
            )
            controller.close()
        }

    @Test
    fun `class selection propagates object and primitive array runtime types`() =
        runTest {
            val heap =
                HeapDump(
                    objectArrays =
                        listOf(
                            HeapObjectArray(9, 100, "com.example.Item[]", 0),
                            HeapObjectArray(7, 100, "com.example.Item[]", 0),
                        ),
                    primitiveArrays =
                        listOf(
                            HeapPrimitiveArray(5, PrimitiveType.INT, 2, 8),
                            HeapPrimitiveArray(3, PrimitiveType.INT, 1, 4),
                        ),
                )
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = heap })
            controller.importHprof(Path.of("array-types.hprof"))

            mapOf("com.example.Item[]" to listOf(7L, 9L), "int[]" to listOf(3L, 5L)).forEach { (className, objectIds) ->
                controller.selectClass(className)
                val rows = controller.state.value.selectedClassInstances
                assertEquals(objectIds, rows.map { it.objectId })
                assertEquals(listOf(1, 2), rows.map { it.index })
                assertEquals(listOf(className, className), rows.map { it.className })

                controller.selectClassAsync(className)
                assertEquals(rows, controller.state.value.selectedClassInstances)
            }
            controller.close()
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
            val controller = MemoryProfilerController(FakeBackend().apply { heapOverride = sharedClassHeap() })
            controller.importHprof(Path.of("heap.hprof"))

            assertTrue(
                controller.state.value.snapshotSummary
                    ?.loadedAt
                    ?.isAfter(Instant.EPOCH) == true,
            )

            val classifier =
                MemoryClassifierRow(
                    id = "group:com.example",
                    label = "com.example",
                    children =
                        listOf(
                            MemoryClassifierRow(
                                id = "class:com.example.Other",
                                label = "Other",
                                className = "com.example.Other",
                            ),
                            MemoryClassifierRow(
                                id = "class:com.example.Shared",
                                label = "Shared",
                                className = "com.example.Shared",
                            ),
                        ),
                )
            controller.selectClassifier(classifier)

            assertEquals("group:com.example", controller.state.value.selectedClassifierId)
            assertEquals("com.example", controller.state.value.selectedClassifierLabel)
            assertNull(controller.state.value.selectedClassName)
            val rows = controller.state.value.selectedClassInstances
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 100L, 101L), rows.map { it.objectId })
            assertEquals(listOf(1, 2, 3, 4, 5, 1, 6, 2), rows.map { it.index })
            assertEquals(
                List(5) { "com.example.Shared" } + listOf("com.example.Other", "java.lang.Class", "java.lang.Class"),
                rows.map { it.className },
            )
            assertEquals(
                List(6) { null } + listOf("com.example.Shared", "com.example.Other"),
                rows.map { it.representedClassName },
            )

            controller.selectClassifierAsync(classifier)
            assertEquals(rows, controller.state.value.selectedClassInstances)
            controller.close()
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
                    assertEquals(
                        expectedIds.map { objectId ->
                            when (objectId) {
                                6L -> "com.example.Other"
                                100L, 101L -> "java.lang.Class"
                                else -> "com.example.Shared"
                            }
                        },
                        state.selectedClassInstances.map { it.className },
                    )
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
                    assertEquals(
                        expectedIds.map { objectId ->
                            when (objectId) {
                                6L -> "com.example.Other"
                                100L, 101L -> "java.lang.Class"
                                else -> "com.example.Shared"
                            }
                        },
                        state.selectedClassInstances.map { it.className },
                    )
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

    private fun inspectorHeap(childFields: Map<String, Long> = mapOf("value" to 7L)): HeapDump =
        HeapDump(
            instances =
                listOf(
                    HeapInstance(
                        1,
                        100,
                        "sample.Root",
                        8,
                        references = listOf(ObjectReference("child", 2), ObjectReference("items", 3)),
                    ),
                    HeapInstance(
                        2,
                        101,
                        "sample.Child",
                        8,
                        primitiveFields = childFields,
                        references = listOf(ObjectReference("root", 1)),
                    ),
                ),
            objectArrays = listOf(HeapObjectArray(3, 102, "sample.Child[]", 450, elementIds = List(450) { 2L })),
            gcRoots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
            heapByObjectId = mapOf(1L to "App", 2L to "Zygote", 3L to "Zygote"),
        )

    private class InspectorFields : Map<String, Long> by mapOf("value" to 7L) {
        var reads = 0
        var failure: Exception? = null

        override val entries: Set<Map.Entry<String, Long>>
            get() {
                reads++
                failure?.let { throw it }
                return mapOf("value" to 7L).entries
            }
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
        val languageChanges = mutableListOf<UiLanguage>()
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

        override fun changeLanguage(language: UiLanguage) {
            languageChanges += language
        }

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
