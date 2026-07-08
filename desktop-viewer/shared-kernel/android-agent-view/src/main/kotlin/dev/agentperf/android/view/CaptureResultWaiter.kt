package dev.agentperf.android.view

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class CaptureResultWaiter<T> {
    private val completed = AtomicBoolean(false)
    private val latch = CountDownLatch(1)
    private val value = AtomicReference<T?>()
    private val failure = AtomicReference<Throwable?>()

    fun complete(result: T) {
        if (completed.compareAndSet(false, true)) {
            value.set(result)
            latch.countDown()
        }
    }

    fun completeExceptionally(error: Throwable) {
        if (completed.compareAndSet(false, true)) {
            failure.set(error)
            latch.countDown()
        }
    }

    fun await(timeout: Long, unit: TimeUnit): T {
        if (!latch.await(timeout, unit)) {
            throw TimeoutException()
        }
        failure.get()?.let { throw ExecutionException(it) }
        @Suppress("UNCHECKED_CAST")
        return value.get() as T
    }
}
