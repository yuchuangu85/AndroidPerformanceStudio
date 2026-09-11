@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.PrimitiveType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM baseline for the HPROF pipeline, written as JSON next to the golden corpus.
 *
 * The TypeScript rewrite measures the identical synthetic heap
 * (electron-viewer/packages/memory-profiler/src/perf.test.ts), so the two numbers
 * can be compared stage by stage. Stages:
 *
 * - parseHprof: the whole dump, header to heap end.
 * - classSumAggregation: grouping instances by class and summing shallow sizes,
 *   which is the work MemoryHistogramAnalyzer does on top of the parse.
 *
 * Each stage runs warmups first and then several measured rounds; the median is
 * reported so a single JIT or GC pause cannot decide the gate.
 */
class HprofJvmBenchmarkTest {
    private val parser = HprofParser()

    // Next to the HPROF corpus, so the workflow needs one output directory.
    private val outputDirectory: File =
        File(System.getenv("APS_GOLDEN_OUT") ?: "build/golden", "hprof").apply { mkdirs() }

    @Test
    fun `records the JVM baseline for the same synthetic heap`() {
        val cases = listOf(measure("chain", wrapChains = false), measure("cyclic", wrapChains = true))
        assertEquals(2, cases.size)
        File(outputDirectory, "benchmark.json").writeText(report(cases))

        // A gate that only exists to catch a catastrophic regression here; the real
        // comparison against TypeScript happens in the Electron CI job.
        val parse = cases.first().measurements.first { it.stage == "parseHprof" }
        kotlin.test.assertTrue(parse.milliseconds > 0.0)
    }

    private data class Measurement(val stage: String, val milliseconds: Double, val runs: List<Double>)

    private data class ShapeCase(
        val shape: String,
        val heap: SyntheticHeap,
        val measurements: List<Measurement>,
    )

    private fun measure(shape: String, wrapChains: Boolean): ShapeCase {
        val heap = syntheticHeap(wrapChains = wrapChains)
        val parseRuns = List(WARMUPS + ROUNDS) { round ->
            val start = System.nanoTime()
            parsed = parser.parse(heap.bytes)
            val elapsed = System.nanoTime() - start
            if (round < WARMUPS) -1.0 else elapsed / NANOS_PER_MILLI
        }.filter { it >= 0.0 }
        val heapDump = parsed

        val aggregationRuns = List(WARMUPS + ROUNDS) { round ->
            val start = System.nanoTime()
            // Repeated so the stage is long enough to compare; the TypeScript
            // benchmark repeats it the same number of times.
            repeat(AGGREGATION_ITERATIONS) {
                val totals = HashMap<Long, Long>()
                heapDump.instances.forEach { instance ->
                    totals[instance.classObjectId] = (totals[instance.classObjectId] ?: 0L) + instance.shallowSize
                }
                sink = totals.size
            }
            val elapsed = System.nanoTime() - start
            if (round < WARMUPS) -1.0 else elapsed / NANOS_PER_MILLI
        }.filter { it >= 0.0 }

        assertEquals(heap.instanceCount + heap.garbageCount, heapDump.instances.size)
        return ShapeCase(
            shape = shape,
            heap = heap,
            measurements =
                listOf(
                    Measurement("parseHprof", median(parseRuns), parseRuns),
                    Measurement("classSumAggregation", median(aggregationRuns), aggregationRuns),
                ),
        )
    }

    private var parsed: HeapDump = HeapDump()

    private var sink: Int = 0

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun report(cases: List<ShapeCase>): String {
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"runtime\": {\n")
        builder.append("    \"jvm\": \"").append(System.getProperty("java.version")).append("\",\n")
        builder.append("    \"os\": \"").append(System.getProperty("os.name")).append("\"\n")
        builder.append("  },\n")
        builder.append("  \"cases\": [\n")
        cases.forEachIndexed { index, case ->
            builder.append("    {\n")
            builder.append("      \"shape\": \"").append(case.shape).append("\",\n")
            builder.append("      \"heap\": {")
            builder.append("\"dumpBytes\": ").append(case.heap.bytes.size)
            builder.append(", \"classes\": ").append(case.heap.classCount)
            builder.append(", \"instances\": ").append(case.heap.instanceCount)
            builder.append(", \"unreachableObjects\": ").append(case.heap.garbageCount)
            builder.append(", \"objectArrays\": ").append(case.heap.objectArrays)
            builder.append(", \"primitiveArrays\": ").append(case.heap.primitiveArrays)
            builder.append(", \"roots\": ").append(case.heap.classCount).append("},\n")
            builder.append("      \"measurements\": [\n")
            case.measurements.forEachIndexed { measurementIndex, measurement ->
                builder.append("        {\"stage\": \"").append(measurement.stage).append("\", ")
                builder.append("\"milliseconds\": ").append(number(measurement.milliseconds)).append(", ")
                builder.append("\"runs\": [")
                builder.append(measurement.runs.joinToString(separator = ", ") { value -> number(value) })
                builder.append("]}")
                builder.append(if (measurementIndex == case.measurements.lastIndex) "\n" else ",\n")
            }
            builder.append("      ]\n")
            builder.append("    }")
            builder.append(if (index == cases.lastIndex) "\n" else ",\n")
        }
        builder.append("  ]\n")
        builder.append("}\n")
        return builder.toString()
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private data class SyntheticHeap(
        val bytes: ByteArray,
        val classCount: Int,
        val instanceCount: Int,
        val garbageCount: Int,
        val objectArrays: Int,
        val primitiveArrays: Int,
    )

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun u1(value: Int): Writer = apply { out.write(value and 0xff) }

        fun u2(value: Int): Writer = apply { out.write((value ushr 8) and 0xff); out.write(value and 0xff) }

        fun u4(value: Int): Writer =
            apply {
                out.write((value ushr 24) and 0xff)
                out.write((value ushr 16) and 0xff)
                out.write((value ushr 8) and 0xff)
                out.write(value and 0xff)
            }

        fun id(value: Long): Writer = u4(value.toInt())

        fun raw(value: ByteArray): Writer = apply { out.write(value) }

        fun utf8(value: String): Writer = apply { out.write(value.toByteArray()) }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun record(tag: Int, payload: ByteArray): ByteArray {
        val head = Writer().u1(tag).u4(0).u4(payload.size).bytes()
        return head + payload
    }

    private fun syntheticHeap(wrapChains: Boolean): SyntheticHeap {
        val classCount = CLASS_COUNT
        val instancesPerClass = INSTANCES_PER_CLASS
        val garbagePerClass = (instancesPerClass / 100).coerceAtLeast(1)
        val instanceSize = 2 * ID_SIZE + 4
        val records = ByteArrayOutputStream()

        records.write("JAVA PROFILE 1.0.3".toByteArray())
        records.write(0)
        records.write(Writer().u4(ID_SIZE).u4(0).u4(0).bytes())

        records.write(record(0x01, Writer().id(HEAP_NAME_ID).utf8("app heap").bytes()))
        (0 until classCount).forEach { index ->
            records.write(record(0x01, Writer().id(FIRST_CLASS_NAME_ID + index).utf8("com.example.Class" + index).bytes()))
        }
        records.write(record(0x01, Writer().id(FIELD_NEXT_ID).utf8("next").bytes()))
        records.write(record(0x01, Writer().id(FIELD_SKIP_ID).utf8("skip").bytes()))
        records.write(record(0x01, Writer().id(FIELD_COUNT_ID).utf8("count").bytes()))

        (0 until classCount).forEach { index ->
            records.write(
                record(0x02, Writer().u4(index).id(classIdOf(index)).u4(0).id(FIRST_CLASS_NAME_ID + index).bytes()),
            )
        }

        val segment = Writer()
        (0 until classCount).forEach { index ->
            segment.u1(0x20).id(classIdOf(index)).u4(0)
            segment.id(0).id(0).id(0).id(0).id(0).id(0)
            segment.u4(instanceSize).u2(0).u2(0).u2(3)
            segment.id(FIELD_NEXT_ID).u1(PrimitiveType.OBJECT.hprofType)
            segment.id(FIELD_SKIP_ID).u1(PrimitiveType.OBJECT.hprofType)
            segment.id(FIELD_COUNT_ID).u1(PrimitiveType.INT.hprofType)
        }

        (0 until classCount).forEach { index ->
            segment.u1(0x01).id(instanceIdOf(index, 0)).id(0x700000L + index)
        }

        (0 until classCount).forEach { classIndex ->
            (0 until instancesPerClass).forEach { instanceIndex ->
                segment.u1(0x21).id(instanceIdOf(classIndex, instanceIndex)).u4(0).id(classIdOf(classIndex))
                segment.u4(instanceSize)
                val next =
                    if (wrapChains || instanceIndex + 1 < instancesPerClass) {
                        instanceIdOf(classIndex, (instanceIndex + 1) % instancesPerClass)
                    } else {
                        0L
                    }
                val skip =
                    if (wrapChains || instanceIndex + 7 < instancesPerClass) {
                        instanceIdOf(classIndex, (instanceIndex + 7) % instancesPerClass)
                    } else {
                        0L
                    }
                segment.id(next).id(skip).u4(instanceIndex)
            }
            (0 until garbagePerClass).forEach { garbage ->
                segment.u1(0x21).id(instanceIdOf(classIndex, instancesPerClass + garbage)).u4(0).id(classIdOf(classIndex))
                segment.u4(instanceSize).id(0).id(0).u4(0)
            }
        }

        var arrayId = 0x900000L
        (0 until OBJECT_ARRAYS).forEach { index ->
            val elements = 16
            segment.u1(0x22).id(arrayId).u4(0).u4(elements).id(classIdOf(index % classCount))
            (0 until elements).forEach { element ->
                segment.id(instanceIdOf(index % classCount, element % instancesPerClass))
            }
            arrayId += 1
        }
        (0 until PRIMITIVE_ARRAYS).forEach { index ->
            val elements = 256
            segment.u1(0x23).id(arrayId).u4(0).u4(elements).u1(PrimitiveType.INT.hprofType)
            segment.raw(ByteArray(elements * PrimitiveType.INT.byteWidth))
            arrayId += 1
        }

        records.write(record(0x1c, segment.bytes()))
        records.write(record(0x2c, ByteArray(0)))

        return SyntheticHeap(
            bytes = records.toByteArray(),
            classCount = classCount,
            instanceCount = classCount * instancesPerClass,
            garbageCount = classCount * garbagePerClass,
            objectArrays = OBJECT_ARRAYS,
            primitiveArrays = PRIMITIVE_ARRAYS,
        )
    }

    private fun classIdOf(index: Int): Long = 0x1000L + index

    private fun instanceIdOf(classIndex: Int, instanceIndex: Int): Long =
        0x100000L + classIndex.toLong() * (INSTANCES_PER_CLASS + (INSTANCES_PER_CLASS / 100).coerceAtLeast(1)) + instanceIndex

    private companion object {
        const val ID_SIZE = 4
        const val CLASS_COUNT = 100
        const val INSTANCES_PER_CLASS = 2000
        const val OBJECT_ARRAYS = 1000
        const val PRIMITIVE_ARRAYS = 2000
        const val WARMUPS = 3
        const val AGGREGATION_ITERATIONS = 20
        const val ROUNDS = 5
        const val NANOS_PER_MILLI = 1_000_000.0
        const val HEAP_NAME_ID = 1L
        const val FIRST_CLASS_NAME_ID = 2L
        const val FIELD_NEXT_ID = 0x500L
        const val FIELD_SKIP_ID = 0x501L
        const val FIELD_COUNT_ID = 0x502L
    }
}
