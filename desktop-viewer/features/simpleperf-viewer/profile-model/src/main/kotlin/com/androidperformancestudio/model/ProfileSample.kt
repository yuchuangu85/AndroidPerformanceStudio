package com.androidperformancestudio.model

data class ProfileSample(
    val timestampNanos: Long,
    val processId: Int,
    val threadId: Int,
    val eventType: String,
    val symbolName: String,
    val eventCount: Long,
)
