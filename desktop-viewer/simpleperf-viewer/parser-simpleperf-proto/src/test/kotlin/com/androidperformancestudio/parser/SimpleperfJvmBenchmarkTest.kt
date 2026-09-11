package com.androidperformancestudio.parser

import com.android.tools.profiler.proto.SimpleperfReport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM baseline for the SIMPLEPERF reader and normalizer.
 *
 * Builds the identical synthetic report the TypeScript benchmark uses
 * (electron-viewer/packages/simpleperf-profiler/src/perf.test.ts) and writes
 * the numbers next to the golden corpus, so the Electron CI job can compare the
 * two implementations on one machine.
 *
 * Stages: `read` (framing and protobuf decoding only) and `readAndNormalize`
 * (decoding plus symbol, thread, and event resolution).
 */
class SimpleperfJvmBenchmarkTest {
    private val outputDirectory: File =
        File(System.getenv("APS_GOLDEN_OUT") ?: "build/golden", "simpleperf").apply { mkdirs() }

    @Test
    fun `records the JVM baseline for the same synthetic report`() {
        val bytes = syntheticReport()
        val reader = SimpleperfRecordReader()

        // Warmups first, then measured rounds; the median is reported so one GC
        // pause cannot decide the gate.
        val readRuns = rounds {
            var count = 0L
            val result = reader.read(ByteArrayInputStream(bytes)) { count += 1 }
            check(result is com.androidperformancestudio.model.StudioResult.Success) { "read failed" }
            assertEquals(EXPECTED_RECORDS.toLong(), count)
        }
        val normalizeRuns = rounds {
            val normalizer = SimpleperfProfileNormalizer()
            var samples = 0
            val result =
                reader.read(ByteArrayInputStream(bytes)) { envelope ->
                    if (normalizer.normalize(envelope.record) is com.androidperformancestudio.model.NormalizedProfileRecord.Sample) {
                        samples += 1
                    }
                }
            check(result is com.androidperformancestudio.model.StudioResult.Success) { "read failed" }
            assertEquals(SAMPLE_COUNT, samples)
        }

        val report = StringBuilder()
        report.append("{\n")
        report.append("  \"runtime\": {\n")
        report.append("    \"jvm\": \"").append(System.getProperty("java.version")).append("\",\n")
        report.append("    \"os\": \"").append(System.getProperty("os.name")).append("\"\n")
        report.append("  },\n")
        report.append("  \"input\": {\"bytes\": ").append(bytes.size)
        report.append(", \"files\": ").append(FILE_COUNT)
        report.append(", \"symbolsPerFile\": ").append(SYMBOLS_PER_FILE)
        report.append(", \"threads\": ").append(THREAD_COUNT)
        report.append(", \"samples\": ").append(SAMPLE_COUNT)
        report.append(", \"framesPerSample\": ").append(FRAMES_PER_SAMPLE).append("},\n")
        report.append("  \"measurements\": [\n")
        report.append(measurement("read", readRuns)).append(",\n")
        report.append(measurement("readAndNormalize", normalizeRuns)).append("\n")
        report.append("  ]\n")
        report.append("}\n")
        File(outputDirectory, "benchmark.json").writeText(report.toString())
    }

    private fun measurement(stage: String, runs: List<Double>): String {
        val sorted = runs.sorted()
        val median = sorted[sorted.size / 2]
        return "    {\"stage\": \"" + stage + "\", \"milliseconds\": " + number(median) +
            ", \"runs\": [" + runs.joinToString(separator = ", ") { value -> number(value) } + "]}"
    }

    private fun rounds(body: () -> Unit): List<Double> =
        (0 until WARMUPS + ROUNDS).mapNotNull { round ->
            val start = System.nanoTime()
            body()
            val elapsed = (System.nanoTime() - start) / NANOS_PER_MILLI
            if (round < WARMUPS) null else elapsed
        }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun syntheticReport(): ByteArray {
        val out = ByteArrayOutputStream(BUFFER_BYTES)
        out.write("SIMPLEPERF".toByteArray(Charsets.US_ASCII))
        out.write(1)
        out.write(0)

        fun emit(record: SimpleperfReport.Record) {
            val payload = record.toByteArray()
            repeat(4) { index -> out.write((payload.size ushr (8 * index)) and 0xff) }
            out.write(payload)
        }

        emit(
            SimpleperfReport.Record
                .newBuilder()
                .setMetaInfo(
                    SimpleperfReport.MetaInfo
                        .newBuilder()
                        .addEventType("cpu-cycles")
                        .setAppPackageName("com.example.app"),
                ).build(),
        )

        (0 until FILE_COUNT).forEach { file ->
            val builder = SimpleperfReport.File.newBuilder().setId(file).setPath("/system/lib64/lib" + file + ".so")
            (0 until SYMBOLS_PER_FILE).forEach { symbol -> builder.addSymbol("symbol_" + file + "_" + symbol) }
            emit(SimpleperfReport.Record.newBuilder().setFile(builder).build())
        }
        (0 until THREAD_COUNT).forEach { thread ->
            emit(
                SimpleperfReport.Record
                    .newBuilder()
                    .setThread(
                        SimpleperfReport.Thread
                            .newBuilder()
                            .setThreadId(thread)
                            .setProcessId(1000 + thread)
                            .setThreadName("Thread-" + thread),
                    ).build(),
            )
        }

        val frameBuilder = SimpleperfReport.Sample.CallChainEntry.newBuilder()
        (0 until SAMPLE_COUNT).forEach { index ->
            val sample = SimpleperfReport.Sample.newBuilder()
            sample.time = index.toLong()
            sample.threadId = index % THREAD_COUNT
            sample.eventCount = 1000L
            sample.eventTypeId = 0
            (0 until FRAMES_PER_SAMPLE).forEach { frame ->
                frameBuilder.clear()
                frameBuilder.vaddrInFile = (0x1000 + frame * 8).toLong()
                frameBuilder.fileId = (index + frame) % FILE_COUNT
                frameBuilder.symbolId = (index + frame) % SYMBOLS_PER_FILE
                frameBuilder.executionType = SimpleperfReport.Sample.CallChainEntry.ExecutionType.NATIVE_METHOD
                sample.addCallchain(frameBuilder)
            }
            emit(SimpleperfReport.Record.newBuilder().setSample(sample).build())
        }
        repeat(4) { out.write(0) }
        return out.toByteArray()
    }

    private companion object {
        const val FILE_COUNT = 40
        const val SYMBOLS_PER_FILE = 120
        const val THREAD_COUNT = 64
        const val SAMPLE_COUNT = 100_000
        const val FRAMES_PER_SAMPLE = 12
        const val EXPECTED_RECORDS = 1 + FILE_COUNT + THREAD_COUNT + SAMPLE_COUNT
        // Matched to the TypeScript benchmark: same warm-ups, same sample count.
        const val WARMUPS = 3
        const val ROUNDS = 5
        const val NANOS_PER_MILLI = 1_000_000.0
        const val BUFFER_BYTES = 16 * 1024 * 1024
    }
}
