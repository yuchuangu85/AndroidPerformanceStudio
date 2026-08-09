package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.NativeHeapCapabilities
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeHeapTraceProcessorAdapterTest {
    @Test
    fun `maps authoritative allocation and stack profile tables into the Native Heap model`() {
        val adapter = NativeHeapTraceProcessorAdapter()
        val result =
            adapter.mapFixture(
                allocationCsv =
                    """|callsite_id,allocated_bytes,freed_bytes,alloc_count,free_count
                    |11,100,40,2,1
                    """.trimMargin(),
                callStackCsv =
                    """|callsite_id,parent_id,frame_name,symbolized
                    |10,[NULL],malloc,1
                    |11,10,example::allocate,1
                    """.trimMargin(),
            )

        assertEquals(100, result.analysis.totalAllocatedBytes)
        assertEquals(40, result.analysis.totalFreedBytes)
        assertEquals(
            listOf("malloc", "example::allocate"),
            result.analysis.topAllocations
                .single()
                .callStack,
        )
        assertEquals(
            2,
            result.analysis.topAllocations
                .single()
                .allocCount,
        )
        assertTrue(NativeHeapCapabilities.SYMBOLS in result.availableCapabilities)
        assertEquals(
            result,
            adapter.map(
                adapter.allocationQuery.map(TraceQueryResult.parse(RESULT_ALLOCATION_CSV)),
                adapter.callStackQuery.map(TraceQueryResult.parse(RESULT_CALL_STACK_CSV)),
            ),
        )
    }

    private companion object {
        const val RESULT_ALLOCATION_CSV =
            "callsite_id,allocated_bytes,freed_bytes,alloc_count,free_count\n11,100,40,2,1\n"

        const val RESULT_CALL_STACK_CSV =
            "callsite_id,parent_id,frame_name,symbolized\n10,[NULL],malloc,1\n11,10,example::allocate,1\n"
    }
}
