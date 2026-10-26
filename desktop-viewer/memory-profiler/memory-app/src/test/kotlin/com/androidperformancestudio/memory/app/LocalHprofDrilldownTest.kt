package com.androidperformancestudio.memory.app

import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.presentation.MemoryClassifierColumn
import com.androidperformancestudio.memory.presentation.MemoryClassifierRow
import com.androidperformancestudio.memory.presentation.MemoryLeakFilter
import com.androidperformancestudio.memory.presentation.MemoryProfilerPresenter
import com.androidperformancestudio.memory.presentation.MemoryProfilerState
import com.androidperformancestudio.memory.presentation.MemorySortDirection
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Opt-in large-file acceptance test; set APS_HPROF_FIXTURE to a local Android HPROF. */
class LocalHprofDrilldownTest {
    @Test
    fun `large imported heap supports class and object drilldown under desktop heap cap`() =
        runBlocking {
            val fixture = System.getenv("APS_HPROF_FIXTURE")?.let(Path::of) ?: return@runBlocking
            assertTrue(Files.isRegularFile(fixture), "Missing HPROF fixture: $fixture")
            val root = createTempDirectory("aps-large-hprof-drilldown")
            try {
                val importStarted = System.nanoTime()
                val backend = DesktopMemoryProfilerBackend(root)
                val result = backend.importHprof(fixture)
                val importMillis = elapsedMillis(importStarted)
                val loaded = assertIs<MemoryBackendResult.Success<LoadedHeap>>(result)
                val appClasses = assertNotNull(loaded.value.perHeapClasses["App"])
                val heap = loaded.value.heapDump
                printAppMetrics(appClasses, heap)
                assertEquals(5_378, appClasses.size)
                assertEquals(2_073_125, appClasses.sumOf { it.instanceCount })
                assertEquals(109_063_260L, appClasses.sumOf { it.shallowSize })
                assertEquals(
                    41,
                    heap.activityFragmentLeakObjectIds.count { heap.heapByObjectId[it] == "App" },
                )
                // The pinned Trace Processor is optional; when present, its class-level native
                // totals must agree with the observed Studio 2026.2 App-heap summary.
                if (System.getenv("PERFETTO_TRACE_PROCESSOR_PATH") != null) {
                    assertEquals(34_945_672L, appClasses.sumOf { it.nativeSize ?: 0L })
                    assertEquals(34_944_078L, appClasses.sumOf { it.reachableNativeSize ?: 0L })
                    assertEquals(109_030_672L, appClasses.sumOf { it.reachableShallowSize ?: 0L })
                }
                listOf("com.tcl.android.quickstep.views.IconView", "java.lang.Class", "java.lang.Object[]")
                    .forEach { className ->
                        appClasses.firstOrNull { it.className == className }?.let { stats ->
                            println(
                                "HPROF_CLASS name=$className count=${stats.instanceCount} " +
                                    "shallow=${stats.shallowSize} retained=${stats.retainedSize}",
                            )
                        }
                    }
                val classStarted = System.nanoTime()
                val rows =
                    loaded.value.investigationIndex.query
                        .instancesOf("java.lang.Object[]", "App")
                val classMillis = elapsedMillis(classStarted)
                assertTrue(rows.isNotEmpty(), "The App heap has no java.lang.Object[] instances")
                val detailStarted = System.nanoTime()
                val detail =
                    assertNotNull(
                        loaded.value.investigationIndex.query
                            .detailOf(rows.first().objectId),
                    )
                val detailMillis = elapsedMillis(detailStarted)
                assertTrue(detail.isArray)
                // Optional same-dump object ID observed in the packaged GUI smoke run. Never
                // infer identity from a class name or reuse it across unrelated heap dumps.
                checkSpotObject(loaded.value.investigationIndex)
                println(
                    "HPROF_DRILLDOWN rows=${rows.size} fields=${detail.fields.size} " +
                        "inbound=${detail.references.size} usedMiB=${usedMiB()} " +
                        "importMs=$importMillis classMs=$classMillis detailMs=$detailMillis",
                )
                checkLifecycleLeakWarnings(backend, loaded.value, fixture)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private suspend fun checkLifecycleLeakWarnings(
        backend: MemoryProfilerBackend,
        loaded: LoadedHeap,
        fixture: Path,
    ) {
        val controller = MemoryProfilerController(reusingLoadedHeap(backend, loaded, fixture))
        try {
            controller.importHprof(fixture)
            assertSame(loaded, controller.loadedHeap)
            assertSame(loaded.investigationIndex.query, controller.instanceQuery)
            checkHeapClassifierGroups(controller, loaded)
            controller.changeHeapFilter("App")
            controller.changeLeakFilter(MemoryLeakFilter.ACTIVITY_FRAGMENT_LEAK)

            val presented = MemoryProfilerPresenter.present(controller.state.value)
            val appLeaks = presented.lifecycleLeakObjects.filter { it.heapName == "App" }
            val expectedIds =
                loaded.heapDump.activityFragmentLeakObjectIds.filterTo(mutableSetOf()) {
                    loaded.heapDump.heapByObjectId[it] == "App"
                }
            assertEquals(41, appLeaks.size)
            assertEquals(expectedIds, appLeaks.mapTo(mutableSetOf()) { it.objectId })
            assertEquals(41, presented.classListSummary.leakCount)
            val appParent = presented.classifierRows.single()
            assertEquals("heap:App", appParent.id)
            assertEquals("App heap", appParent.label)
            assertEquals("App", appParent.heapName)
            assertNull(appParent.className)
            val displayedClasses = presented.displayedClasses.associateBy { it.className }
            assertEquals(displayedClasses.keys, appParent.children.map { it.className }.toSet())
            assertEquals(MemoryClassifierColumn.RETAINED_SIZE, presented.classifierSortColumn)
            assertEquals(MemorySortDirection.DESCENDING, presented.classifierSortDirection)
            assertRetainedDescending(appParent.children)
            appLeaks.forEach { leak ->
                assertEquals(loaded.investigationIndex.query.groupingClassNameOf(leak.objectId), leak.className)
                val stats = assertNotNull(displayedClasses[leak.className], "No displayed class for ${leak.objectId}")
                val row = assertNotNull(appParent.children.singleOrNull { it.className == leak.className })
                assertEquals("heap:App/class:${leak.className}", row.id)
                assertEquals("App", row.heapName)
                assertClassifierClassLabel(stats, row)
                assertEquals(stats.instanceCount.toLong(), row.totalCount)
            }

            var normalInstancesChecked = 0
            appLeaks.groupBy { it.className }.forEach { (className, leaks) ->
                val leakIds = leaks.mapTo(mutableSetOf()) { it.objectId }
                controller.selectClass(className)
                val classState = MemoryProfilerPresenter.present(controller.state.value)
                assertEquals(className, classState.selectedClassName)
                checkLeakInstanceRows(classState, leakIds)

                val leakId = leaks.first().objectId
                controller.selectInstance(leakId)
                val instanceState = MemoryProfilerPresenter.present(controller.state.value)
                val detail = assertNotNull(instanceState.selectedInstanceDetail)
                assertEquals(leakId, detail.objectId)
                assertEquals(className, detail.className)
                checkLeakInstanceRows(instanceState, leakIds)

                normalInstancesChecked += checkNormalInstance(controller, loaded, className, leakIds)
            }
            assertTrue(normalInstancesChecked > 0, "No normal instance of a lifecycle leak class was checked")
        } finally {
            controller.close()
        }
    }

    private fun checkHeapClassifierGroups(
        controller: MemoryProfilerController,
        loaded: LoadedHeap,
    ) {
        val all = MemoryProfilerPresenter.present(controller.state.value)
        assertNull(all.heapFilter)
        assertEquals(MemoryClassifierColumn.RETAINED_SIZE, all.classifierSortColumn)
        assertEquals(MemorySortDirection.DESCENDING, all.classifierSortDirection)
        assertEquals(loaded.perHeapClasses, all.perHeapClasses)
        assertRetainedDescending(all.classifierRows)
        assertEquals(listOf("App", "Image", "Zygote"), all.classifierRows.map { it.heapName })
        val rowsByHeap =
            all.classifierRows.associate { parent ->
                val heapName = assertNotNull(parent.heapName)
                val classes = assertNotNull(loaded.perHeapClasses[heapName])
                assertEquals("heap:$heapName", parent.id)
                assertEquals("$heapName heap", parent.label)
                assertNull(parent.className)
                assertEquals(0, parent.depth)
                assertEquals(classes.sumOf { it.instanceCount.toLong() }, parent.totalCount)
                assertEquals(classes.sumOf { it.shallowSize }, parent.shallowSize)
                assertEquals(classes.size, parent.children.size)
                val rows = parent.children.associateBy { it.className }
                assertEquals(classes.map { it.className }.toSet(), rows.keys)
                assertRetainedDescending(parent.children)
                classes.forEach { stats ->
                    val row = assertNotNull(rows[stats.className])
                    assertEquals("heap:$heapName/class:${stats.className}", row.id)
                    assertEquals(heapName, row.heapName)
                    assertEquals(1, row.depth)
                    assertClassifierClassLabel(stats, row)
                    assertEquals(stats.instanceCount.toLong(), row.totalCount)
                    assertEquals(stats.shallowSize, row.shallowSize)
                }
                heapName to rows
            }
        val appRows = rowsByHeap.getValue("App")
        val zygoteRows = rowsByHeap.getValue("Zygote")
        val sharedClasses = appRows.keys.intersect(zygoteRows.keys)
        assertTrue(sharedClasses.isNotEmpty(), "The fixture must exercise same-name classes across heaps")
        val mergedClasses = all.classes.associateBy { it.className }
        sharedClasses.forEach { className ->
            val mergedCount = assertNotNull(mergedClasses[className]).instanceCount.toLong()
            val appCount = appRows.getValue(className).totalCount
            val zygoteCount = zygoteRows.getValue(className).totalCount
            assertTrue(appCount < mergedCount, "App $className includes other heaps")
            assertTrue(zygoteCount < mergedCount, "Zygote $className includes other heaps")
        }
    }

    private fun assertRetainedDescending(rows: List<MemoryClassifierRow>) {
        assertEquals(rows.sortedWith(compareByDescending { it.retainedSize }), rows)
    }

    private fun assertClassifierClassLabel(
        stats: ClassStats,
        row: MemoryClassifierRow,
    ) {
        assertEquals(stats.className, row.className)
        val simpleName = stats.className.substringAfterLast('.')
        val packageName = stats.className.substringBeforeLast('.', "")
        val qualifiedLabel = if (packageName.isEmpty()) simpleName else "$simpleName ($packageName)"
        val expectedLabel = stats.obfuscatedClassName?.let { "$qualifiedLabel [$it]" } ?: qualifiedLabel
        assertEquals(expectedLabel, row.label)
    }

    private fun checkNormalInstance(
        controller: MemoryProfilerController,
        loaded: LoadedHeap,
        className: String,
        leakIds: Set<Long>,
    ): Int {
        val normalId =
            controller.state.value.selectedClassInstances
                .firstOrNull { row ->
                    row.objectId !in loaded.heapDump.activityFragmentLeakObjectIds &&
                        loaded.investigationIndex.query
                            .objectOf(row.objectId)
                            ?.className == className
                }?.objectId ?: return 0
        controller.selectInstance(normalId)
        val normalState = MemoryProfilerPresenter.present(controller.state.value)
        assertEquals(normalId, assertNotNull(normalState.selectedInstanceDetail).objectId)
        assertFalse(normalState.selectedClassInstances.single { it.objectId == normalId }.isLeakWarning)
        checkLeakInstanceRows(normalState, leakIds)
        return 1
    }

    private fun reusingLoadedHeap(
        backend: MemoryProfilerBackend,
        loaded: LoadedHeap,
        fixture: Path,
    ): MemoryProfilerBackend =
        object : MemoryProfilerBackend by backend {
            override fun registerTargetListener(listener: AndroidTargetListener) = AndroidTargetSubscription {}

            // Both overloads must reuse the already parsed large dump, including progress imports.
            override suspend fun importHprof(file: Path): MemoryBackendResult<LoadedHeap> {
                assertEquals(fixture, file)
                return MemoryBackendResult.Success(loaded)
            }

            override suspend fun importHprof(
                file: Path,
                onProgress: (Int) -> Unit,
            ): MemoryBackendResult<LoadedHeap> = importHprof(file)
        }

    private fun checkLeakInstanceRows(
        state: MemoryProfilerState,
        leakIds: Set<Long>,
    ) {
        val rows = state.selectedClassInstances.associateBy { it.objectId }
        leakIds.forEach { objectId ->
            assertTrue(assertNotNull(rows[objectId], "Missing leak instance $objectId").isLeakWarning)
        }
        state.selectedClassInstances.filter { it.objectId !in leakIds }.forEach { row ->
            assertFalse(row.isLeakWarning, "Normal ${state.selectedClassName} instance ${row.objectId} was flagged")
        }
    }

    private fun printAppMetrics(
        appClasses: List<ClassStats>,
        heap: HeapDump,
    ) {
        val representedClassNames =
            heap.classes
                .asSequence()
                .filter { heap.heapByObjectId[it.objectId] == "App" }
                .map { it.name }
                .toSet()
        val studioStyleClassNames = appClasses.map { it.className }.toSet() + representedClassNames
        println(
            "HPROF_APP classes=${appClasses.size} count=${appClasses.sumOf { it.instanceCount }} " +
                "shallow=${appClasses.sumOf { it.shallowSize }} " +
                "sumClassRetained=${appClasses.sumOf { it.retainedSize ?: 0L }} " +
                "estimatedNative=${appClasses.sumOf { it.nativeSize ?: 0L }} " +
                "classRecords=${heap.classes.size} appClassRecords=" +
                heap.classes.count { heap.heapByObjectId[it.objectId] == "App" } +
                " appInstances=${heap.instances.count { heap.heapByObjectId[it.objectId] == "App" }} " +
                "appObjectArrays=${heap.objectArrays.count { heap.heapByObjectId[it.objectId] == "App" }} " +
                "appPrimitiveArrays=${heap.primitiveArrays.count { heap.heapByObjectId[it.objectId] == "App" }} " +
                "representedClassNames=${representedClassNames.size} " +
                "studioStyleClassNames=${studioStyleClassNames.size}",
        )
    }

    private fun checkSpotObject(index: MemoryHeapInvestigationIndex) {
        val objectId = System.getenv("APS_HPROF_SPOT_OBJECT_ID")?.removePrefix("0x")?.toLongOrNull(16) ?: return
        val rooted = assertNotNull(index.query.detailOf(objectId))
        assertTrue(rooted.fields.isNotEmpty(), "The sampled App object lost its fields")
        assertTrue(rooted.references.isNotEmpty(), "The sampled App object lost inbound references")
        assertTrue(rooted.referenceChain.isNotEmpty(), "The sampled App object lost its GC-root path")
    }

    private fun usedMiB(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
