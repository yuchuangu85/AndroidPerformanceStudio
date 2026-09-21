@file:Suppress("MagicNumber")

package com.androidperformancestudio.export

import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.WeightedCallStack
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories

class FoldedStacksExportService {
    fun export(
        callNodes: CallNodeTable,
        destination: Path,
    ) = export(callNodes.asSelfWeightedStacks(), destination)

    fun export(
        table: CallStackTable,
        destination: Path,
    ) {
        destination.toAbsolutePath().parent?.createDirectories()
        val rows =
            table.stacks
                .groupBy { stack -> stack.frameIdsRootToLeaf.map { table.frame(it).symbolName.sanitizeFolded() } }
                .map { (frames, stacks) -> "${frames.joinToString(";")} ${stacks.sumOf(WeightedCallStack::weight)}" }
                .sorted()
        Files.writeString(destination, rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n"))
    }
}

class PprofProfileExportService {
    fun export(
        callNodes: CallNodeTable,
        destination: Path,
    ) = export(callNodes.asSelfWeightedStacks(), destination)

    fun export(
        table: CallStackTable,
        destination: Path,
    ) {
        destination.toAbsolutePath().parent?.createDirectories()
        val payload = buildProfile(table)
        GZIPOutputStream(Files.newOutputStream(destination)).use { it.write(payload) }
    }

    private fun buildProfile(table: CallStackTable): ByteArray {
        val strings = StringTable()
        val frames = table.framesById.values.sortedBy { it.frameId }
        val functions = frames.distinctBy { it.functionId }
        val ids =
            PprofIds(
                locations = frames.mapIndexed { index, frame -> frame.frameId to index.toLong() + 1 }.toMap(),
                functions = functions.mapIndexed { index, frame -> frame.functionId to index.toLong() + 1 }.toMap(),
            )
        val writer = ProtoWriter()
        writer.message(1, sampleType(strings))
        writeFunctions(writer, strings, functions, ids)
        writeLocations(writer, frames, ids)
        writeSamples(writer, table, ids)
        strings.values.forEach { writer.string(6, it) }
        writeTiming(writer, table)
        return writer.bytes()
    }

    private fun sampleType(strings: StringTable): ByteArray =
        ProtoWriter()
            .apply {
                int64(1, strings.index("samples").toLong())
                int64(2, strings.index("count").toLong())
            }.bytes()

    private fun writeFunctions(
        writer: ProtoWriter,
        strings: StringTable,
        functions: List<CallStackFrame>,
        ids: PprofIds,
    ) {
        functions.forEach { frame ->
            writer.message(
                5,
                ProtoWriter()
                    .apply {
                        uint64(1, ids.functions.getValue(frame.functionId))
                        int64(2, strings.index(frame.symbolName).toLong())
                        int64(3, strings.index(frame.symbolName).toLong())
                        int64(4, strings.index(frame.resource).toLong())
                    }.bytes(),
            )
        }
    }

    private fun writeLocations(
        writer: ProtoWriter,
        frames: List<CallStackFrame>,
        ids: PprofIds,
    ) {
        frames.forEach { frame ->
            writer.message(
                4,
                ProtoWriter()
                    .apply {
                        uint64(1, ids.locations.getValue(frame.frameId))
                        if (frame.virtualAddress != 0L) uint64(3, frame.virtualAddress)
                        message(4, line(ids.functions.getValue(frame.functionId)))
                    }.bytes(),
            )
        }
    }

    private fun line(functionId: Long): ByteArray =
        ProtoWriter()
            .apply {
                uint64(1, functionId)
                int64(2, 0)
            }.bytes()

    private fun writeSamples(
        writer: ProtoWriter,
        table: CallStackTable,
        ids: PprofIds,
    ) {
        table.stacks.forEach { stack ->
            writer.message(
                2,
                ProtoWriter()
                    .apply {
                        stack.frameIdsRootToLeaf.asReversed().forEach { frameId ->
                            uint64(1, ids.locations.getValue(frameId))
                        }
                        int64(2, stack.weight)
                    }.bytes(),
            )
        }
    }

    private fun writeTiming(
        writer: ProtoWriter,
        table: CallStackTable,
    ) {
        val first = table.stacks.minOfOrNull(WeightedCallStack::timestampNanos)
        val last = table.stacks.maxOfOrNull(WeightedCallStack::timestampNanos)
        writer.int64(9, first ?: 0)
        writer.int64(10, if (first != null && last != null) (last - first).coerceAtLeast(0) else 0)
    }

    private data class PprofIds(
        val locations: Map<Long, Long>,
        val functions: Map<FlameFunctionId, Long>,
    )
}

private class StringTable {
    val values = mutableListOf("")
    private val indexes = mutableMapOf("" to 0)

    fun index(value: String): Int = indexes.getOrPut(value) { values.size.also { values += value } }
}

private class ProtoWriter {
    private val output = ByteArrayOutputStream()

    fun uint64(
        field: Int,
        value: Long,
    ) {
        tag(field, 0)
        varint(value)
    }

    fun int64(
        field: Int,
        value: Long,
    ) = uint64(field, value)

    fun string(
        field: Int,
        value: String,
    ) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(
        field: Int,
        value: ByteArray,
    ) = bytes(field, value)

    fun bytes(): ByteArray = output.toByteArray()

    private fun bytes(
        field: Int,
        value: ByteArray,
    ) {
        tag(field, 2)
        varint(value.size.toLong())
        output.write(value)
    }

    private fun tag(
        field: Int,
        wireType: Int,
    ) = varint(((field shl 3) or wireType).toLong())

    private fun varint(raw: Long) {
        var value = raw
        while (true) {
            if (value and -128L == 0L) {
                output.write(value.toInt())
                return
            }
            output.write((value.toInt() and 0x7f) or 0x80)
            value = value ushr 7
        }
    }
}

private fun String.sanitizeFolded(): String = replace(';', ':').replace('\n', ' ').replace('\r', ' ')

private fun CallNodeTable.asSelfWeightedStacks(): CallStackTable {
    val parents = parentIndexes
    val frameIds = frameIds
    val weights = selfWeights
    val stacks =
        (0 until size).mapNotNull { index ->
            val weight = weights[index]
            if (weight <= 0) return@mapNotNull null
            val path = ArrayDeque<Long>()
            var cursor = index
            while (cursor >= 0) {
                path.addFirst(frameIds[cursor])
                cursor = parents[cursor]
            }
            WeightedCallStack(
                sampleId = index.toLong() + 1,
                timestampNanos = 0,
                weight = weight,
                threadKey = "aggregate",
                category = categoryAt(index),
                subcategory = null,
                frameIdsRootToLeaf = path.toList(),
            )
        }
    return CallStackTable(framesById, stacks)
}
