package com.androidperformancestudio.storage

import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileFile
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileThread
import java.io.Closeable
import java.sql.Connection

@Suppress("MagicNumber", "TooManyFunctions")
class SQLiteProfileRecordWriter internal constructor(
    private val connection: Connection,
    private val batchSize: Int,
) : Closeable {
    private val processIds = mutableSetOf<Int>()
    private val threads = mutableMapOf<Int, ProfileThread>()
    private val eventIds = mutableMapOf<String, Long>()
    private val files = mutableMapOf<Int, String>()
    private val symbolIds = mutableMapOf<SymbolKey, Long>()
    private val frameIds = mutableMapOf<FrameKey, Long>()
    private val callsiteIds = mutableMapOf<CallsiteKey, Long>()
    private val insertSample =
        connection.prepareStatement(
            "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, " +
                "leaf_callsite_id, has_unknown_symbol, empty_stack, unwind_error_code, unwind_raw_code, " +
                "unwind_address) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
    private var importedRecords = 0L
    private var importedSamples = 0L
    private var pendingRecords = 0
    private var pendingSamples = 0
    private var committedBatches = 0
    private var finished = false

    fun add(record: NormalizedProfileRecord) {
        check(!finished) { "Record writer is already finished" }
        when (record) {
            is NormalizedProfileRecord.Sample -> insertSample(record.value)
            is NormalizedProfileRecord.Lost -> insertLost(record.sampleCount, record.lostCount)
            is NormalizedProfileRecord.File -> insertFile(record.value)
            is NormalizedProfileRecord.Thread -> insertThread(record.value)
            is NormalizedProfileRecord.Metadata -> insertMetadata(record.value)
            is NormalizedProfileRecord.ContextSwitch -> insertContextSwitch(record)
            NormalizedProfileRecord.Unknown -> insertUnknown()
        }
        importedRecords++
        pendingRecords++
        if (record is NormalizedProfileRecord.Sample) {
            importedSamples++
            pendingSamples++
            if (pendingSamples == batchSize) commitBatch()
        }
    }

    fun finish(): ProfileImportResult {
        check(!finished) { "Record writer is already finished" }
        if (pendingRecords > 0) commitBatch()
        finished = true
        connection.autoCommit = true
        insertSample.close()
        return ProfileImportResult(importedRecords, importedSamples, committedBatches)
    }

    override fun close() {
        if (!finished) {
            connection.rollback()
            connection.autoCommit = true
            finished = true
            insertSample.close()
        }
    }

    private fun insertSample(sample: NormalizedSample) {
        upsertProcess(sample.processId)
        upsertThread(ProfileThread(sample.processId, sample.threadId, sample.threadName))
        val eventId = eventId(sample.eventType)
        val leafCallsite = callsiteId(sample.frames)
        insertSample.setLong(1, sample.timestampNanos)
        insertSample.setInt(2, sample.processId)
        insertSample.setInt(3, sample.threadId)
        insertSample.setLong(4, eventId)
        insertSample.setLong(5, sample.eventCount)
        insertSample.setObject(6, leafCallsite)
        insertSample.setInt(7, if (sample.frames.any { it.isUnknown() }) 1 else 0)
        insertSample.setInt(8, if (sample.frames.isEmpty()) 1 else 0)
        insertSample.setString(9, sample.unwindError?.code)
        insertSample.setObject(10, sample.unwindError?.rawCode)
        insertSample.setObject(11, sample.unwindError?.address)
        insertSample.addBatch()
    }

    private fun callsiteId(frames: List<ProfileFrame>): Long? {
        var parentId: Long? = null
        frames.asReversed().forEach { frame ->
            val frameId = frameId(frame)
            val key = CallsiteKey(parentId, frameId)
            parentId = callsiteIds.getOrPut(key) { loadOrInsertCallsite(key) }
        }
        return parentId
    }

    private fun frameId(frame: ProfileFrame): Long {
        val key =
            FrameKey(
                frame.virtualAddress,
                frame.fileId,
                frame.symbolId,
                frame.symbolName,
                frame.executionType.name,
            )
        return frameIds.getOrPut(key) { loadOrInsertFrame(frame) }
    }

    private fun loadOrInsertFrame(frame: ProfileFrame): Long {
        upsertFile(frame.fileId, frame.filePath)
        val symbolId = symbolId(frame.fileId, frame.symbolId, frame.symbolName, null)
        connection
            .prepareStatement(
                "INSERT OR IGNORE INTO frame(virtual_address, file_id, symbol_id, execution_type) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, frame.virtualAddress)
                statement.setInt(2, frame.fileId)
                statement.setLong(3, symbolId)
                statement.setString(4, frame.executionType.name)
                statement.executeUpdate()
            }
        return connection
            .prepareStatement(
                "SELECT frame_id FROM frame WHERE virtual_address=? AND file_id=? AND symbol_id=? AND execution_type=?",
            ).use { statement ->
                statement.setLong(1, frame.virtualAddress)
                statement.setInt(2, frame.fileId)
                statement.setLong(3, symbolId)
                statement.setString(4, frame.executionType.name)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
    }

    private fun insertFile(file: ProfileFile) {
        upsertFile(file.id, file.path)
        file.symbols.forEachIndexed { index, name ->
            symbolId(file.id, index, name, file.mangledSymbols.getOrNull(index))
        }
    }

    private fun upsertFile(
        fileId: Int,
        path: String,
    ) {
        if (files[fileId] == path) return
        connection
            .prepareStatement(
                "INSERT INTO file(file_id, path) VALUES (?, ?) " +
                    "ON CONFLICT(file_id) DO UPDATE SET path=excluded.path",
            ).use { statement ->
                statement.setInt(1, fileId)
                statement.setString(2, path)
                statement.executeUpdate()
            }
        files[fileId] = path
    }

    private fun symbolId(
        fileId: Int,
        sourceSymbolId: Int,
        name: String,
        mangledName: String?,
    ): Long {
        val key = SymbolKey(fileId, sourceSymbolId, name)
        symbolIds[key]?.let { return it }
        connection
            .prepareStatement(
                "INSERT INTO symbol(file_id, source_symbol_id, name, mangled_name) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(file_id, source_symbol_id, name) DO UPDATE SET " +
                    "mangled_name=COALESCE(excluded.mangled_name, symbol.mangled_name)",
            ).use { statement ->
                statement.setInt(1, fileId)
                statement.setInt(2, sourceSymbolId)
                statement.setString(3, name)
                statement.setString(4, mangledName)
                statement.executeUpdate()
            }
        val id =
            connection
                .prepareStatement(
                    "SELECT symbol_id FROM symbol WHERE file_id=? AND source_symbol_id=? AND name=?",
                ).use { statement ->
                    statement.setInt(1, fileId)
                    statement.setInt(2, sourceSymbolId)
                    statement.setString(3, name)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getLong(1)
                    }
                }
        symbolIds[key] = id
        return id
    }

    private fun insertThread(thread: ProfileThread) {
        upsertProcess(thread.processId)
        upsertThread(thread)
    }

    private fun upsertProcess(processId: Int) {
        if (!processIds.add(processId)) return
        connection.prepareStatement("INSERT OR IGNORE INTO process(process_id) VALUES (?)").use { statement ->
            statement.setInt(1, processId)
            statement.executeUpdate()
        }
    }

    private fun upsertThread(thread: ProfileThread) {
        if (threads[thread.threadId] == thread) return
        connection
            .prepareStatement(
                "INSERT INTO thread(thread_id, process_id, name) VALUES (?, ?, ?) " +
                    "ON CONFLICT(thread_id) DO UPDATE SET process_id=excluded.process_id, name=excluded.name",
            ).use { statement ->
                statement.setInt(1, thread.threadId)
                statement.setInt(2, thread.processId)
                statement.setString(3, thread.name)
                statement.executeUpdate()
            }
        threads[thread.threadId] = thread
    }

    private fun eventId(name: String): Long {
        eventIds[name]?.let { return it }
        connection.prepareStatement("INSERT OR IGNORE INTO event(name) VALUES (?)").use { statement ->
            statement.setString(1, name)
            statement.executeUpdate()
        }
        val id =
            connection.prepareStatement("SELECT event_id FROM event WHERE name=?").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        eventIds[name] = id
        return id
    }

    private fun insertLost(
        sampleCount: Long,
        lostCount: Long,
    ) {
        connection.prepareStatement("INSERT INTO lost_situation(sample_count, lost_count) VALUES (?, ?)").use {
            it.setLong(1, sampleCount)
            it.setLong(2, lostCount)
            it.executeUpdate()
        }
    }

    private fun insertUnknown() {
        connection.createStatement().use { it.executeUpdate("INSERT INTO unknown_record DEFAULT VALUES") }
    }

    private fun insertContextSwitch(record: NormalizedProfileRecord.ContextSwitch) {
        connection
            .prepareStatement(
                "INSERT INTO context_switch(thread_id, timestamp_nanos, switched_on_cpu) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setInt(1, record.threadId)
                statement.setLong(2, record.timestampNanos)
                statement.setInt(3, if (record.switchedOnCpu) 1 else 0)
                statement.executeUpdate()
            }
    }

    private fun insertMetadata(metadata: ProfileMetadata) {
        connection
            .prepareStatement(
                "INSERT INTO profile_metadata(metadata_id, event_types, app_package_name, app_type, " +
                    "android_sdk_version, android_build_type, trace_off_cpu) VALUES (1, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(metadata_id) DO UPDATE SET event_types=excluded.event_types, " +
                    "app_package_name=excluded.app_package_name, app_type=excluded.app_type, " +
                    "android_sdk_version=excluded.android_sdk_version, " +
                    "android_build_type=excluded.android_build_type, " +
                    "trace_off_cpu=excluded.trace_off_cpu",
            ).use { statement ->
                statement.setString(1, metadata.eventTypes.joinToString("\n"))
                statement.setString(2, metadata.appPackageName)
                statement.setString(3, metadata.appType)
                statement.setString(4, metadata.androidSdkVersion)
                statement.setString(5, metadata.androidBuildType)
                statement.setInt(6, if (metadata.traceOffCpu) 1 else 0)
                statement.executeUpdate()
            }
    }

    private fun commitBatch() {
        if (pendingSamples > 0) insertSample.executeBatch()
        connection.commit()
        committedBatches++
        pendingRecords = 0
        pendingSamples = 0
    }

    private fun loadOrInsertCallsite(key: CallsiteKey): Long {
        connection
            .prepareStatement(
                "INSERT OR IGNORE INTO callsite(parent_id, frame_id) VALUES (?, ?)",
            ).use { statement ->
                statement.setObject(1, key.parentId)
                statement.setLong(2, key.frameId)
                statement.executeUpdate()
            }
        return connection
            .prepareStatement(
                "SELECT callsite_id FROM callsite WHERE IFNULL(parent_id, 0) = IFNULL(?, 0) AND frame_id = ?",
            ).use { statement ->
                statement.setObject(1, key.parentId)
                statement.setLong(2, key.frameId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
    }
}

private data class SymbolKey(
    val fileId: Int,
    val sourceSymbolId: Int,
    val name: String,
)

private data class FrameKey(
    val virtualAddress: Long,
    val fileId: Int,
    val sourceSymbolId: Int,
    val symbolName: String,
    val executionType: String,
)

private data class CallsiteKey(
    val parentId: Long?,
    val frameId: Long,
)

private fun ProfileFrame.isUnknown(): Boolean = symbolName.startsWith("<unknown") || filePath.startsWith("<unknown")
