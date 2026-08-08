@file:Suppress("MagicNumber", "MaxLineLength", "TooGenericExceptionCaught", "TooManyFunctions")

package com.androidperformancestudio.frame.storage

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameSourceCapabilities
import com.androidperformancestudio.frame.model.FrameStages
import com.androidperformancestudio.frame.model.JankType
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.Base64

public class SqliteFrameSessionStore private constructor(
    private val connection: Connection,
) : AutoCloseable {
    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS frame_session (
                    session_id TEXT PRIMARY KEY NOT NULL,
                    source TEXT NOT NULL,
                    started_at_epoch_millis INTEGER NOT NULL,
                    package_name TEXT,
                    device_serial TEXT,
                    device_api_level INTEGER,
                    agent_protocol TEXT,
                    source_capabilities TEXT,
                    observed_refresh_rates_hz TEXT NOT NULL DEFAULT '',
                    imported_file TEXT,
                    imported_file_sha256 TEXT,
                    imported_at_epoch_millis INTEGER,
                    provenance_complete INTEGER NOT NULL DEFAULT 1,
                    provenance_warnings TEXT NOT NULL DEFAULT '',
                    perfetto_trace_file TEXT
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS frame_sample (
                    session_id TEXT NOT NULL,
                    frame_id INTEGER NOT NULL,
                    frame_source TEXT,
                    package_name TEXT,
                    process_id INTEGER,
                    intended_vsync_ns INTEGER,
                    actual_vsync_ns INTEGER,
                    frame_completed_ns INTEGER,
                    present_ns INTEGER,
                    expected_duration_ns INTEGER,
                    expected_duration_source TEXT NOT NULL,
                    refresh_rate_hz REAL,
                    frame_timeline_vsync_id INTEGER,
                    total_duration_ns INTEGER,
                    input_ns INTEGER,
                    animation_ns INTEGER,
                    layout_measure_ns INTEGER,
                    draw_ns INTEGER,
                    sync_ns INTEGER,
                    command_issue_ns INTEGER,
                    swap_buffers_ns INTEGER,
                    gpu_ns INTEGER,
                    platform_jank INTEGER,
                    platform_jank_types TEXT NOT NULL DEFAULT '',
                    platform_jank_rule_id TEXT,
                    platform_jank_rule_version TEXT,
                    eligible_for_jank INTEGER NOT NULL,
                    dropped_before_sample INTEGER NOT NULL DEFAULT 0,
                    activity_name TEXT,
                    window_id TEXT,
                    layout_snapshot_id TEXT,
                    PRIMARY KEY(session_id, frame_id),
                    FOREIGN KEY(session_id) REFERENCES frame_session(session_id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS frame_state (
                    session_id TEXT NOT NULL,
                    frame_id INTEGER NOT NULL,
                    state_key TEXT NOT NULL,
                    state_value TEXT NOT NULL,
                    PRIMARY KEY(session_id, frame_id, state_key),
                    FOREIGN KEY(session_id, frame_id) REFERENCES frame_sample(session_id, frame_id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS frame_sample_timeline_idx
                ON frame_sample(session_id, intended_vsync_ns)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS frame_sample_window_timeline_idx
                ON frame_sample(session_id, window_id, intended_vsync_ns)
                """.trimIndent(),
            )
            ensureCurrentColumns()
            statement.execute("PRAGMA user_version = 3")
        }
    }

    public fun save(
        session: FrameCaptureSession,
        frames: List<FrameSample>,
    ) {
        require(frames.all { it.sessionId == session.id }) { "Every frame must belong to the saved session" }
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            upsertSession(session)
            deleteFrames(session.id)
            insertFrames(frames)
            insertStates(frames)
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    public fun findSession(sessionId: String): FrameCaptureSession? =
        connection
            .prepareStatement(
                """
                SELECT *
                FROM frame_session WHERE session_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { result -> if (result.next()) result.toSession() else null }
            }

    public fun loadFrames(sessionId: String): List<FrameSample> {
        val states = loadStates(sessionId)
        val session = findSession(sessionId) ?: return emptyList()
        return connection
            .prepareStatement(
                """
                SELECT * FROM frame_sample
                WHERE session_id = ?
                ORDER BY intended_vsync_ns, frame_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                result.toFrame(
                                    states = states[result.getLong("frame_id")].orEmpty(),
                                    source = session.source,
                                    packageName = session.packageName,
                                ),
                            )
                        }
                    }
                }
            }
    }

    override fun close() {
        connection.close()
    }

    private fun ensureCurrentColumns() {
        ensureColumns(
            "frame_session",
            mapOf(
                "device_api_level" to "INTEGER",
                "agent_protocol" to "TEXT",
                "source_capabilities" to "TEXT",
                "observed_refresh_rates_hz" to "TEXT NOT NULL DEFAULT ''",
                "imported_file_sha256" to "TEXT",
                "imported_at_epoch_millis" to "INTEGER",
                "provenance_complete" to "INTEGER NOT NULL DEFAULT 1",
                "provenance_warnings" to "TEXT NOT NULL DEFAULT ''",
                "perfetto_trace_file" to "TEXT",
            ),
        )
        ensureColumns(
            "frame_sample",
            mapOf(
                "frame_source" to "TEXT",
                "package_name" to "TEXT",
                "process_id" to "INTEGER",
                "refresh_rate_hz" to "REAL",
                "frame_timeline_vsync_id" to "INTEGER",
                "platform_jank_types" to "TEXT NOT NULL DEFAULT ''",
                "platform_jank_rule_id" to "TEXT",
                "platform_jank_rule_version" to "TEXT",
                "dropped_before_sample" to "INTEGER NOT NULL DEFAULT 0",
            ),
        )
    }

    private fun ensureColumns(
        table: String,
        definitions: Map<String, String>,
    ) {
        val existing =
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { result ->
                    buildSet { while (result.next()) add(result.getString("name")) }
                }
            }
        definitions.filterKeys { it !in existing }.forEach { (name, definition) ->
            connection.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $name $definition") }
        }
    }

    private fun upsertSession(session: FrameCaptureSession) {
        connection
            .prepareStatement(
                """
                INSERT INTO frame_session(
                    session_id, source, started_at_epoch_millis, package_name, device_serial, device_api_level,
                    agent_protocol, source_capabilities, observed_refresh_rates_hz, imported_file,
                    imported_file_sha256, imported_at_epoch_millis, provenance_complete, provenance_warnings,
                    perfetto_trace_file
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    source = excluded.source,
                    started_at_epoch_millis = excluded.started_at_epoch_millis,
                    package_name = excluded.package_name,
                    device_serial = excluded.device_serial,
                    device_api_level = excluded.device_api_level,
                    agent_protocol = excluded.agent_protocol,
                    source_capabilities = excluded.source_capabilities,
                    observed_refresh_rates_hz = excluded.observed_refresh_rates_hz,
                    imported_file = excluded.imported_file,
                    imported_file_sha256 = excluded.imported_file_sha256,
                    imported_at_epoch_millis = excluded.imported_at_epoch_millis,
                    provenance_complete = excluded.provenance_complete,
                    provenance_warnings = excluded.provenance_warnings,
                    perfetto_trace_file = excluded.perfetto_trace_file
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, session.id)
                statement.setString(2, session.source.name)
                statement.setLong(3, session.startedAt.toEpochMilli())
                statement.setString(4, session.packageName)
                statement.setString(5, session.deviceSerial)
                statement.setNullableInt(6, session.deviceApiLevel)
                statement.setString(7, session.agentProtocol)
                statement.setString(8, session.sourceCapabilities?.encode())
                statement.setString(9, session.observedRefreshRatesHz.sorted().joinToString(","))
                statement.setString(10, session.importedFile)
                statement.setString(11, session.importedFileSha256)
                statement.setNullableLong(12, session.importedAt?.toEpochMilli())
                statement.setInt(13, if (session.provenanceComplete) 1 else 0)
                statement.setString(14, session.provenanceWarnings.encodeStrings())
                statement.setString(15, session.perfettoTraceFile)
                statement.executeUpdate()
            }
    }

    private fun deleteFrames(sessionId: String) {
        connection.prepareStatement("DELETE FROM frame_sample WHERE session_id = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
    }

    private fun insertFrames(frames: List<FrameSample>) {
        connection
            .prepareStatement(
                """
                INSERT INTO frame_sample(
                    session_id, frame_id, frame_source, package_name, process_id,
                    intended_vsync_ns, actual_vsync_ns, frame_completed_ns, present_ns,
                    expected_duration_ns, expected_duration_source, refresh_rate_hz, frame_timeline_vsync_id,
                    total_duration_ns, input_ns, animation_ns,
                    layout_measure_ns, draw_ns, sync_ns, command_issue_ns, swap_buffers_ns, gpu_ns,
                    platform_jank, platform_jank_types, platform_jank_rule_id, platform_jank_rule_version,
                    eligible_for_jank, dropped_before_sample,
                    activity_name, window_id, layout_snapshot_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                frames.forEach { frame ->
                    statement.setString(1, frame.sessionId)
                    statement.setLong(2, frame.frameId)
                    statement.setString(3, frame.source.name)
                    statement.setString(4, frame.packageName)
                    statement.setNullableInt(5, frame.processId)
                    statement.setNullableLong(6, frame.intendedVsyncNs)
                    statement.setNullableLong(7, frame.actualVsyncNs)
                    statement.setNullableLong(8, frame.frameCompletedNs)
                    statement.setNullableLong(9, frame.presentNs)
                    statement.setNullableLong(10, frame.expectedDurationNs)
                    statement.setString(11, frame.expectedDurationSource.name)
                    statement.setNullableDouble(12, frame.refreshRateHz)
                    statement.setNullableLong(13, frame.frameTimelineVsyncId)
                    statement.setNullableLong(14, frame.totalDurationNs)
                    statement.setNullableLong(15, frame.stages.inputNs)
                    statement.setNullableLong(16, frame.stages.animationNs)
                    statement.setNullableLong(17, frame.stages.layoutMeasureNs)
                    statement.setNullableLong(18, frame.stages.drawNs)
                    statement.setNullableLong(19, frame.stages.syncNs)
                    statement.setNullableLong(20, frame.stages.commandIssueNs)
                    statement.setNullableLong(21, frame.stages.swapBuffersNs)
                    statement.setNullableLong(22, frame.stages.gpuNs)
                    if (frame.platformJank == null) {
                        statement.setNull(23, Types.INTEGER)
                    } else {
                        statement.setInt(23, if (frame.platformJank == true) 1 else 0)
                    }
                    statement.setString(24, frame.platformJankTypes.sortedBy { it.name }.joinToString(",") { it.name })
                    statement.setString(25, frame.platformJankRuleId)
                    statement.setString(26, frame.platformJankRuleVersion)
                    statement.setInt(27, if (frame.eligibleForJank) 1 else 0)
                    statement.setLong(28, frame.droppedBeforeSample)
                    statement.setString(29, frame.activityName)
                    statement.setString(30, frame.windowId)
                    statement.setString(31, frame.layoutSnapshotId)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }

    private fun insertStates(frames: List<FrameSample>) {
        connection
            .prepareStatement(
                "INSERT INTO frame_state(session_id, frame_id, state_key, state_value) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                frames.forEach { frame ->
                    frame.states.forEach { (key, value) ->
                        statement.setString(1, frame.sessionId)
                        statement.setLong(2, frame.frameId)
                        statement.setString(3, key)
                        statement.setString(4, value)
                        statement.addBatch()
                    }
                }
                statement.executeBatch()
            }
    }

    private fun loadStates(sessionId: String): Map<Long, Map<String, String>> =
        connection
            .prepareStatement(
                "SELECT frame_id, state_key, state_value FROM frame_state WHERE session_id = ?",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { result ->
                    val states = mutableMapOf<Long, MutableMap<String, String>>()
                    while (result.next()) {
                        states.getOrPut(result.getLong("frame_id"), ::mutableMapOf)[result.getString("state_key")] =
                            result.getString("state_value")
                    }
                    states
                }
            }

    private fun ResultSet.toSession(): FrameCaptureSession =
        FrameCaptureSession(
            id = getString("session_id"),
            source = FrameSource.valueOf(getString("source")),
            startedAt = Instant.ofEpochMilli(getLong("started_at_epoch_millis")),
            packageName = getString("package_name"),
            deviceSerial = getString("device_serial"),
            deviceApiLevel = nullableInt("device_api_level"),
            agentProtocol = getString("agent_protocol"),
            sourceCapabilities = getString("source_capabilities")?.decodeCapabilities(),
            observedRefreshRatesHz =
                getString(
                    "observed_refresh_rates_hz",
                ).orEmpty().splitNotBlank(',').mapNotNull(String::toDoubleOrNull).toSet(),
            importedFile = getString("imported_file"),
            importedFileSha256 = getString("imported_file_sha256"),
            importedAt = nullableLong("imported_at_epoch_millis")?.let(Instant::ofEpochMilli),
            provenanceComplete = getInt("provenance_complete") != 0,
            provenanceWarnings = getString("provenance_warnings").orEmpty().decodeStrings(),
            perfettoTraceFile = getString("perfetto_trace_file"),
        )

    private fun ResultSet.toFrame(
        states: Map<String, String>,
        source: FrameSource,
        packageName: String?,
    ): FrameSample =
        FrameSample(
            frameId = getLong("frame_id"),
            sessionId = getString("session_id"),
            source = getString("frame_source")?.let(FrameSource::valueOf) ?: source,
            packageName = getString("package_name") ?: packageName,
            processId = nullableInt("process_id"),
            intendedVsyncNs = nullableLong("intended_vsync_ns"),
            actualVsyncNs = nullableLong("actual_vsync_ns"),
            frameCompletedNs = nullableLong("frame_completed_ns"),
            presentNs = nullableLong("present_ns"),
            expectedDurationNs = nullableLong("expected_duration_ns"),
            expectedDurationSource = ExpectedDurationSource.valueOf(getString("expected_duration_source")),
            refreshRateHz = nullableDouble("refresh_rate_hz"),
            frameTimelineVsyncId = nullableLong("frame_timeline_vsync_id"),
            totalDurationNs = nullableLong("total_duration_ns"),
            stages =
                FrameStages(
                    inputNs = nullableLong("input_ns"),
                    animationNs = nullableLong("animation_ns"),
                    layoutMeasureNs = nullableLong("layout_measure_ns"),
                    drawNs = nullableLong("draw_ns"),
                    syncNs = nullableLong("sync_ns"),
                    commandIssueNs = nullableLong("command_issue_ns"),
                    swapBuffersNs = nullableLong("swap_buffers_ns"),
                    gpuNs = nullableLong("gpu_ns"),
                ),
            platformJank = nullableBoolean("platform_jank"),
            platformJankTypes =
                getString("platform_jank_types")
                    .orEmpty()
                    .splitNotBlank(',')
                    .mapTo(linkedSetOf(), JankType::valueOf),
            platformJankRuleId = getString("platform_jank_rule_id"),
            platformJankRuleVersion = getString("platform_jank_rule_version"),
            eligibleForJank = getInt("eligible_for_jank") != 0,
            droppedBeforeSample = getLong("dropped_before_sample"),
            activityName = getString("activity_name"),
            windowId = getString("window_id"),
            layoutSnapshotId = getString("layout_snapshot_id"),
            states = states,
        )

    private fun java.sql.PreparedStatement.setNullableLong(
        index: Int,
        value: Long?,
    ) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun java.sql.PreparedStatement.setNullableInt(
        index: Int,
        value: Int?,
    ) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private fun java.sql.PreparedStatement.setNullableDouble(
        index: Int,
        value: Double?,
    ) {
        if (value == null) setNull(index, Types.DOUBLE) else setDouble(index, value)
    }

    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).let { if (wasNull()) null else it }

    private fun ResultSet.nullableDouble(column: String): Double? = getDouble(column).let { if (wasNull()) null else it }

    private fun ResultSet.nullableBoolean(column: String): Boolean? = getInt(column).let { if (wasNull()) null else it != 0 }

    public companion object {
        public fun open(databaseFile: Path): SqliteFrameSessionStore {
            databaseFile.parent?.let(Files::createDirectories)
            return SqliteFrameSessionStore(DriverManager.getConnection("jdbc:sqlite:$databaseFile"))
        }
    }
}

private fun FrameSourceCapabilities.encode(): String =
    listOf(realtime, stageBreakdown, platformJankClassification, expectedFrameDeadline, appStateLabels)
        .joinToString("") { if (it) "1" else "0" }

private fun String.decodeCapabilities(): FrameSourceCapabilities? =
    takeIf { it.length == 5 && it.all { character -> character == '0' || character == '1' } }?.let {
        FrameSourceCapabilities(it[0] == '1', it[1] == '1', it[2] == '1', it[3] == '1', it[4] == '1')
    }

private fun List<String>.encodeStrings(): String =
    joinToString(",") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }

private fun String.decodeStrings(): List<String> = splitNotBlank(',').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }

private fun String.splitNotBlank(delimiter: Char): List<String> =
    if (isBlank()) emptyList() else split(delimiter).filter(String::isNotBlank)
