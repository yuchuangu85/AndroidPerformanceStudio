@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.PrimitiveType
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exports a golden corpus for the TypeScript rewrite.
 *
 * Each case is written twice: the raw .hprof bytes the fixture builder produced,
 * and a JSON digest of what this parser saw in them. The TypeScript side
 * (electron-viewer/packages/memory-profiler/src/golden-corpus.test.ts) parses the
 * same bytes and must reproduce every field, so the two implementations check each
 * other rather than a hand-written expectation.
 *
 * Output directory: APS_GOLDEN_OUT, or the module build/golden.
 */
class HprofGoldenExportTest {
    private val parser = HprofParser()

    private val outputDirectory: File =
        File(System.getenv("APS_GOLDEN_OUT") ?: "build/golden").apply { mkdirs() }

    @Test
    fun `exports golden cases with their parse digests`() {
        val cases = listOf(basicCase(), eightByteIdCase(), androidExtensionsCase(), nullRootCase())
        assertEquals(4, cases.size)
        cases.forEach { case -> write(case) }
        assertTrue(File(outputDirectory, "basic.json").isFile)
    }

    private data class GoldenCase(
        val name: String,
        val bytes: ByteArray,
    )

    private fun basicCase(): GoldenCase =
        GoldenCase(
            name = "basic",
            bytes =
                HprofFixtureBuilder(idSize = 4)
                    .string(1, "com.example.Golden")
                    .string(2, "next")
                    .loadClass(0x100, 1)
                    .heapDump(
                        HprofFixtureBuilder(idSize = 4)
                            .classDump(
                                classId = 0x100,
                                instanceSize = 4,
                                instanceFields = listOf(2L to PrimitiveType.OBJECT),
                            ),
                        HprofFixtureBuilder(idSize = 4).instanceDump(0x200, 0x100, HprofFixtureBuilder().objectValue(0)),
                        HprofFixtureBuilder(idSize = 4).primitiveArrayDump(0x300, PrimitiveType.INT, 4),
                    ).heapDumpEnd()
                    .build(),
        )

    private fun eightByteIdCase(): GoldenCase =
        GoldenCase(
            name = "eight-byte-ids",
            bytes =
                HprofFixtureBuilder(idSize = 8)
                    .string(1, "com.example.Wide")
                    .loadClass(0x1_0000_0000, 1)
                    .heapDump(
                        HprofFixtureBuilder(idSize = 8)
                            .classDump(
                                classId = 0x1_0000_0000,
                                instanceSize = 12,
                                instanceFields = listOf(2L to PrimitiveType.LONG),
                            ),
                        HprofFixtureBuilder(idSize = 8)
                            .instanceDump(0x2_0000_0000, 0x1_0000_0000, ByteArray(12)),
                        HprofFixtureBuilder(idSize = 8)
                            .objectArrayDump(0x3_0000_0000, 0x1_0000_0000, listOf(0x2_0000_0000)),
                    ).heapDumpEnd()
                    .build(),
        )

    private fun androidExtensionsCase(): GoldenCase =
        GoldenCase(
            name = "android-extensions",
            bytes =
                HprofFixtureBuilder(idSize = 4)
                    .string(1, "com.example.AndroidObject")
                    .string(3, "app")
                    .loadClass(2, 1)
                    .heapDump(
                        HprofFixtureBuilder(idSize = 4).androidHeapDumpInfo(heapId = 1, heapNameStringId = 3),
                        HprofFixtureBuilder(idSize = 4).androidRoot(0x89, 100),
                        HprofFixtureBuilder(idSize = 4).androidRoot(0x8a, 101),
                        HprofFixtureBuilder(idSize = 4).androidJniMonitorRoot(106),
                        HprofFixtureBuilder(idSize = 4).classDump(classId = 2, instanceSize = 24),
                        HprofFixtureBuilder(idSize = 4).instanceDump(4, 2),
                        HprofFixtureBuilder(idSize = 4).androidPrimitiveArrayNoData(5, PrimitiveType.BYTE, 10),
                    ).heapDumpEnd()
                    .build(),
        )

    private fun nullRootCase(): GoldenCase =
        GoldenCase(
            name = "null-root",
            bytes =
                HprofFixtureBuilder(idSize = 4)
                    .string(1, "com.example.Rooted")
                    .loadClass(0x10, 1)
                    .heapDump(
                        HprofFixtureBuilder(idSize = 4).classDump(classId = 0x10, instanceSize = 4),
                        // A zero object id is not a root; the reference implementation drops it.
                        // Sticky-class roots carry a single id, so the record stays well formed.
                        HprofFixtureBuilder(idSize = 4).androidRoot(0x05, 0),
                        HprofFixtureBuilder(idSize = 4).androidRoot(0x05, 0x20),
                        HprofFixtureBuilder(idSize = 4).instanceDump(0x20, 0x10),
                    ).heapDumpEnd()
                    .build(),
        )

    private fun write(case: GoldenCase) {
        val inputFile = case.name + ".hprof"
        File(outputDirectory, inputFile).writeBytes(case.bytes)
        File(outputDirectory, case.name + ".json").writeText(digest(case, inputFile))
    }

    private fun digest(case: GoldenCase, inputFile: String): String {
        val heap = parser.parse(case.bytes)
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"parser\": \"HPROF\",\n")
        builder.append("  \"case\": \"").append(case.name).append("\",\n")
        builder.append("  \"inputFile\": \"").append(inputFile).append("\",\n")
        builder.append("  \"inputSha256\": \"").append(sha256(case.bytes)).append("\",\n")
        builder.append("  \"expectations\": {\n")
        builder.append("    \"format\": \"").append(heap.format).append("\",\n")
        builder.append("    \"idSize\": ").append(heap.idSize).append(",\n")
        builder.append("    \"classNames\": ").append(stringArray(heap.classes.map { it.name }.sorted())).append(",\n")
        builder.append("    \"instanceCount\": ").append(heap.instances.size).append(",\n")
        builder.append("    \"instanceShallowSizes\": ")
        builder.append(longArray(heap.instances.map { it.shallowSize }.sorted())).append(",\n")
        val arraySizes = (heap.objectArrays.map { it.shallowSize } + heap.primitiveArrays.map { it.shallowSize }).sorted()
        builder.append("    \"arrayShallowSizes\": ").append(longArray(arraySizes)).append(",\n")
        builder.append("    \"heapNames\": ").append(stringArray(heap.heapByObjectId.values.distinct().sorted())).append(",\n")
        builder.append("    \"rootCount\": ").append(heap.gcRoots.size).append(",\n")
        builder.append("    \"warningCount\": ").append(heap.warnings.size).append("\n")
        builder.append("  }\n")
        builder.append("}\n")
        return builder.toString()
    }

    private fun stringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { value -> "\"" + value + "\"" }

    private fun longArray(values: List<Long>): String = values.joinToString(prefix = "[", postfix = "]")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
