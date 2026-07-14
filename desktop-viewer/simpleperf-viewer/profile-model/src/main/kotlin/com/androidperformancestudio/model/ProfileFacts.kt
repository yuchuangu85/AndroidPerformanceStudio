package com.androidperformancestudio.model

data class ProfileSourceFact(
    val id: ProfileSourceId,
    val kind: ProfileSourceKind,
    val clockDomain: ProfileClockDomain,
    val validFromNanos: Long?,
    val validUntilNanosExclusive: Long?,
)

data class ProfileProcessFact(
    val key: ProfileProcessKey,
    val name: String?,
    val start: ProfileTimePoint?,
    val end: ProfileTimePoint?,
)

data class ProfileThreadFact(
    val key: ProfileThreadKey,
    val name: String,
    val start: ProfileTimePoint?,
    val end: ProfileTimePoint?,
)

data class ProfileSampleFact(
    val sourceId: ProfileSourceId,
    val time: ProfileTimePoint,
    val thread: ProfileThreadKey,
    val eventType: String,
    val eventCount: Long,
    val cpuCore: Int?,
    val onCpu: Boolean?,
    val category: ProfileCategory?,
    val frames: List<ProfileFrame>,
    val unwindError: ProfileUnwindError?,
)

data class ProfileMarkerFact(
    val sourceId: ProfileSourceId,
    val thread: ProfileThreadKey?,
    val start: ProfileTimePoint,
    val end: ProfileTimePoint?,
    val schema: String,
    val name: String,
    val payloadJson: String,
)

data class ProfileCounterFact(
    val sourceId: ProfileSourceId,
    val time: ProfileTimePoint,
    val name: String,
    val unit: String,
    val value: Double,
)

data class ProfileSliceFact(
    val sourceId: ProfileSourceId,
    val thread: ProfileThreadKey?,
    val start: ProfileTimePoint,
    val end: ProfileTimePoint,
    val name: String,
    val category: ProfileCategory?,
) {
    init {
        require(end.timestampNanos >= start.timestampNanos) { "slice end must not precede start" }
    }
}

data class ProfileScreenshotFact(
    val sourceId: ProfileSourceId,
    val time: ProfileTimePoint,
    val artifactPath: String,
)
