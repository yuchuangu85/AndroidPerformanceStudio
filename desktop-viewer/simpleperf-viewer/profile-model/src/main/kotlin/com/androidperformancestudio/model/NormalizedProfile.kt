package com.androidperformancestudio.model

enum class ProfileExecutionType {
    NATIVE,
    INTERPRETED_JVM,
    JIT_JVM,
    ART,
}

data class ProfileFrame(
    val virtualAddress: Long,
    val fileId: Int,
    val symbolId: Int,
    val filePath: String,
    val symbolName: String,
    val executionType: ProfileExecutionType,
)

data class ProfileUnwindError(
    val code: String,
    val rawCode: Int,
    val address: Long,
)

data class NormalizedSample(
    val timestampNanos: Long,
    val processId: Int,
    val threadId: Int,
    val threadName: String,
    val eventType: String,
    val eventCount: Long,
    val frames: List<ProfileFrame>,
    val unwindError: ProfileUnwindError?,
)

data class ProfileFile(
    val id: Int,
    val path: String,
    val symbols: List<String>,
    val mangledSymbols: List<String>,
)

data class ProfileThread(
    val processId: Int,
    val threadId: Int,
    val name: String,
)

data class ProfileMetadata(
    val eventTypes: List<String>,
    val appPackageName: String?,
    val appType: String?,
    val androidSdkVersion: String?,
    val androidBuildType: String?,
    val traceOffCpu: Boolean,
)

sealed interface NormalizedProfileRecord {
    data class Sample(
        val value: NormalizedSample,
    ) : NormalizedProfileRecord

    data class Lost(
        val sampleCount: Long,
        val lostCount: Long,
    ) : NormalizedProfileRecord

    data class File(
        val value: ProfileFile,
    ) : NormalizedProfileRecord

    data class Thread(
        val value: ProfileThread,
    ) : NormalizedProfileRecord

    data class Metadata(
        val value: ProfileMetadata,
    ) : NormalizedProfileRecord

    data class ContextSwitch(
        val threadId: Int,
        val timestampNanos: Long,
        val switchedOnCpu: Boolean,
    ) : NormalizedProfileRecord

    data object Unknown : NormalizedProfileRecord
}
