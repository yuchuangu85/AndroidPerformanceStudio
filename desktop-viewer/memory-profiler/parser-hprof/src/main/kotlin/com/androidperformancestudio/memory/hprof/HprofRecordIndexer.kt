@file:Suppress("ThrowsCount", "MagicNumber")

package com.androidperformancestudio.memory.hprof

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

public data class HprofRecordIndexEntry(
    val offset: Long,
    val tag: Int,
    val payloadLength: Int,
)

public data class HprofRecordIndex(
    val sourceSizeBytes: Long,
    val idSize: Int,
    val records: List<HprofRecordIndexEntry>,
)

/** Builds a compact record-offset index without materializing heap objects. */
public class HprofRecordIndexer {
    public fun build(
        source: Path,
        indexFile: Path,
    ): HprofRecordIndex {
        require(Files.isRegularFile(source)) { "HPROF input is not a regular file: $source" }
        val index =
            FileChannel.open(source, StandardOpenOption.READ).use { channel ->
                val sourceSize = channel.size()
                val header = readHeader(channel)
                val idSize = readInt(channel)
                if (idSize != ID_SIZE_4 && idSize != ID_SIZE_8) {
                    throw HprofParseException("Unsupported HPROF id size $idSize")
                }
                readLong(channel)
                val records =
                    buildList {
                        while (channel.position() < sourceSize) {
                            val offset = channel.position()
                            if (sourceSize - offset < RECORD_HEADER_BYTES) {
                                throw HprofParseException("Truncated HPROF record header at offset $offset")
                            }
                            val tag = readByte(channel)
                            readInt(channel)
                            val payloadLength = readInt(channel)
                            if (payloadLength < 0 || payloadLength.toLong() > sourceSize - channel.position()) {
                                throw HprofParseException("Truncated HPROF record at offset $offset")
                            }
                            add(HprofRecordIndexEntry(offset, tag, payloadLength))
                            channel.position(channel.position() + payloadLength)
                        }
                    }
                if (!header.startsWith(HPROF_HEADER_PREFIX)) {
                    throw HprofParseException("Unsupported HPROF header $header")
                }
                HprofRecordIndex(sourceSize, idSize, records)
            }
        writeAtomically(indexFile, index)
        return index
    }

    public fun read(indexFile: Path): HprofRecordIndex {
        val lines = Files.readAllLines(indexFile)
        require(lines.firstOrNull()?.startsWith(INDEX_HEADER) == true) { "Invalid HPROF record index: $indexFile" }
        val metadata = lines.first().removePrefix(INDEX_HEADER).split('|')
        val sourceSize = metadata[0].toLong()
        val idSize = metadata[1].toInt()
        val records =
            lines.drop(1).filter(String::isNotBlank).map { line ->
                val values = line.split('|')
                HprofRecordIndexEntry(values[0].toLong(), values[1].toInt(), values[2].toInt())
            }
        return HprofRecordIndex(sourceSize, idSize, records)
    }

    private fun writeAtomically(
        indexFile: Path,
        index: HprofRecordIndex,
    ) {
        indexFile.parent?.let(Files::createDirectories)
        val temporary = indexFile.resolveSibling(".${indexFile.fileName}.tmp")
        Files.newBufferedWriter(temporary).use { writer ->
            writer.appendLine("$INDEX_HEADER${index.sourceSizeBytes}|${index.idSize}")
            index.records.forEach { entry ->
                writer.appendLine("${entry.offset}|${entry.tag}|${entry.payloadLength}")
            }
        }
        Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readByte(channel: FileChannel): Int {
        val buffer = ByteBuffer.allocate(1)
        readFully(channel, buffer)
        return buffer.flip().get().toInt() and 0xff
    }

    private fun readInt(channel: FileChannel): Int {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
        readFully(channel, buffer)
        return buffer.flip().int
    }

    private fun readLong(channel: FileChannel): Long {
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
        readFully(channel, buffer)
        return buffer.flip().long
    }

    private fun readHeader(channel: FileChannel): String {
        val bytes =
            buildList {
                while (true) {
                    val value = readByte(channel)
                    if (value == 0) break
                    add(value.toByte())
                    if (size > MAX_HEADER_BYTES) throw HprofParseException("HPROF header is too large")
                }
            }
        return bytes.toByteArray().decodeToString()
    }

    private fun readFully(
        channel: FileChannel,
        buffer: ByteBuffer,
    ) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw HprofParseException("Unexpected end of HPROF")
        }
    }

    private companion object {
        const val ID_SIZE_4 = 4
        const val ID_SIZE_8 = 8
        const val RECORD_HEADER_BYTES = 1 + Int.SIZE_BYTES + Int.SIZE_BYTES
        const val MAX_HEADER_BYTES = 256
        const val HPROF_HEADER_PREFIX = "JAVA PROFILE "
        const val INDEX_HEADER = "HPROF-RECORD-INDEX|"
    }
}
