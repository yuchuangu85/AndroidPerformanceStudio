package com.androidperformancestudio.arttrace

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * JVM baseline for the ART method-trace parser and projector.
 *
 * Builds the identical synthetic trace the TypeScript benchmark uses
 * (electron-viewer/packages/art-trace/src/perf.test.ts): 2000 methods, 64
 * threads, and 1600 events per thread in the streaming version 5 layout.
 * Stages: `parse` and `parseAndProject`.
 */
class ArtTraceJvmBenchmarkTest {
    private val outputDirectory: File =
        File(System.getenv("APS_GOLDEN_OUT") ?: "build/golden", "art-trace").apply { mkdirs() }

    @Test
    fun `records the JVM baseline for the same synthetic trace`() {
        val bytes = syntheticTrace()

        val parseRuns = rounds {
            val parsed = assertIs<ArtTraceParseResult.Success>(ArtTraceParser.parse(bytes))
            events = parsed.analysis.events.size
        }
        if (events != EVENT_COUNT) error("expected " + EVENT_COUNT + " events, saw " + events)

        val projectRuns = rounds {
            val parsed = assertIs<ArtTraceParseResult.Success>(ArtTraceParser.parse(bytes))
            stacks = ArtTraceCallStackProjector.toCallStackTable(parsed.analysis).stacks.size
        }

        val report = StringBuilder()
        report.append("{\n")
        report.append("  \"runtime\": {\n")
        report.append("    \"jvm\": \"").append(System.getProperty("java.version")).append("\",\n")
        report.append("    \"os\": \"").append(System.getProperty("os.name")).append("\"\n")
        report.append("  },\n")
        report.append("  \"input\": {\"bytes\": ").append(bytes.size)
        report.append(", \"methods\": ").append(METHOD_COUNT)
        report.append(", \"threads\": ").append(THREAD_COUNT)
        report.append(", \"events\": ").append(EVENT_COUNT).append("},\n")
        report.append("  \"measurements\": [\n")
        report.append(measurement("parse", parseRuns)).append(",\n")
        report.append(measurement("parseAndProject", projectRuns)).append("\n")
        report.append("  ]\n")
        report.append("}\n")
        File(outputDirectory, "benchmark.json").writeText(report.toString())
    }

    private var events: Int = 0

    private var stacks: Int = 0

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

    private fun syntheticTrace(): ByteArray {
        val out = ByteArrayOutputStream(BUFFER_BYTES)
        writeHeader(out)
        (0 until THREAD_COUNT).forEach { thread ->
            out.write(threadInfoPacket(thread, "Thread-" + thread))
        }
        (0 until METHOD_COUNT).forEach { method ->
            out.write(
                methodInfoPacket(
                    method.toLong(),
                    "Lcom/example/Class" + method + "\tmethod" + method + "\t()V\tClass" + method + ".java",
                ),
            )
        }
        (0 until THREAD_COUNT).forEach { thread ->
            val methodIds = IntArray(4) { index -> (thread * 7 + index * 13) % METHOD_COUNT }
            out.write(entryBlock(thread, methodIds))
        }
        out.write(TraceWriter().u8(3).u16(0).build())
        return out.toByteArray()
    }

    private fun writeHeader(out: ByteArrayOutputStream) {
        out.write(TraceWriter().u32(TRACE_MAGIC).u16(5).u64(1_000_000L).build())
        repeat(18) { out.write(0) }
    }

    private fun threadInfoPacket(threadId: Int, name: String): ByteArray {
        val encoded = name.toByteArray(Charsets.UTF_8)
        return TraceWriter().u8(0).u32(threadId.toLong()).u16(encoded.size).bytes(encoded).build()
    }

    private fun methodInfoPacket(methodId: Long, info: String): ByteArray {
        val encoded = info.toByteArray(Charsets.UTF_8)
        return TraceWriter().u8(1).u64(methodId).u16(encoded.size).bytes(encoded).build()
    }

    /** Two enters then two exits, delta encoded like ART writes them. */
    private fun entryBlock(threadId: Int, methodIds: IntArray): ByteArray {
        val payload = TraceWriter()
        var previous = 0L
        (0 until EVENTS_PER_THREAD).forEach { index ->
            val step = index % 4
            val action = if (step < 2) 0 else 1
            val methodIndex = if (step < 2) step else 3 - step
            val word = methodIds[methodIndex].toLong() * 4 + action
            payload.sleb(word - previous)
            previous = word
            payload.uleb(1000L)
            payload.uleb(500L)
        }
        val body = payload.build()
        return TraceWriter().u8(2).u32(threadId.toLong()).u24(EVENTS_PER_THREAD).u32(body.size.toLong()).bytes(body).build()
    }

    private class TraceWriter {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int): TraceWriter = apply { out.write(value and 0xff) }

        fun u16(value: Int): TraceWriter =
            apply {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }

        fun u24(value: Int): TraceWriter =
            apply {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
                out.write((value ushr 16) and 0xff)
            }

        fun u32(value: Long): TraceWriter =
            apply {
                repeat(4) { index -> out.write(((value ushr (8 * index)) and 0xff).toInt()) }
            }

        fun u64(value: Long): TraceWriter =
            apply {
                repeat(8) { index -> out.write(((value ushr (8 * index)) and 0xff).toInt()) }
            }

        fun uleb(value: Long): TraceWriter =
            apply {
                var remaining = value
                while (true) {
                    val byte = (remaining and 0x7f).toInt()
                    remaining = remaining ushr 7
                    if (remaining == 0L) {
                        out.write(byte)
                        break
                    }
                    out.write(byte or 0x80)
                }
            }

        fun sleb(value: Long): TraceWriter =
            apply {
                var remaining = value
                while (true) {
                    var byte = (remaining and 0x7f).toInt()
                    remaining = remaining shr 7
                    val signBitSet = byte and 0x40 != 0
                    if ((remaining == 0L && !signBitSet) || (remaining == -1L && signBitSet)) {
                        out.write(byte)
                        break
                    }
                    byte = byte or 0x80
                    out.write(byte)
                }
            }

        fun bytes(value: ByteArray): TraceWriter = apply { out.write(value) }

        fun build(): ByteArray = out.toByteArray()
    }

    private companion object {
        const val TRACE_MAGIC = 0x574f4c53L
        const val METHOD_COUNT = 2000
        const val THREAD_COUNT = 64
        const val EVENTS_PER_THREAD = 1600
        const val EVENT_COUNT = THREAD_COUNT * EVENTS_PER_THREAD
        // Matched to the TypeScript benchmark: same warm-ups, same sample count.
        // A median of three is a single sample and moves with the runner.
        const val WARMUPS = 3
        const val ROUNDS = 5
        const val NANOS_PER_MILLI = 1_000_000.0
        const val BUFFER_BYTES = 8 * 1024 * 1024
    }
}
