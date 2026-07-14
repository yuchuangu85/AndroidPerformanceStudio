package com.androidperformancestudio.storage

enum class ProfileTrackKind {
    CPU_SAMPLES,
    CONTEXT_SWITCHES,
    MARKERS,
    COUNTERS,
    SLICES,
    SCREENSHOTS,
}

enum class ProfileDataAvailability {
    AVAILABLE,
    EMPTY,
    NOT_COLLECTED,
    UNAVAILABLE,
    UNAUTHORIZED,
    FAILED,
    NOT_APPLICABLE,
}

data class ProfileTrackSnapshot(
    val id: String,
    val kind: ProfileTrackKind,
    val processId: Int?,
    val threadId: Int?,
    val availability: ProfileDataAvailability,
    val startNanos: Long?,
    val endNanosExclusive: Long?,
)

data class ProfileProjectionRequest(
    val query: ProfileQuery = ProfileQuery(),
    val timelineBucketCount: Int = 600,
    val topFunctionLimit: Int = 200,
    val topSearch: String = "",
    val topSort: TopFunctionSort = TopFunctionSort.INCLUSIVE_WEIGHT,
    val topDescending: Boolean = true,
    val callTreeDirection: CallTreeDirection = CallTreeDirection.FORWARD,
) {
    init {
        require(timelineBucketCount > 0) { "timelineBucketCount must be positive" }
        require(topFunctionLimit > 0) { "topFunctionLimit must be positive" }
    }
}

data class ProfileProjectionSnapshot(
    val query: ProfileQuery,
    val overview: ProfileOverview,
    val quality: DataQualitySummary,
    val tracks: List<ProfileTrackSnapshot>,
    val threads: List<ThreadSummary>,
    val timeline: List<TimelineBucket>,
    val topFunctions: List<TopFunction>,
    val forwardCallTree: List<CallTreeNode>,
    val sessionOverview: ProfileOverview = overview,
    val sessionThreads: List<ThreadSummary> = threads,
    val callTree: List<CallTreeNode> = forwardCallTree,
)
