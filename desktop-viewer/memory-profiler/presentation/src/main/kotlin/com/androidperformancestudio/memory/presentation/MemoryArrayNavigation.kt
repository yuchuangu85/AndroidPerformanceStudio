package com.androidperformancestudio.memory.presentation

internal fun MemoryInstanceDetail.previousArrayPageStart(): Int? =
    if (isArray && arrayStart > 0) (arrayStart - arrayPageSize).coerceAtLeast(0) else null

internal fun MemoryInstanceDetail.nextArrayPageStart(): Int? {
    val count = elementCount ?: 0
    return if (isArray && fields.size >= arrayPageSize && arrayStart.toLong() + fields.size < count) {
        (arrayStart.toLong() + arrayPageSize).coerceAtMost(count.toLong() - 1).toInt()
    } else {
        null
    }
}
