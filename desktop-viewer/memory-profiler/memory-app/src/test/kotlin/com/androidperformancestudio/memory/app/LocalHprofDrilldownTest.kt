package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDump
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
                val result = DesktopMemoryProfilerBackend(root).importHprof(fixture)
                val importMillis = elapsedMillis(importStarted)
                val loaded =
                    result as? MemoryBackendResult.Success
                        ?: error("HPROF import failed: $result")
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
            } finally {
                root.toFile().deleteRecursively()
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
