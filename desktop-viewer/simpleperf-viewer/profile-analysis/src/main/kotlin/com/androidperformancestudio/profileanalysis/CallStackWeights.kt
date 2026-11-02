package com.androidperformancestudio.profileanalysis

import kotlin.math.roundToLong

fun CallStackTable.withWeightType(
    type: CallStackWeightType,
    tracedTimeSource: CallStackTable = this,
): CallStackTable {
    stacks.firstOrNull { it.weight < 0 }?.let { invalid ->
        throw IllegalArgumentException(
            "Negative call-stack weight is unsupported: sampleId=${invalid.sampleId}, weight=${invalid.weight}",
        )
    }
    // Gecko processing stores compact per-thread deltas and then cumulatively
    // reconstructs milliseconds. The floating-point ordering matters when samples
    // from different threads share an original timestamp; never rewrite that fact.
    val firefoxTimes = if (type == CallStackWeightType.SAMPLES) tracedTimeSource.firefoxTracedTimes() else emptyMap()
    val orderedIndexes =
        stacks.indices.sortedWith(
            (
                if (type == CallStackWeightType.SAMPLES) {
                    compareBy<Int> { checkNotNull(firefoxTimes[stacks[it].sampleId]) }
                } else {
                    compareBy<Int> { stacks[it].timestampNanos }
                }
            ).thenByDescending { stacks[it].threadKey }
                .thenBy { stacks[it].sampleId },
        )
    val traced = LongArray(stacks.size)
    orderedIndexes.forEachIndexed { position, index ->
        traced[index] =
            stacks[index].tracedDurationNanos
                ?: orderedIndexes.getOrNull(position + 1)?.let { next ->
                    val startMillis = firefoxTimes[stacks[index].sampleId]
                    val endMillis = firefoxTimes[stacks[next].sampleId]
                    if (startMillis != null && endMillis != null) {
                        ((endMillis - startMillis) * FIREFOX_TRACED_INTERVAL_NANOS).roundToLong().coerceAtLeast(0)
                    } else {
                        tracedDuration(stacks[index].timestampNanos, stacks[next].timestampNanos)
                    }
                }
                ?: FIREFOX_TRACED_INTERVAL_NANOS
    }
    return copy(
        stacks =
            stacks.mapIndexed { index, stack ->
                stack.copy(
                    weight = if (type == CallStackWeightType.SAMPLES) 1L else stack.weight,
                    tracedDurationNanos = traced[index],
                )
            },
        weightType = type,
    )
}

private fun CallStackTable.firefoxTracedTimes(): Map<Long, Double> {
    val times = HashMap<Long, Double>(stacks.size)
    stacks.groupBy { it.threadKey }.values.forEach { thread ->
        var previousNanos = 0L
        var previousMillis = 0.0
        thread.sortedWith(compareBy({ it.timestampNanos }, { it.sampleId })).forEach { stack ->
            val deltaMillis =
                (stack.timestampNanos.toDouble() - previousNanos.toDouble()) / FIREFOX_TRACED_INTERVAL_NANOS
            previousMillis += deltaMillis
            previousNanos = stack.timestampNanos
            times[stack.sampleId] = previousMillis
        }
    }
    return times
}

private fun tracedDuration(
    start: Long,
    end: Long,
): Long =
    try {
        Math.subtractExact(end, start)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

private const val FIREFOX_TRACED_INTERVAL_NANOS = 1_000_000L
