package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.aiplatform.model.ResourceBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExecutionCpuTimeTest {
    @Test
    fun `suspended provider waiting and unrelated CPU work are not charged`() =
        runBlocking {
            val cpu = AtomicLong()
            val ledger = ExecutionBudgetLedger(ResourceBudget(maxCpuMillis = 10))
            val gate = CompletableDeferred<Unit>()
            val work =
                launch {
                    withContext(ExecutionCpuTime(listOf(ledger), cpu::get)) {
                        cpu.addAndGet(2_000_000)
                        gate.await()
                        cpu.addAndGet(3_000_000)
                        ledger.checkTime()
                    }
                }
            yield()
            assertEquals(2L, ledger.snapshot().cpuMillis)
            cpu.addAndGet(5_000_000_000)
            gate.complete(Unit)
            work.join()
            assertEquals(5L, ledger.snapshot().cpuMillis)
        }

    @Test
    fun `nested child CPU is charged once to its parents and never resets root allowance`() {
        var cpu = 0L
        val root = ExecutionBudgetLedger(ResourceBudget(maxCpuMillis = 10))
        val child = ExecutionBudgetLedger(ResourceBudget(maxCpuMillis = 10))
        val meter = ExecutionCpuTime(listOf(root)) { cpu }
        val childMeter = ExecutionCpuTime(listOf(root, child)) { cpu }
        val beforeRoot = meter.updateThreadContext(EmptyCoroutineContext)
        try {
            cpu += 2_000_000
            val beforeChild = childMeter.updateThreadContext(EmptyCoroutineContext)
            try {
                cpu += 7_000_000
                assertEquals(9L, root.snapshot().cpuMillis)
                assertEquals(7L, child.snapshot().cpuMillis)
            } finally {
                childMeter.restoreThreadContext(EmptyCoroutineContext, beforeChild)
            }
            cpu += 2_000_000
            assertEquals("E_CPU_BUDGET", assertFailsWith<AgentPolicyException> { root.checkTime() }.code)
        } finally {
            meter.restoreThreadContext(EmptyCoroutineContext, beforeRoot)
        }
        assertEquals(11L, root.snapshot().cpuMillis)
        assertEquals(7L, child.snapshot().cpuMillis)
    }

    @Test
    fun `dispatcher switches use separate thread samples and preserve cumulative CPU`() =
        runBlocking {
            val clocks = ThreadLocal.withInitial { 0L }
            val ledger = ExecutionBudgetLedger(ResourceBudget(maxCpuMillis = 20))
            withContext(ExecutionCpuTime(listOf(ledger), clocks::get)) {
                clocks.set(clocks.get() + 2_000_000)
                withContext(Dispatchers.Default) {
                    clocks.set(clocks.get() + 3_000_000)
                }
                clocks.set(clocks.get() + 5_000_000)
                ledger.checkTime()
            }
            assertEquals(10L, ledger.snapshot().cpuMillis)
        }

    @Test
    fun `context restoration never throws a budget failure and clears the active meter`() {
        val ledger = ExecutionBudgetLedger(ResourceBudget())
        val meter = ExecutionCpuTime(listOf(ledger)) { -1L }
        val previous = meter.updateThreadContext(EmptyCoroutineContext)
        meter.restoreThreadContext(EmptyCoroutineContext, previous)
        assertEquals("E_CPU_METER_UNAVAILABLE", assertFailsWith<AgentPolicyException> { ledger.checkTime() }.code)
        ExecutionBudgetLedger(ResourceBudget()).checkTime()
    }
}
