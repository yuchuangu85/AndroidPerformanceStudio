package com.androidperformancestudio.parser

import com.android.tools.profiler.proto.SimpleperfReport
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileUnwindError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SimpleperfProfileNormalizerTest {
    @Test
    fun `resolves sample thread event file symbol and callchain`() {
        val normalizer = SimpleperfProfileNormalizer()
        normalizer.normalize(metaInfo("cpu-clock", "cpu-cycles"))
        normalizer.normalize(file(id = 7, path = "/system/lib64/libui.so", symbols = listOf("draw", "render")))
        normalizer.normalize(thread(pid = 100, tid = 101, name = "RenderThread"))

        val normalized =
            normalizer.normalize(
                sample(
                    time = 1234,
                    tid = 101,
                    eventTypeId = 1,
                    eventCount = 50,
                    frame(fileId = 7, symbolId = 1, vaddr = 0x20),
                    frame(fileId = 7, symbolId = 0, vaddr = 0x10),
                ),
            )

        val sample = assertIs<NormalizedProfileRecord.Sample>(normalized).value
        assertEquals(1234L, sample.timestampNanos)
        assertEquals(100, sample.processId)
        assertEquals(101, sample.threadId)
        assertEquals("RenderThread", sample.threadName)
        assertEquals("cpu-cycles", sample.eventType)
        assertEquals(50L, sample.eventCount)
        assertEquals(listOf("render", "draw"), sample.frames.map { it.symbolName })
        assertEquals(ProfileExecutionType.NATIVE, sample.frames.first().executionType)
    }

    @Test
    fun `preserves lost samples and unwind quality evidence`() {
        val normalizer = SimpleperfProfileNormalizer()
        normalizer.normalize(metaInfo("cpu-clock"))
        normalizer.normalize(thread(pid = 200, tid = 201, name = "worker"))

        val sample =
            normalizer.normalize(
                SimpleperfReport.Record
                    .newBuilder()
                    .setSample(
                        SimpleperfReport.Sample
                            .newBuilder()
                            .setTime(900)
                            .setThreadId(201)
                            .setEventTypeId(0)
                            .setEventCount(5)
                            .setUnwindingResult(
                                SimpleperfReport.Sample.UnwindingResult
                                    .newBuilder()
                                    .setErrorCode(
                                        SimpleperfReport.Sample.UnwindingResult.ErrorCode.ERROR_NOT_ENOUGH_STACK,
                                    ).setRawErrorCode(12)
                                    .setErrorAddr(0xabcd),
                            ),
                    ).build(),
            )
        val lost =
            normalizer.normalize(
                SimpleperfReport.Record
                    .newBuilder()
                    .setLost(
                        SimpleperfReport.LostSituation
                            .newBuilder()
                            .setSampleCount(100)
                            .setLostCount(4),
                    ).build(),
            )

        val normalizedSample = assertIs<NormalizedProfileRecord.Sample>(sample).value
        assertEquals(
            ProfileUnwindError("ERROR_NOT_ENOUGH_STACK", rawCode = 12, address = 0xabcd),
            normalizedSample.unwindError,
        )
        assertEquals(
            NormalizedProfileRecord.Lost(sampleCount = 100, lostCount = 4),
            lost,
        )
    }

    @Test
    fun `uses explicit unknown placeholders for unresolved references`() {
        val normalized =
            SimpleperfProfileNormalizer().normalize(
                sample(
                    time = 1,
                    tid = 999,
                    eventTypeId = 3,
                    eventCount = 1,
                    frame(fileId = 42, symbolId = -1, vaddr = 0x40),
                ),
            )

        val sample = assertIs<NormalizedProfileRecord.Sample>(normalized).value
        assertEquals(0, sample.processId)
        assertEquals("<unknown-thread:999>", sample.threadName)
        assertEquals("<unknown-event:3>", sample.eventType)
        assertEquals("<unknown-symbol>", sample.frames.single().symbolName)
        assertEquals("<unknown-file:42>", sample.frames.single().filePath)
    }

    @Test
    fun `classifies kernel and unresolved file mappings truthfully`() {
        val normalizer = SimpleperfProfileNormalizer()
        normalizer.normalize(file(id = 7, path = "[kernel.kallsyms]", symbols = listOf("schedule")))

        val normalized =
            normalizer.normalize(
                sample(
                    time = 1,
                    tid = 101,
                    eventTypeId = 0,
                    eventCount = 1,
                    frame(fileId = 7, symbolId = 0, vaddr = 0x10),
                    frame(fileId = 42, symbolId = -1, vaddr = 0x20),
                ),
            )

        val frames = assertIs<NormalizedProfileRecord.Sample>(normalized).value.frames
        assertEquals(ProfileExecutionType.KERNEL, frames[0].executionType)
        assertEquals(ProfileExecutionType.UNKNOWN, frames[1].executionType)
    }

    private fun metaInfo(vararg events: String): SimpleperfReport.Record =
        SimpleperfReport.Record
            .newBuilder()
            .setMetaInfo(SimpleperfReport.MetaInfo.newBuilder().addAllEventType(events.toList()))
            .build()

    private fun file(
        id: Int,
        path: String,
        symbols: List<String>,
    ): SimpleperfReport.Record =
        SimpleperfReport.Record
            .newBuilder()
            .setFile(
                SimpleperfReport.File
                    .newBuilder()
                    .setId(id)
                    .setPath(path)
                    .addAllSymbol(symbols),
            ).build()

    private fun thread(
        pid: Int,
        tid: Int,
        name: String,
    ): SimpleperfReport.Record =
        SimpleperfReport.Record
            .newBuilder()
            .setThread(
                SimpleperfReport.Thread
                    .newBuilder()
                    .setProcessId(pid)
                    .setThreadId(tid)
                    .setThreadName(name),
            ).build()

    private fun sample(
        time: Long,
        tid: Int,
        eventTypeId: Int,
        eventCount: Long,
        vararg frames: SimpleperfReport.Sample.CallChainEntry,
    ): SimpleperfReport.Record =
        SimpleperfReport.Record
            .newBuilder()
            .setSample(
                SimpleperfReport.Sample
                    .newBuilder()
                    .setTime(time)
                    .setThreadId(tid)
                    .setEventTypeId(eventTypeId)
                    .setEventCount(eventCount)
                    .addAllCallchain(frames.toList()),
            ).build()

    private fun frame(
        fileId: Int,
        symbolId: Int,
        vaddr: Long,
    ): SimpleperfReport.Sample.CallChainEntry =
        SimpleperfReport.Sample.CallChainEntry
            .newBuilder()
            .setFileId(fileId)
            .setSymbolId(symbolId)
            .setVaddrInFile(vaddr)
            .build()
}
