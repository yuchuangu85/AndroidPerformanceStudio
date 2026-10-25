package com.androidperformancestudio.memory.app

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HprofTraceProcessorMetricsTest {
    @Test
    fun `real launcher hprof class metrics match Studio reference`() =
        runBlocking {
            val fixture = System.getenv("APS_HPROF_FIXTURE")?.let(Path::of) ?: return@runBlocking
            require(Files.isRegularFile(fixture))
            val metrics = assertNotNull(TraceProcessorHprofClassMetricsAnalyzer().analyze(fixture))
            val app = metrics.filter { it.heapName == "app" }
            assertEquals(5378, app.size)
            assertEquals(2_073_125L, app.sumOf { it.objectCount })
            assertEquals(109_063_260L, app.sumOf { it.shallowSize })
            assertEquals(34_945_672L, app.sumOf { it.nativeSize })
            assertEquals(109_030_672L, app.sumOf { it.reachableShallowSize })
            assertEquals(34_944_078L, app.sumOf { it.reachableNativeSize })
            val objectArrays = app.single { it.className == "java.lang.Object[]" }
            assertEquals(86_385L, objectArrays.objectCount)
            assertEquals(3_862_520L, objectArrays.shallowSize)
        }
}
