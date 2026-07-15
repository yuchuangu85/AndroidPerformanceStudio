package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals

class CallStackContractsTest {
    @Test
    fun `search parser trims blanks and preserves Firefox comma order`() {
        assertEquals(listOf("render", "libc"), parseFlameSearchTerms(" render, ,libc "))
    }

    @Test
    fun `call node path uses structural equality`() {
        assertEquals(
            CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2))),
            CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2))),
        )
    }
}
