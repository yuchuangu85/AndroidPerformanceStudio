package com.androidperformancestudio.storage

import com.androidperformancestudio.model.CanonicalProfileRecord
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileCategory
import com.androidperformancestudio.model.ProfileCounterFact
import com.androidperformancestudio.model.ProfileFile
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMarkerFact
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileProcessFact
import com.androidperformancestudio.model.ProfileProcessKey
import com.androidperformancestudio.model.ProfileSampleFact
import com.androidperformancestudio.model.ProfileScreenshotFact
import com.androidperformancestudio.model.ProfileSliceFact
import com.androidperformancestudio.model.ProfileSourceFact
import com.androidperformancestudio.model.ProfileSourceId
import com.androidperformancestudio.model.ProfileThread
import com.androidperformancestudio.model.ProfileThreadFact
import com.androidperformancestudio.model.ProfileThreadKey
import com.androidperformancestudio.model.ProfileTimePoint
import java.io.Closeable
import java.sql.Connection
import java.sql.PreparedStatement

@Suppress("MagicNumber", "TooGenericExceptionCaught", "TooManyFunctions")
class SQLiteProfileRecordWriter internal constructor(
    private val connection: Connection,
    private val batchSize: Int,
) : Closeable {
    private val processIds = mutableSetOf<Int>()
    private val threads = mutableMapOf<Int, ProfileThread>()
    private val canonicalProcessRows = mutableMapOf<ProfileProcessKey, Long>()
    private val canonicalThreadRows = mutableMapOf<ProfileThreadKey, Long>()
    private val eventIds = mutableMapOf<String, Long>()
    private val files = mutableMapOf<Int, String>()
    private val symbolIds = mutableMapOf<SymbolKey, Long>()
    private val frameIds = mutableMapOf<FrameKey, Long>()
    private val callsiteIds = mutableMapOf<CallsiteKey, Long>()
    private val insertSample =
        connection.prepareStatement(
            "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, " +
                "leaf_callsite_id, has_unknown_symbol, empty_stack, unwind_error_code, unwind_raw_code, " +
                "unwind_address, cpu_core, on_cpu, category_name, subcategory_name, source_id, " +
                "process_row_id, thread_row_id, clock_domain, time_error_bound_nanos) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
    private var importedRecords = 0L
    private var importedSamples = 0L
    private var pendingRecords = 0
    private var pendingSamples = 0
    private var committedBatches = 0
    private var finished = false

    fun add(record: NormalizedProfileRecord) {
        checkActive()
        when (record) {
            is NormalizedProfileRecord.Sample -> insertSample(record.value)
            is NormalizedProfileRecord.Lost -> insertLost(record.sampleCount, record.lostCount)
            is NormalizedProfileRecord.File -> insertFile(record.value)
            is NormalizedProfileRecord.Thread -> insertThread(record.value)
            is NormalizedProfileRecord.Metadata -> insertMetadata(record.value)
            is NormalizedProfileRecord.ContextSwitch -> insertContextSwitch(record)
            NormalizedProfileRecord.Unknown -> insertUnknown()
        }
        recordImported(record is NormalizedProfileRecord.Sample)
    }

    fun addCanonical(record: CanonicalProfileRecord) {
        checkActive()
        when (record) {
            is CanonicalProfileRecord.Source -> insertSource(record.value)
            is CanonicalProfileRecord.Process -> insertProcess(record.value)
            is CanonicalProfileRecord.Thread -> insertCanonicalThread(record.value)
            is CanonicalProfileRecord.Sample -> insertCanonicalSample(record.value)
            is CanonicalProfileRecord.Marker -> insertMarker(record.value)
            is CanonicalProfileRecord.Counter -> insertCounter(record.value)
            is CanonicalProfileRecord.Slice -> insertSlice(record.value)
            is CanonicalProfileRecord.Screenshot -> insertScreenshot(record.value)
            is CanonicalProfileRecord.Legacy -> {
                add(record.value)
                return
            }
        }
        recordImported(record is CanonicalProfileRecord.Sample)
    }

    fun finish(): ProfileImportResult {
        checkActive()
        try {
            if (pendingRecords > 0) commitBatch()
        } catch (failure: Throwable) {
            cleanupFailedImport(failure)
            throw failure
        }
        val result = ProfileImportResult(importedRecords, importedSamples, committedBatches)
        finished = true
        closeResourcesAfterSuccess()
        return result
    }

    override fun close() {
        if (finished) return
        finished = true
        var failure: Throwable? = null
        failure = captureCleanupFailure(failure) { connection.rollback() }
        failure = captureCleanupFailure(failure) { connection.autoCommit = true }
        failure = captureCleanupFailure(failure) { insertSample.close() }
        failure?.let { throw it }
    }

    private fun checkActive() {
        check(!finished) { "Record writer is already finished" }
    }

    private fun recordImported(sample: Boolean) {
        importedRecords++
        pendingRecords++
        if (sample) {
            importedSamples++
            pendingSamples++
        }
        if (pendingRecords == batchSize) commitBatch()
    }

    private fun insertSample(sample: NormalizedSample) {
        upsertProcess(sample.processId)
        upsertThread(ProfileThread(sample.processId, sample.threadId, sample.threadName))
        enqueueSample(sample, null, null, null, null)
    }

    private fun insertCanonicalSample(sample: ProfileSampleFact) {
        validateThreadSource(sample.sourceId, sample.thread, "sample")
        val processRowId = ensureCanonicalProcess(sample.thread.process)
        val threadRowId = ensureCanonicalThread(sample.thread)
        enqueueSample(
            sample =
                NormalizedSample(
                    timestampNanos = sample.time.timestampNanos,
                    processId = sample.thread.process.processId,
                    threadId = sample.thread.threadId,
                    threadName = "<unknown-thread:${sample.thread.threadId}>",
                    eventType = sample.eventType,
                    eventCount = sample.eventCount,
                    frames = sample.frames,
                    unwindError = sample.unwindError,
                ),
            cpuCore = sample.cpuCore,
            onCpu = sample.onCpu,
            category = sample.category,
            canonical =
                CanonicalSampleReference(
                    sample.sourceId.value,
                    processRowId,
                    threadRowId,
                    sample.time.clockDomain.value,
                    sample.time.errorBoundNanos,
                ),
        )
    }

    private fun enqueueSample(
        sample: NormalizedSample,
        cpuCore: Int?,
        onCpu: Boolean?,
        category: ProfileCategory?,
        canonical: CanonicalSampleReference?,
    ) {
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
        insertSample.setObject(12, cpuCore)
        insertSample.setObject(13, onCpu?.let { if (it) 1 else 0 })
        insertSample.setString(14, category?.name)
        insertSample.setString(15, category?.subcategory)
        insertSample.setString(16, canonical?.sourceId)
        insertSample.setObject(17, canonical?.processRowId)
        insertSample.setObject(18, canonical?.threadRowId)
        insertSample.setString(19, canonical?.clockDomain)
        insertSample.setObject(20, canonical?.errorBoundNanos)
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

    private fun insertSource(source: ProfileSourceFact) {
        connection
            .prepareStatement(
                "INSERT INTO profile_source(source_id, kind, clock_domain, valid_from_nanos, valid_until_nanos) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT(source_id) DO UPDATE SET kind=excluded.kind, " +
                    "clock_domain=excluded.clock_domain, valid_from_nanos=excluded.valid_from_nanos, " +
                    "valid_until_nanos=excluded.valid_until_nanos",
            ).use { statement ->
                statement.setString(1, source.id.value)
                statement.setString(2, source.kind.name)
                statement.setString(3, source.clockDomain.value)
                statement.setObject(4, source.validFromNanos)
                statement.setObject(5, source.validUntilNanosExclusive)
                statement.executeUpdate()
            }
    }

    private fun insertProcess(process: ProfileProcessFact) {
        val rowId = ensureCanonicalProcess(process.key)
        connection
            .prepareStatement(
                "UPDATE profile_process SET name=?, start_nanos=?, start_clock_domain=?, " +
                    "start_error_bound_nanos=?, end_nanos=?, end_clock_domain=?, end_error_bound_nanos=? " +
                    "WHERE process_row_id=?",
            ).use { statement ->
                statement.setString(1, process.name)
                statement.bindTime(2, process.start)
                statement.bindTime(5, process.end)
                statement.setLong(8, rowId)
                statement.executeUpdate()
            }
    }

    private fun insertCanonicalThread(thread: ProfileThreadFact) {
        validateThreadKey(thread.key)
        val rowId = ensureCanonicalThread(thread.key)
        connection
            .prepareStatement(
                "UPDATE profile_thread SET name=?, start_nanos=?, start_clock_domain=?, " +
                    "start_error_bound_nanos=?, end_nanos=?, end_clock_domain=?, end_error_bound_nanos=? " +
                    "WHERE thread_row_id=?",
            ).use { statement ->
                statement.setString(1, thread.name)
                statement.bindTime(2, thread.start)
                statement.bindTime(5, thread.end)
                statement.setLong(8, rowId)
                statement.executeUpdate()
            }
    }

    private fun ensureCanonicalProcess(process: ProfileProcessKey): Long =
        canonicalProcessRows.getOrPut(process) {
            upsertProcess(process.processId)
            connection
                .prepareStatement(
                    "INSERT OR IGNORE INTO profile_process(source_id, process_id) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, process.sourceId.value)
                    statement.setInt(2, process.processId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "SELECT process_row_id FROM profile_process WHERE source_id=? AND process_id=?",
                ).use { statement ->
                    statement.setString(1, process.sourceId.value)
                    statement.setInt(2, process.processId)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getLong(1)
                    }
                }
        }

    private fun ensureCanonicalThread(thread: ProfileThreadKey): Long {
        validateThreadKey(thread)
        return canonicalThreadRows.getOrPut(thread) {
            val processRowId = ensureCanonicalProcess(thread.process)
            insertLegacyThreadPlaceholder(thread)
            connection
                .prepareStatement(
                    "INSERT OR IGNORE INTO profile_thread(source_id, process_row_id, thread_id, name) " +
                        "VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, thread.sourceId.value)
                    statement.setLong(2, processRowId)
                    statement.setInt(3, thread.threadId)
                    statement.setString(4, "<unknown-thread:${thread.threadId}>")
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "SELECT thread_row_id FROM profile_thread " +
                        "WHERE source_id=? AND process_row_id=? AND thread_id=?",
                ).use { statement ->
                    statement.setString(1, thread.sourceId.value)
                    statement.setLong(2, processRowId)
                    statement.setInt(3, thread.threadId)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getLong(1)
                    }
                }
        }
    }

    private fun insertLegacyThreadPlaceholder(thread: ProfileThreadKey) {
        connection
            .prepareStatement(
                "INSERT OR IGNORE INTO thread(thread_id, process_id, name) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setInt(1, thread.threadId)
                statement.setInt(2, thread.process.processId)
                statement.setString(3, "<unknown-thread:${thread.threadId}>")
                statement.executeUpdate()
            }
    }

    private fun insertMarker(marker: ProfileMarkerFact) {
        val threadRowId = marker.thread?.let { thread -> canonicalThreadRow(marker.sourceId, thread, "marker") }
        connection
            .prepareStatement(
                "INSERT INTO profile_marker(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                    "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, " +
                    "schema_name, name, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, marker.sourceId.value)
                statement.setObject(2, threadRowId)
                statement.bindTime(3, marker.start)
                statement.bindTime(6, marker.end)
                statement.setString(9, marker.schema)
                statement.setString(10, marker.name)
                statement.setString(11, marker.payloadJson)
                statement.executeUpdate()
            }
    }

    private fun insertCounter(counter: ProfileCounterFact) {
        connection
            .prepareStatement(
                "INSERT INTO profile_counter(source_id, timestamp_nanos, clock_domain, time_error_bound_nanos, " +
                    "name, unit, value) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, counter.sourceId.value)
                statement.bindTime(2, counter.time)
                statement.setString(5, counter.name)
                statement.setString(6, counter.unit)
                statement.setDouble(7, counter.value)
                statement.executeUpdate()
            }
    }

    private fun insertSlice(slice: ProfileSliceFact) {
        val threadRowId = slice.thread?.let { thread -> canonicalThreadRow(slice.sourceId, thread, "slice") }
        connection
            .prepareStatement(
                "INSERT INTO profile_slice(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                    "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, name, " +
                    "category_name, subcategory_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, slice.sourceId.value)
                statement.setObject(2, threadRowId)
                statement.bindTime(3, slice.start)
                statement.bindTime(6, slice.end)
                statement.setString(9, slice.name)
                statement.setString(10, slice.category?.name)
                statement.setString(11, slice.category?.subcategory)
                statement.executeUpdate()
            }
    }

    private fun insertScreenshot(screenshot: ProfileScreenshotFact) {
        connection
            .prepareStatement(
                "INSERT INTO profile_screenshot(source_id, timestamp_nanos, clock_domain, " +
                    "time_error_bound_nanos, artifact_path) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, screenshot.sourceId.value)
                statement.bindTime(2, screenshot.time)
                statement.setString(5, screenshot.artifactPath)
                statement.executeUpdate()
            }
    }

    private fun canonicalThreadRow(
        sourceId: ProfileSourceId,
        thread: ProfileThreadKey,
        recordType: String,
    ): Long {
        validateThreadSource(sourceId, thread, recordType)
        return ensureCanonicalThread(thread)
    }

    private fun validateThreadSource(
        sourceId: ProfileSourceId,
        thread: ProfileThreadKey,
        recordType: String,
    ) {
        validateThreadKey(thread)
        require(sourceId == thread.sourceId) {
            "$recordType source ${sourceId.value} does not match thread source ${thread.sourceId.value}"
        }
    }

    private fun validateThreadKey(thread: ProfileThreadKey) {
        require(thread.sourceId == thread.process.sourceId) {
            "thread source ${thread.sourceId.value} does not match process source ${thread.process.sourceId.value}"
        }
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

    private fun cleanupFailedImport(primary: Throwable) {
        finished = true
        captureCleanupFailure(primary) { connection.rollback() }
        captureCleanupFailure(primary) { connection.autoCommit = true }
        captureCleanupFailure(primary) { insertSample.close() }
    }

    private fun closeResourcesAfterSuccess() {
        var failure: Throwable? = null
        failure = captureCleanupFailure(failure) { connection.autoCommit = true }
        failure = captureCleanupFailure(failure) { insertSample.close() }
        failure?.let { throw it }
    }
}

private data class CanonicalSampleReference(
    val sourceId: String,
    val processRowId: Long,
    val threadRowId: Long,
    val clockDomain: String,
    val errorBoundNanos: Long,
)

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

private fun PreparedStatement.bindTime(
    startIndex: Int,
    time: ProfileTimePoint?,
) {
    setObject(startIndex, time?.timestampNanos)
    setString(startIndex + 1, time?.clockDomain?.value)
    setObject(startIndex + 2, time?.errorBoundNanos)
}

@Suppress("TooGenericExceptionCaught")
private fun captureCleanupFailure(
    primary: Throwable?,
    cleanup: () -> Unit,
): Throwable? =
    try {
        cleanup()
        primary
    } catch (failure: Throwable) {
        if (primary == null) failure else primary.apply { addSuppressed(failure) }
    }

private fun ProfileFrame.isUnknown(): Boolean = symbolName.startsWith("<unknown") || filePath.startsWith("<unknown")
