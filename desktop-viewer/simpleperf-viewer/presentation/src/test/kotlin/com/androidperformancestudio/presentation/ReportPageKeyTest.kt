package com.androidperformancestudio.presentation

import com.androidperformancestudio.storage.TopFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReportPageKeyTest {
    @Test
    fun `duplicate unknown symbols receive unique stable list keys`() {
        val first = unknownHwuiFunction()
        val second = unknownHwuiFunction()

        assertNotEquals(topFunctionItemKey(0, first), topFunctionItemKey(1, second))
        assertEquals(topFunctionItemKey(0, first), topFunctionItemKey(0, first))
    }

    private fun unknownHwuiFunction(): TopFunction =
        TopFunction(
            symbolName = "<unknown-symbol>",
            filePath = "/system/lib64/libhwui.so",
            inclusiveWeight = 1,
            exclusiveWeight = 1,
            sampleCount = 1,
            threadCount = 1,
        )
}
