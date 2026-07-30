package com.androidperformancestudio.android.view

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CaptureResultWaiterTest {
    @Test
    fun `returns completed value`() {
        val waiter = CaptureResultWaiter<String>()

        waiter.complete("frame")

        assertEquals("frame", waiter.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `wraps failed capture in execution exception`() {
        val waiter = CaptureResultWaiter<String>()
        val cause = IllegalStateException("capture failed")

        waiter.completeExceptionally(cause)

        val error = assertThrows(ExecutionException::class.java) {
            waiter.await(1, TimeUnit.SECONDS)
        }
        assertSame(cause, error.cause)
    }

    @Test
    fun `times out when capture never completes`() {
        val waiter = CaptureResultWaiter<String>()

        assertThrows(TimeoutException::class.java) {
            waiter.await(1, TimeUnit.MILLISECONDS)
        }
    }
}
