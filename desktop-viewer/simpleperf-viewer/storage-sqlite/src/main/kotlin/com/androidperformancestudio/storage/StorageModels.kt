package com.androidperformancestudio.storage

import com.androidperformancestudio.model.ProfileFrame

data class SampleImportResult(
    val importedSamples: Long,
    val committedBatches: Int,
)

data class ProfileImportResult(
    val importedRecords: Long,
    val importedSamples: Long,
    val committedBatches: Int,
)

data class SymbolWeight(
    val symbolName: String,
    val totalEventCount: Long,
)

data class ProfileQuery(
    val startNanosInclusive: Long? = null,
    val endNanosExclusive: Long? = null,
    val threadIds: Set<Int> = emptySet(),
    val eventTypes: Set<String> = emptySet(),
) {
    init {
        require(
            startNanosInclusive == null || endNanosExclusive == null || startNanosInclusive < endNanosExclusive,
        ) { "startNanosInclusive must be less than endNanosExclusive" }
    }
}

data class ThreadSummary(
    val processId: Int,
    val threadId: Int,
    val name: String,
    val sampleCount: Long,
    val totalEventCount: Long,
)

data class StoredProfileThread(
    val key: String,
    val processId: Int,
    val threadId: Int,
    val name: String,
)

data class StoredProfileSample(
    val thread: StoredProfileThread,
    val timestampNanos: Long,
    val framesRootToLeaf: List<ProfileFrame>,
)

data class TopFunction(
    val symbolName: String,
    val filePath: String,
    val inclusiveWeight: Long,
    val exclusiveWeight: Long,
    val sampleCount: Long,
    val threadCount: Long,
)

enum class TopFunctionSort {
    INCLUSIVE_WEIGHT,
    EXCLUSIVE_WEIGHT,
    SAMPLE_COUNT,
    THREAD_COUNT,
    SYMBOL_NAME,
    FILE_PATH,
}

internal data class TopFunctionOptions(
    val limit: Int,
    val search: String,
    val sort: TopFunctionSort,
    val descending: Boolean,
)

data class ProfileOverview(
    val startNanos: Long?,
    val endNanosInclusive: Long?,
    val sampleCount: Long,
    val totalEventWeight: Long,
    val processCount: Long,
    val threadCount: Long,
    val eventTypes: List<String>,
)

data class TimelineBucket(
    val startNanos: Long,
    val endNanosExclusive: Long,
    val sampleCount: Long,
    val eventWeight: Long,
)

data class ThreadTimelineTrack(
    val id: String,
    val processId: Int,
    val threadId: Int,
    val name: String,
    val buckets: List<TimelineBucket>,
)

data class CallTreeNode(
    val id: Long,
    val parentId: Long?,
    val depth: Int,
    val symbolName: String,
    val filePath: String,
    val inclusiveWeight: Long,
    val exclusiveWeight: Long,
    val sampleCount: Long,
    val threadCount: Long,
)

data class UnwindErrorSummary(
    val code: String,
    val rawCode: Int,
    val address: Long,
    val sampleCount: Long,
)

data class DataQualitySummary(
    val sampleCount: Long,
    val reportedSampleCount: Long,
    val lostSampleCount: Long,
    val unwindErrorSamples: Long,
    val unknownSymbolSamples: Long,
    val emptyStackSamples: Long,
    val unknownRecords: Long,
    val unwindErrors: List<UnwindErrorSummary>,
) {
    val lostRate: Double
        get() = if (reportedSampleCount == 0L) 0.0 else lostSampleCount.toDouble() / reportedSampleCount
}
