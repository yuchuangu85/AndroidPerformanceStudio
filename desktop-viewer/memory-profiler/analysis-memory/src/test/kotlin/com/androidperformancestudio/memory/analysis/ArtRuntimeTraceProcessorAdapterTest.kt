@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.memory.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtRuntimeTraceProcessorAdapterTest {
    @Test
    fun `keeps gc allocation jit and class evidence distinct`() {
        val result =
            ArtRuntimeTraceProcessorAdapter().mapFixture(
                gcCsv = "process_name,gc_type,count,total_duration_ns,maximum_duration_ns,reclaimed_mb\napp,concurrent copying,2,3000000,2000000,8.5\n",
                allocationCsv = "process_name,allocated_mb,allocation_rate_mb_s,heap_size_mb,heap_utilization_percent,gc_cpu_rate_percent\napp,24.0,4.0,48.0,50.0,3.0\n",
                compilationCsv = "process_name,thread_name,operation,count,total_duration_ns,maximum_duration_ns\napp,Jit thread pool,JIT compiling Foo,3,900000,400000\n",
                classLoadingCsv = "process_name,thread_name,operation,count,total_duration_ns,maximum_duration_ns\napp,main,Ldev/example/Foo;,1,500000,500000\n",
            )

        assertEquals(2, result.garbageCollections.single().count)
        assertEquals(4.0, result.allocationChurn.single().allocationRateMbPerSecond)
        assertEquals("JIT compiling Foo", result.compilation.single().name)
        assertEquals("Ldev/example/Foo;", result.classLoadingAndInitialization.single().name)
    }
}
