package com.androidperformancestudio.export

import com.androidperformancestudio.storage.SQLiteSampleStore
import com.androidperformancestudio.storage.StoredProfileSample
import com.androidperformancestudio.storage.StoredProfileThread
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories

data class GeckoProfileExportResult(
    val destination: Path,
    val threadCount: Int,
    val sampleCount: Long,
    val copiedOriginal: Boolean,
)

class GeckoProfileExportService {
    fun export(
        sessionDirectory: Path,
        destination: Path,
    ): GeckoProfileExportResult {
        val original = sessionDirectory.resolve(GECKO_PROFILE_FILE)
        if (Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
            copyOriginal(original, destination)
            return sessionCounts(sessionDirectory, destination, copiedOriginal = true)
        }

        val database = sessionDirectory.resolve(PROFILE_DATABASE_FILE)
        require(Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            "Profile database does not exist: $database"
        }
        destination.toAbsolutePath().parent?.createDirectories()
        val temporary = temporarySibling(destination)
        return try {
            val counts = writeDatabaseProfile(database, temporary)
            replace(temporary, destination)
            GeckoProfileExportResult(destination, counts.threadCount, counts.sampleCount, copiedOriginal = false)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun copyOriginal(
        source: Path,
        destination: Path,
    ) {
        destination.toAbsolutePath().parent?.createDirectories()
        if (source.toAbsolutePath().normalize() == destination.toAbsolutePath().normalize()) return
        val temporary = temporarySibling(destination)
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            replace(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sessionCounts(
        sessionDirectory: Path,
        destination: Path,
        copiedOriginal: Boolean,
    ): GeckoProfileExportResult {
        val database = sessionDirectory.resolve(PROFILE_DATABASE_FILE)
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            return GeckoProfileExportResult(destination, 0, 0, copiedOriginal)
        }
        return SQLiteSampleStore.openReadOnly(database).use { store ->
            GeckoProfileExportResult(
                destination = destination,
                threadCount = store.threads().size,
                sampleCount = store.sampleCount(),
                copiedOriginal = copiedOriginal,
            )
        }
    }

    private fun writeDatabaseProfile(
        database: Path,
        destination: Path,
    ): ExportCounts {
        GZIPOutputStream(Files.newOutputStream(destination)).use { gzip ->
            BufferedWriter(gzip.writer(StandardCharsets.UTF_8)).use { writer ->
                return SQLiteSampleStore.openReadOnly(database).use { store ->
                    GeckoProfileWriter(writer).write(store)
                }
            }
        }
    }

    private fun temporarySibling(destination: Path): Path {
        val absolute = destination.toAbsolutePath()
        val parent = checkNotNull(absolute.parent) { "Export destination has no parent: $destination" }
        parent.createDirectories()
        return Files.createTempFile(parent, ".${absolute.fileName}-", ".tmp")
    }

    private fun replace(
        source: Path,
        destination: Path,
    ) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private data class ExportCounts(
    val threadCount: Int,
    val sampleCount: Long,
)

private class GeckoProfileWriter(
    private val writer: BufferedWriter,
) {
    private var currentThread: GeckoThreadBuilder? = null
    private var wroteThread = false
    private var threadCount = 0
    private var sampleCount = 0L

    fun write(store: SQLiteSampleStore): ExportCounts {
        writer.append(PROFILE_PREFIX)
        store.forEachStoredSample(::addSample)
        flushThread()
        writer.append(PROFILE_SUFFIX)
        return ExportCounts(threadCount, sampleCount)
    }

    private fun addSample(sample: StoredProfileSample) {
        val current = currentThread
        if (current != null && current.thread.key != sample.thread.key) flushThread()
        val target = currentThread ?: GeckoThreadBuilder(sample.thread).also { currentThread = it }
        target.add(sample)
        sampleCount++
    }

    private fun flushThread() {
        val thread = currentThread ?: return
        if (wroteThread) writer.append(',')
        thread.writeJson(writer)
        wroteThread = true
        threadCount++
        currentThread = null
    }
}

private class GeckoThreadBuilder(
    val thread: StoredProfileThread,
) {
    private val stringIds = linkedMapOf<String, Int>()
    private val frames = mutableListOf<GeckoFrame>()
    private val frameIds = linkedMapOf<String, Int>()
    private val stacks = mutableListOf<GeckoStack>()
    private val stackIds = linkedMapOf<StackKey, Int>()
    private val samples = mutableListOf<GeckoSample>()

    fun add(sample: StoredProfileSample) {
        var prefix: Int? = null
        sample.framesRootToLeaf.forEach { frame ->
            val location = "${frame.symbolName} (in ${frame.filePath})"
            val frameId = internFrame(location)
            prefix = internStack(StackKey(prefix, frameId))
        }
        samples += GeckoSample(prefix, sample.timestampNanos / NANOS_PER_MILLISECOND)
    }

    fun writeJson(writer: BufferedWriter) {
        writer.append("{\"tid\":").append(thread.threadId.toString())
        writer.append(",\"pid\":").append(thread.processId.toString())
        writer.append(",\"name\":").append(thread.name.jsonString())
        writer.append(MARKERS_JSON)
        writer.append(",\"samples\":{\"schema\":{\"stack\":0,\"time\":1,\"responsiveness\":2},\"data\":[")
        samples.forEachIndexed { index, sample ->
            if (index > 0) writer.append(',')
            writer.append('[').append(sample.stackId?.toString() ?: "null")
            writer.append(',').append(sample.timeMillis.toString()).append(",0]")
        }
        writer.append("]},\"frameTable\":{\"schema\":")
        writer.append(FRAME_SCHEMA_JSON).append(",\"data\":[")
        frames.forEachIndexed { index, frame ->
            if (index > 0) writer.append(',')
            writer.append('[').append(frame.stringId.toString())
            writer.append(",false,0,null,null,null,null,")
            writer.append(frame.category.toString()).append(",0]")
        }
        writer.append("]},\"stackTable\":{\"schema\":{\"prefix\":0,\"frame\":1,\"category\":2},\"data\":[")
        stacks.forEachIndexed { index, stack ->
            if (index > 0) writer.append(',')
            writer.append('[').append(stack.prefixId?.toString() ?: "null")
            writer.append(',').append(stack.frameId.toString()).append(",0]")
        }
        writer.append("]},\"stringTable\":[")
        stringIds.keys.forEachIndexed { index, value ->
            if (index > 0) writer.append(',')
            writer.append(value.jsonString())
        }
        writer.append("],\"registerTime\":0,\"unregisterTime\":null,\"processType\":\"default\"}")
    }

    private fun internFrame(location: String): Int =
        frameIds.getOrPut(location) {
            val id = frames.size
            frames += GeckoFrame(internString(location), location.geckoCategory())
            id
        }

    private fun internString(value: String): Int = stringIds.getOrPut(value) { stringIds.size }

    private fun internStack(key: StackKey): Int =
        stackIds.getOrPut(key) {
            val id = stacks.size
            stacks += GeckoStack(key.prefixId, key.frameId)
            id
        }
}

private data class GeckoFrame(
    val stringId: Int,
    val category: Int,
)

private data class GeckoStack(
    val prefixId: Int?,
    val frameId: Int,
)

private data class StackKey(
    val prefixId: Int?,
    val frameId: Int,
)

private data class GeckoSample(
    val stackId: Int?,
    val timeMillis: Double,
)

private fun String.geckoCategory(): Int =
    when {
        "kallsyms" in this || ".ko" in this -> if (startsWith("__schedule ")) OFF_CPU_CATEGORY else KERNEL_CATEGORY
        ".so" in this -> NATIVE_CATEGORY
        ".vdex" in this -> DEX_CATEGORY
        ".oat" in this -> OAT_CATEGORY
        "[JIT app cache]" in this -> JIT_CATEGORY
        else -> USER_CATEGORY
    }

private fun String.jsonString(): String =
    buildString {
        append('"')
        this@jsonString.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < CONTROL_CHARACTER_LIMIT) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }

private const val PROFILE_DATABASE_FILE = "profile.sqlite"
private const val GECKO_PROFILE_FILE = "gecko-profile.json.gz"
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val CONTROL_CHARACTER_LIMIT = 0x20
private const val USER_CATEGORY = 0
private const val KERNEL_CATEGORY = 1
private const val NATIVE_CATEGORY = 2
private const val DEX_CATEGORY = 3
private const val OAT_CATEGORY = 4
private const val OFF_CPU_CATEGORY = 5
private const val JIT_CATEGORY = 7

private const val PROFILE_PREFIX =
    "{\"meta\":{\"interval\":1,\"processType\":0,\"product\":\"Android Performance Studio\"," +
        "\"device\":null,\"platform\":null,\"stackwalk\":1,\"debug\":0,\"gcpoison\":0," +
        "\"asyncstack\":1,\"startTime\":0,\"shutdownTime\":null,\"version\":24," +
        "\"presymbolicated\":true,\"categories\":[" +
        "{\"name\":\"User\",\"color\":\"yellow\",\"subcategories\":[\"Other\"]}," +
        "{\"name\":\"Kernel\",\"color\":\"orange\",\"subcategories\":[\"Other\"]}," +
        "{\"name\":\"Native\",\"color\":\"yellow\",\"subcategories\":[\"Other\"]}," +
        "{\"name\":\"DEX\",\"color\":\"green\",\"subcategories\":[\"Other\"]}," +
        "{\"name\":\"OAT\",\"color\":\"green\",\"subcategories\":[\"Other\"]}," +
        "{\"name\":\"Off-CPU\",\"color\":\"blue\",\"subcategories\":[\"Other\"]}," +
        "{\"name\":\"Other\",\"color\":\"grey\",\"subcategories\":[\"Other\"]}," +
        "{\"name\":\"JIT\",\"color\":\"green\",\"subcategories\":[\"Other\"]}" +
        "],\"markerSchema\":[],\"abi\":null,\"oscpu\":null,\"appBuildID\":null}," +
        "\"libs\":[],\"threads\":["

private const val PROFILE_SUFFIX = "],\"processes\":[],\"pausedRanges\":[]}"

private const val MARKERS_JSON =
    ",\"markers\":{\"schema\":{\"name\":0,\"startTime\":1,\"endTime\":2,\"phase\":3," +
        "\"category\":4,\"data\":5},\"data\":[]}"

private const val FRAME_SCHEMA_JSON =
    "{\"location\":0,\"relevantForJS\":1,\"innerWindowID\":2,\"implementation\":3," +
        "\"optimizations\":4,\"line\":5,\"column\":6,\"category\":7,\"subcategory\":8}"
