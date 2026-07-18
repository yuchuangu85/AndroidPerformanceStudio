package com.androidperformancestudio.storage

@JvmInline
value class ProfileMarkerId(
    val value: Long,
)

enum class MarkerAvailability { AVAILABLE, NOT_COLLECTED }

enum class MarkerEmptyReason { PROFILE_EMPTY, RANGE_EMPTY, FILTERED_EMPTY }

data class MarkerProjectionRow(
    val id: ProfileMarkerId,
    val sourceId: String,
    val processId: Int?,
    val threadId: Int?,
    val threadName: String?,
    val startNanos: Long,
    val endNanosExclusive: Long,
    val interval: Boolean,
    val schema: String,
    val name: String,
    val payloadJson: String,
)

data class MarkerLane(
    val key: String,
    val label: String,
    val markerIds: List<ProfileMarkerId>,
)

data class MarkerProjectionSnapshot(
    val availability: MarkerAvailability,
    val emptyReason: MarkerEmptyReason?,
    val markers: List<MarkerProjectionRow>,
    val lanes: List<MarkerLane>,
)
