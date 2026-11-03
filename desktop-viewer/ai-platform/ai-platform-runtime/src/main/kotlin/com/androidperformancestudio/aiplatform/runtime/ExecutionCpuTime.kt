package com.androidperformancestudio.aiplatform.runtime

import kotlinx.coroutines.ThreadContextElement
import java.lang.management.ManagementFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Charges only work on the graph's current coroutine threads, not time suspended on IO. */
internal class ExecutionCpuTime(
    ledgers: List<ExecutionBudgetLedger>,
    private val readCpuNanos: () -> Long = ::readThreadCpuNanos,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<ExecutionCpuTime.Sample?> {
    private val ledgers = ledgers.distinct()

    internal class Sample(
        private val meter: ExecutionCpuTime,
    ) {
        private var previous = meter.readCpuNanos()

        fun checkpoint() {
            val current = meter.readCpuNanos()
            val elapsed = if (previous < 0 || current < previous) -1L else current - previous
            previous = current
            meter.ledgers.forEach { it.recordCpuTime(elapsed) }
        }

        fun restart() {
            previous = meter.readCpuNanos()
        }
    }

    override fun updateThreadContext(context: CoroutineContext): Sample? {
        val previous = activeSample.get()
        previous?.checkpoint()
        activeSample.set(Sample(this))
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Sample?,
    ) {
        // Context hooks only record usage; budget failures are raised at ordinary execution checks.
        activeSample.get()?.checkpoint()
        if (oldState == null) {
            activeSample.remove()
        } else {
            oldState.restart()
            activeSample.set(oldState)
        }
    }

    internal companion object Key : CoroutineContext.Key<ExecutionCpuTime> {
        private val activeSample = ThreadLocal<Sample?>()

        fun checkpoint() {
            activeSample.get()?.checkpoint()
        }
    }
}

private val threadCpuBean by lazy {
    ManagementFactory.getThreadMXBean().also { bean ->
        if (bean.isCurrentThreadCpuTimeSupported && !bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
    }
}

private fun readThreadCpuNanos(): Long =
    try {
        if (threadCpuBean.isCurrentThreadCpuTimeSupported) threadCpuBean.currentThreadCpuTime else -1L
    } catch (_: Exception) {
        -1L
    }
