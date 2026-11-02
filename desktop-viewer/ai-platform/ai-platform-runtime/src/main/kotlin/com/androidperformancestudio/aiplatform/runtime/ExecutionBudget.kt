package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.aiplatform.model.ResourceBudget

public data class ExecutionBudgetSnapshot(
    val invocations: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val elapsedMillis: Long,
    val deepestChild: Int,
)

/**
 * One monotonic ledger shared by the root, children, Skills and provider; no budget resets per child.
 * The controlled serial host lane conservatively spends CPU allowance using elapsed time, including
 * waiting. It can stop earlier than actual CPU exhaustion and is not process-tree CPU metering.
 */
public class ExecutionBudgetLedger(
    private val budget: ResourceBudget,
    private val maxInvocations: Int = 16,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    private var calls = 0
    private var input = 0L
    private var output = 0L
    private var deepest = 0

    init {
        require(maxInvocations in 1..64)
    }

    @Synchronized
    public fun enter(
        depth: Int,
        inputBytes: Long = 0,
    ) {
        requireBudget(depth in 0..budget.maxChildDepth, "E_CHILD_DEPTH")
        requireBudget(inputBytes >= 0 && calls < maxInvocations, "E_CALL_BUDGET")
        checkTime()
        requireBudget(inputBytes <= budget.maxInputBytes - input, "E_INPUT_BUDGET")
        calls++
        input += inputBytes
        deepest = maxOf(deepest, depth)
    }

    @Synchronized
    public fun consumeOutput(bytes: Long) {
        requireBudget(bytes >= 0 && bytes <= budget.maxOutputBytes - output, "E_OUTPUT_BUDGET")
        checkTime()
        output += bytes
    }

    @Synchronized
    public fun snapshot(): ExecutionBudgetSnapshot = ExecutionBudgetSnapshot(calls, input, output, elapsed(), deepest)

    @Synchronized
    public fun checkTime() {
        val elapsed = elapsed()
        requireBudget(elapsed <= budget.timeoutMillis, "E_TIME_BUDGET")
        requireBudget(elapsed <= budget.maxCpuMillis, "E_CPU_BUDGET")
    }

    private fun elapsed(): Long = ((nanoTime() - started).coerceAtLeast(0) / 1_000_000).coerceAtMost(3_600_000)
}

private fun requireBudget(
    condition: Boolean,
    code: String,
) {
    if (!condition) throw AgentPolicyException(code)
}
