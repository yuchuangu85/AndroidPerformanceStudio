package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.aiplatform.model.ResourceBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExecutionBudgetTest {
    @Test
    fun `children and skill calls consume one cumulative ledger`() {
        val ledger = ExecutionBudgetLedger(ResourceBudget(maxInputBytes = 100, maxOutputBytes = 100, maxChildDepth = 1), maxInvocations = 3)
        ledger.enter(0, 20)
        ledger.enter(1, 30)
        ledger.consumeOutput(40)
        ledger.enter(1, 40)
        ledger.consumeOutput(50)
        assertEquals(3, ledger.snapshot().invocations)
        assertEquals(90L, ledger.snapshot().inputBytes)
        assertEquals(90L, ledger.snapshot().outputBytes)
        assertEquals("E_CALL_BUDGET", assertFailsWith<AgentPolicyException> { ledger.enter(0) }.code)
        assertEquals("E_OUTPUT_BUDGET", assertFailsWith<AgentPolicyException> { ledger.consumeOutput(11) }.code)
    }

    @Test
    fun `depth time input output and arithmetic overflow reject before accounting`() {
        var time = 0L
        val ledger = ExecutionBudgetLedger(ResourceBudget(timeoutMillis = 10, maxInputBytes = 10, maxOutputBytes = 10), nanoTime = { time })
        ledger.enter(0, 1)
        assertEquals("E_CHILD_DEPTH", assertFailsWith<AgentPolicyException> { ledger.enter(1) }.code)
        assertEquals("E_INPUT_BUDGET", assertFailsWith<AgentPolicyException> { ledger.enter(0, Long.MAX_VALUE) }.code)
        assertEquals("E_OUTPUT_BUDGET", assertFailsWith<AgentPolicyException> { ledger.consumeOutput(Long.MAX_VALUE) }.code)
        time = 11_000_000
        assertEquals("E_TIME_BUDGET", assertFailsWith<AgentPolicyException> { ledger.checkTime() }.code)
        assertEquals(1, ledger.snapshot().invocations)
    }

    @Test
    fun `waiting spends wall time but not CPU allowance`() {
        var time = 0L
        val ledger =
            ExecutionBudgetLedger(
                ResourceBudget(timeoutMillis = 1000, maxCpuMillis = 10),
                nanoTime = { time },
            )
        ledger.enter(0, 1)
        time = 11_000_000
        ledger.enter(0, 1)
        ledger.consumeOutput(1)
        assertEquals(2, ledger.snapshot().invocations)
        assertEquals(11L, ledger.snapshot().elapsedMillis)
        assertEquals(0L, ledger.snapshot().cpuMillis)
    }

    @Test
    fun `measured CPU exhaustion rejects before counting another invocation or output`() {
        val ledger = ExecutionBudgetLedger(ResourceBudget(timeoutMillis = 1000, maxCpuMillis = 10))
        ledger.enter(0, 1)
        ledger.recordCpuTime(11_000_000)
        assertEquals("E_CPU_BUDGET", assertFailsWith<AgentPolicyException> { ledger.enter(0, 1) }.code)
        assertEquals("E_CPU_BUDGET", assertFailsWith<AgentPolicyException> { ledger.consumeOutput(1) }.code)
        assertEquals(1, ledger.snapshot().invocations)
        assertEquals(1L, ledger.snapshot().inputBytes)
        assertEquals(0L, ledger.snapshot().outputBytes)
        assertEquals(11L, ledger.snapshot().cpuMillis)
    }

    @Test
    fun `CPU accounting saturates safely and unsupported sampling fails closed`() {
        val overflow = ExecutionBudgetLedger(ResourceBudget())
        overflow.recordCpuTime(Long.MAX_VALUE)
        overflow.recordCpuTime(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE / 1_000_000, overflow.snapshot().cpuMillis)
        assertEquals("E_CPU_BUDGET", assertFailsWith<AgentPolicyException> { overflow.checkTime() }.code)

        val unavailable = ExecutionBudgetLedger(ResourceBudget())
        unavailable.recordCpuTime(-1)
        assertEquals("E_CPU_METER_UNAVAILABLE", assertFailsWith<AgentPolicyException> { unavailable.checkTime() }.code)
    }
}
