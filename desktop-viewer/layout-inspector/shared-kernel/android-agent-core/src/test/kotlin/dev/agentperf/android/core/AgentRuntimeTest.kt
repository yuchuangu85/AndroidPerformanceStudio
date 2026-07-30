package com.androidperformancestudio.android.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AgentRuntimeTest {
    @Test
    fun `does not start inside a non-debuggable application`() {
        var transportStarted = false
        val runtime = AgentRuntime { transportStarted = true }

        val result = runtime.start(debuggable = false)

        assertEquals(StartResult.DISABLED_NOT_DEBUGGABLE, result)
        assertFalse(transportStarted)
    }

    @Test
    fun `starts transport once for a debuggable application`() {
        var starts = 0
        val runtime = AgentRuntime { starts += 1 }

        assertEquals(StartResult.STARTED, runtime.start(debuggable = true))
        assertEquals(StartResult.ALREADY_RUNNING, runtime.start(debuggable = true))
        assertEquals(1, starts)
    }
}
