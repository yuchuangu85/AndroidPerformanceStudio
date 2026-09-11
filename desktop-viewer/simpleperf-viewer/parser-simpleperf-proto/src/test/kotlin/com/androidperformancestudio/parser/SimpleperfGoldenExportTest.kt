package com.androidperformancestudio.parser

import com.android.tools.profiler.proto.SimpleperfReport
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.StudioResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exports a golden corpus for the TypeScript rewrite of the SIMPLEPERF reader
 * and normalizer.
 *
 * Records are built with the generated protobuf classes, framed exactly like
 * simpleperf writes them (magic, version, length prefixed records, terminator),
 * and each case is written as the raw bytes plus a JSON digest of what this
 * implementation saw. The TypeScript side parses the same bytes and must
 * reproduce every field.
 *
 * Output directory: APS_GOLDEN_OUT, or the module build/golden.
 */
class SimpleperfGoldenExportTest {
    // Case names are not unique across parsers, so each exporter owns a directory.
    private val outputDirectory: File =
        File(System.getenv("APS_GOLDEN_OUT") ?: "build/golden", "simpleperf").apply { mkdirs() }

    @Test
    fun `exports golden cases with their parse digests`() {
        val cases = listOf(basicCase(), dualClockCase(), lostSamplesCase())
        assertEquals(3, cases.size)
        cases.forEach { case -> write(case) }
    }

    private data class GoldenCase(
        val name: String,
        val bytes: ByteArray,
    )

    private fun basicCase(): GoldenCase =
        GoldenCase(
            name = "basic",
            bytes =
                stream(
                    metaInfo(eventTypes = listOf("cpu-cycles"), appPackageName = "com.example.app"),
                    file(id = 0, path = "/system/lib64/libc.so", symbols = listOf("memcpy", "malloc")),
                    thread(threadId = 42, processId = 7, name = "RenderThread"),
                    sample(
                        time = 1_000L,
                        threadId = 42,
                        eventCount = 1_000L,
                        eventTypeId = 0,
                        callchain = listOf(entry(fileId = 0, symbolId = 1), entry(fileId = 0, symbolId = 0)),
                    ),
                    sample(
                        time = 2_000L,
                        threadId = 42,
                        eventCount = 2_000L,
                        eventTypeId = 0,
                        callchain = listOf(entry(fileId = 0, symbolId = 1)),
                    ),
                ),
        )

    private fun dualClockCase(): GoldenCase =
        GoldenCase(
            name = "dual-clock",
            bytes =
                stream(
                    metaInfo(eventTypes = listOf("cpu-clock", "task-clock"), traceOffCpu = true),
                    file(id = 0, path = "[kernel.kallsyms]", symbols = listOf("schedule")),
                    thread(threadId = 7, processId = 7, name = "main"),
                    sample(
                        time = 5_000L,
                        threadId = 7,
                        eventCount = 10L,
                        eventTypeId = 1,
                        callchain = listOf(entry(fileId = 0, symbolId = 0)),
                    ),
                ),
        )

    private fun lostSamplesCase(): GoldenCase =
        GoldenCase(
            name = "lost-and-unknown",
            bytes =
                stream(
                    metaInfo(eventTypes = listOf("cpu-cycles")),
                    thread(threadId = 3, processId = 3, name = "worker"),
                    // No file record, so the frame resolves to an unknown file and symbol.
                    sample(
                        time = 10L,
                        threadId = 3,
                        eventCount = 5L,
                        eventTypeId = 0,
                        callchain = listOf(entry(fileId = 9, symbolId = -1)),
                    ),
                    lost(sampleCount = 100L, lostCount = 2L),
                ),
        )

    private fun metaInfo(
        eventTypes: List<String>,
        appPackageName: String? = null,
        traceOffCpu: Boolean = false,
    ): SimpleperfReport.Record {
        val meta =
            SimpleperfReport.MetaInfo
                .newBuilder()
                .addAllEventType(eventTypes)
                .setTraceOffcpu(traceOffCpu)
        appPackageName?.let(meta::setAppPackageName)
        return SimpleperfReport.Record.newBuilder().setMetaInfo(meta).build()
    }

    private fun file(id: Int, path: String, symbols: List<String>): SimpleperfReport.Record =
        SimpleperfReport.Record
            .newBuilder()
            .setFile(
                SimpleperfReport.File
                    .newBuilder()
                    .setId(id)
                    .setPath(path)
                    .addAllSymbol(symbols),
            ).build()

    private fun thread(threadId: Int, processId: Int, name: String): SimpleperfReport.Record =
        SimpleperfReport.Record
            .newBuilder()
            .setThread(
                SimpleperfReport.Thread
                    .newBuilder()
                    .setThreadId(threadId)
                    .setProcessId(processId)
                    .setThreadName(name),
            ).build()

    private fun entry(fileId: Int, symbolId: Int): SimpleperfReport.Sample.CallChainEntry =
        SimpleperfReport.Sample.CallChainEntry
            .newBuilder()
            .setVaddrInFile(0x1000L)
            .setFileId(fileId)
            .setSymbolId(symbolId)
            .setExecutionType(SimpleperfReport.Sample.CallChainEntry.ExecutionType.NATIVE_METHOD)
            .build()

    private fun sample(
        time: Long,
        threadId: Int,
        eventCount: Long,
        eventTypeId: Int,
        callchain: List<SimpleperfReport.Sample.CallChainEntry>,
    ): SimpleperfReport.Record =
        SimpleperfReport.Record
            .newBuilder()
            .setSample(
                SimpleperfReport.Sample
                    .newBuilder()
                    .setTime(time)
                    .setThreadId(threadId)
                    .setEventCount(eventCount)
                    .setEventTypeId(eventTypeId)
                    .addAllCallchain(callchain),
            ).build()

    private fun lost(sampleCount: Long, lostCount: Long): SimpleperfReport.Record =
        SimpleperfReport.Record
            .newBuilder()
            .setLost(
                SimpleperfReport.LostSituation
                    .newBuilder()
                    .setSampleCount(sampleCount)
                    .setLostCount(lostCount),
            ).build()

    /** magic, version, then length prefixed records and a zero terminator. */
    private fun stream(vararg records: SimpleperfReport.Record): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("SIMPLEPERF".toByteArray(Charsets.US_ASCII))
        out.write(1)
        out.write(0)
        records.forEach { record ->
            val payload = record.toByteArray()
            repeat(4) { index -> out.write((payload.size ushr (8 * index)) and 0xff) }
            out.write(payload)
        }
        repeat(4) { out.write(0) }
        return out.toByteArray()
    }

    private fun write(case: GoldenCase) {
        val inputFile = case.name + ".simpleperf"
        File(outputDirectory, inputFile).writeBytes(case.bytes)
        File(outputDirectory, case.name + ".json").writeText(digest(case, inputFile))
    }

    private fun digest(case: GoldenCase, inputFile: String): String {
        val normalizer = SimpleperfProfileNormalizer()
        var recordCount = 0L
        var sampleCount = 0
        var lostCount = 0L
        var totalEventCount = 0L
        var eventTypes: List<String> = emptyList()
        var appPackageName: String? = null
        var traceOffCpu = false
        val threadNames = mutableListOf<String>()
        val frameSymbols = mutableListOf<String>()
        val record =
            SimpleperfRecordReader().read(ByteArrayInputStream(case.bytes)) { envelope ->
                recordCount += 1
                when (val normalized = normalizer.normalize(envelope.record)) {
                    is NormalizedProfileRecord.Sample -> {
                        sampleCount += 1
                        totalEventCount += normalized.value.eventCount
                        threadNames += normalized.value.threadName
                        frameSymbols += normalized.value.frames.joinToString(separator = "|") { frame -> frame.symbolName }
                    }
                    is NormalizedProfileRecord.Lost -> lostCount = normalized.lostCount
                    is NormalizedProfileRecord.Metadata -> {
                        eventTypes = normalized.value.eventTypes
                        appPackageName = normalized.value.appPackageName
                        traceOffCpu = normalized.value.traceOffCpu
                    }
                    else -> Unit
                }
            }
        check(record is StudioResult.Success) { "fixture failed to parse" }
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"parser\": \"SIMPLEPERF\",\n")
        builder.append("  \"case\": \"").append(case.name).append("\",\n")
        builder.append("  \"inputFile\": \"").append(inputFile).append("\",\n")
        builder.append("  \"inputSha256\": \"").append(sha256(case.bytes)).append("\",\n")
        builder.append("  \"expectations\": {\n")
        builder.append("    \"version\": 1,\n")
        builder.append("    \"recordCount\": ").append(recordCount).append(",\n")
        builder.append("    \"sampleCount\": ").append(sampleCount).append(",\n")
        builder.append("    \"lostCount\": ").append(lostCount).append(",\n")
        builder.append("    \"totalEventCount\": ").append(totalEventCount).append(",\n")
        builder.append("    \"eventTypes\": ").append(stringArray(eventTypes.sorted())).append(",\n")
        builder.append("    \"appPackageName\": ").append(appPackageName?.let { "\"" + it + "\"" } ?: "null").append(",\n")
        builder.append("    \"traceOffCpu\": ").append(traceOffCpu).append(",\n")
        builder.append("    \"threadNames\": ").append(stringArray(threadNames)).append(",\n")
        builder.append("    \"frameSymbols\": ").append(stringArray(frameSymbols)).append(",\n")
        builder.append("    \"warningCount\": 0\n")
        builder.append("  }\n")
        builder.append("}\n")
        return builder.toString()
    }

    private fun stringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { value -> "\"" + value + "\"" }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}