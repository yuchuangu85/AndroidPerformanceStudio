package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.aiplatform.model.ResourceBudget

public data class ExecutionBudgetSnapshot(
    val invocations: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val elapsedMillis: Long,
    val deepestChild: Int,
    val cpuMillis: Long = 0,
)

/**
 * One monotonic ledger shared by the root, children, Skills and provider; no budget resets per child.
 * CPU is charged by the coroutine's thread meter, separately from the wall deadline. Native script
 * process-tree limits remain the sandbox's responsibility.
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
    private var cpuNanos = 0L
    private var cpuMeterAvailable = true

    init {
        require(maxInvocations in 1..64)
    }

    public fun enter(
        depth: Int,
        inputBytes: Long = 0,
    ) {
        ExecutionCpuTime.checkpoint()
        synchronized(this) {
            requireBudget(depth in 0..budget.maxChildDepth, "E_CHILD_DEPTH")
            requireBudget(inputBytes >= 0 && calls < maxInvocations, "E_CALL_BUDGET")
            checkTimeLocked()
            requireBudget(inputBytes <= budget.maxInputBytes - input, "E_INPUT_BUDGET")
            calls++
            input += inputBytes
            deepest = maxOf(deepest, depth)
        }
    }

    public fun consumeOutput(bytes: Long) {
        ExecutionCpuTime.checkpoint()
        synchronized(this) {
            requireBudget(bytes >= 0 && bytes <= budget.maxOutputBytes - output, "E_OUTPUT_BUDGET")
            checkTimeLocked()
            output += bytes
        }
    }

    public fun snapshot(): ExecutionBudgetSnapshot {
        ExecutionCpuTime.checkpoint()
        return synchronized(this) { ExecutionBudgetSnapshot(calls, input, output, elapsed(), deepest, cpuNanos / 1_000_000) }
    }

    public fun checkTime() {
        ExecutionCpuTime.checkpoint()
        synchronized(this) { checkTimeLocked() }
    }

    @Synchronized
    internal fun recordCpuTime(nanos: Long) {
        if (nanos < 0) {
            cpuMeterAvailable = false
        } else {
            cpuNanos += nanos.coerceAtMost(Long.MAX_VALUE - cpuNanos)
        }
    }

    private fun checkTimeLocked() {
        requireBudget(elapsed() <= budget.timeoutMillis, "E_TIME_BUDGET")
        requireBudget(cpuMeterAvailable, "E_CPU_METER_UNAVAILABLE")
        requireBudget(cpuNanos <= budget.maxCpuMillis * 1_000_000, "E_CPU_BUDGET")
    }

    private fun elapsed(): Long = ((nanoTime() - started).coerceAtLeast(0) / 1_000_000).coerceAtMost(3_600_000)
}

private fun requireBudget(
    condition: Boolean,
    code: String,
) {
    if (!condition) throw AgentPolicyException(code)
}
