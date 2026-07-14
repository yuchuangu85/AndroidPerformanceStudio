package com.androidperformancestudio.model

@JvmInline
value class ProfileSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "source id must not be blank" }
    }
}

enum class ProfileSourceKind {
    SIMPLEPERF,
    PERFETTO,
    APP_INSTRUMENTATION,
    IMPORTED,
}

data class ProfileProcessKey(val sourceId: ProfileSourceId, val processId: Int)

data class ProfileThreadKey(
    val sourceId: ProfileSourceId,
    val process: ProfileProcessKey,
    val threadId: Int,
)

data class ProfileCategory(val name: String, val subcategory: String? = null) {
    init {
        require(name.isNotBlank()) { "category name must not be blank" }
    }
}

@JvmInline
value class ProfileClockDomain(val value: String) {
    init {
        require(value.isNotBlank()) { "clock domain must not be blank" }
    }
}

data class ProfileTimePoint(
    val clockDomain: ProfileClockDomain,
    val timestampNanos: Long,
    val errorBoundNanos: Long = 0,
) {
    init {
        require(errorBoundNanos >= 0) { "error bound must be non-negative" }
    }
}
