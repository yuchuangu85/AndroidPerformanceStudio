package com.androidperformancestudio.parser

import com.android.tools.profiler.proto.SimpleperfReport
import com.android.tools.profiler.proto.SimpleperfReport.Sample.CallChainEntry.ExecutionType
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFile
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileThread
import com.androidperformancestudio.model.ProfileUnwindError

class SimpleperfProfileNormalizer {
    private val files = mutableMapOf<Int, ProfileFile>()
    private val threads = mutableMapOf<Int, ProfileThread>()
    private var eventTypes: List<String> = emptyList()

    fun normalize(record: SimpleperfReport.Record): NormalizedProfileRecord =
        when (record.recordDataCase) {
            SimpleperfReport.Record.RecordDataCase.SAMPLE -> normalizeSample(record.sample)
            SimpleperfReport.Record.RecordDataCase.LOST ->
                NormalizedProfileRecord.Lost(record.lost.sampleCount, record.lost.lostCount)
            SimpleperfReport.Record.RecordDataCase.FILE -> normalizeFile(record.file)
            SimpleperfReport.Record.RecordDataCase.THREAD -> normalizeThread(record.thread)
            SimpleperfReport.Record.RecordDataCase.META_INFO -> normalizeMetadata(record.metaInfo)
            SimpleperfReport.Record.RecordDataCase.CONTEXT_SWITCH ->
                NormalizedProfileRecord.ContextSwitch(
                    threadId = record.contextSwitch.threadId,
                    timestampNanos = record.contextSwitch.time,
                    switchedOnCpu = record.contextSwitch.switchOn,
                )
            SimpleperfReport.Record.RecordDataCase.RECORDDATA_NOT_SET -> NormalizedProfileRecord.Unknown
        }

    private fun normalizeFile(file: SimpleperfReport.File): NormalizedProfileRecord.File {
        val normalized =
            ProfileFile(
                id = file.id,
                path = file.path,
                symbols = file.symbolList,
                mangledSymbols = file.mangledSymbolList,
            )
        files[file.id] = normalized
        return NormalizedProfileRecord.File(normalized)
    }

    private fun normalizeThread(thread: SimpleperfReport.Thread): NormalizedProfileRecord.Thread {
        val normalized = ProfileThread(thread.processId, thread.threadId, thread.threadName)
        threads[thread.threadId] = normalized
        return NormalizedProfileRecord.Thread(normalized)
    }

    private fun normalizeMetadata(metaInfo: SimpleperfReport.MetaInfo): NormalizedProfileRecord.Metadata {
        eventTypes = metaInfo.eventTypeList
        return NormalizedProfileRecord.Metadata(
            ProfileMetadata(
                eventTypes = eventTypes,
                appPackageName = metaInfo.appPackageName.takeIf { metaInfo.hasAppPackageName() },
                appType = metaInfo.appType.takeIf { metaInfo.hasAppType() },
                androidSdkVersion = metaInfo.androidSdkVersion.takeIf { metaInfo.hasAndroidSdkVersion() },
                androidBuildType = metaInfo.androidBuildType.takeIf { metaInfo.hasAndroidBuildType() },
                traceOffCpu = metaInfo.traceOffcpu,
            ),
        )
    }

    private fun normalizeSample(sample: SimpleperfReport.Sample): NormalizedProfileRecord.Sample {
        val thread = threads[sample.threadId]
        return NormalizedProfileRecord.Sample(
            NormalizedSample(
                timestampNanos = sample.time,
                processId = thread?.processId ?: UNKNOWN_PROCESS_ID,
                threadId = sample.threadId,
                threadName = thread?.name ?: "<unknown-thread:${sample.threadId}>",
                eventType = eventTypes.getOrNull(sample.eventTypeId) ?: "<unknown-event:${sample.eventTypeId}>",
                eventCount = sample.eventCount,
                frames = sample.callchainList.map(::normalizeFrame),
                unwindError = sample.unwindError(),
            ),
        )
    }

    private fun normalizeFrame(entry: SimpleperfReport.Sample.CallChainEntry): ProfileFrame {
        val file = files[entry.fileId]
        val symbol =
            file
                ?.symbols
                ?.getOrNull(entry.symbolId)
                ?: UNKNOWN_SYMBOL
        return ProfileFrame(
            virtualAddress = entry.vaddrInFile,
            fileId = entry.fileId,
            symbolId = entry.symbolId,
            filePath = file?.path ?: "<unknown-file:${entry.fileId}>",
            symbolName = symbol,
            executionType = entry.executionType.toProfileExecutionType(),
        )
    }

    companion object {
        private const val UNKNOWN_PROCESS_ID = 0
        private const val UNKNOWN_SYMBOL = "<unknown-symbol>"
    }
}

private fun SimpleperfReport.Sample.unwindError(): ProfileUnwindError? =
    if (hasUnwindingResult()) {
        ProfileUnwindError(
            code = unwindingResult.errorCode.name,
            rawCode = unwindingResult.rawErrorCode,
            address = unwindingResult.errorAddr,
        )
    } else {
        null
    }

private fun ExecutionType.toProfileExecutionType(): ProfileExecutionType =
    when (this) {
        ExecutionType.NATIVE_METHOD -> ProfileExecutionType.NATIVE
        ExecutionType.INTERPRETED_JVM_METHOD -> ProfileExecutionType.INTERPRETED_JVM
        ExecutionType.JIT_JVM_METHOD -> ProfileExecutionType.JIT_JVM
        ExecutionType.ART_METHOD -> ProfileExecutionType.ART
    }
