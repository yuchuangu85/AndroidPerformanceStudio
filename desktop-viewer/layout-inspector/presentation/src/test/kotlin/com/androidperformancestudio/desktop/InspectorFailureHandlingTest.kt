package com.androidperformancestudio.desktop

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InspectorFailureHandlingTest {
    @Test
    fun `ordinary inspection failures remain recoverable`() {
        val failure = IllegalStateException("agent unavailable")
        val result = runInspectorCatching { throw failure }
        assertSame(failure, result.exceptionOrNull())
        assertEquals("compatible", result.getOrElse { "compatible" })
    }

    @Test
    fun `heap exhaustion escapes before fallback can run`() {
        val fatal = OutOfMemoryError("synthetic heap failure")
        var fallbackInvoked = false
        val thrown = assertThrows(OutOfMemoryError::class.java) {
            runInspectorCatching { throw fatal }.getOrElse { fallbackInvoked = true }
        }
        assertSame(fatal, thrown)
        assertFalse(fallbackInvoked)
    }

    @Test
    fun `cancellation escapes before fallback can run`() {
        val cancelled = CancellationException("target changed")
        var fallbackInvoked = false
        val thrown = assertThrows(CancellationException::class.java) {
            runInspectorCatching { throw cancelled }.getOrElse { fallbackInvoked = true }
        }
        assertSame(cancelled, thrown)
        assertFalse(fallbackInvoked)
    }
}
