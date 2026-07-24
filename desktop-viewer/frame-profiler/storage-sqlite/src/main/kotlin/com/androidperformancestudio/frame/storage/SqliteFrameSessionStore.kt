@file:Suppress("MagicNumber", "MaxLineLength", "TooGenericExceptionCaught", "TooManyFunctions")

package com.androidperformancestudio.frame.storage

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameStages
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant

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
                    imported_file TEXT
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS frame_sample (
                    session_id TEXT NOT NULL,
                    frame_id INTEGER NOT NULL,
                    intended_vsync_ns INTEGER,
                    actual_vsync_ns INTEGER,
                    frame_completed_ns INTEGER,
                    present_ns INTEGER,
                    expected_duration_ns INTEGER,
                    expected_duration_source TEXT NOT NULL,
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
                    eligible_for_jank INTEGER NOT NULL,
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
                SELECT session_id, source, started_at_epoch_millis, package_name, device_serial, imported_file
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

    private fun upsertSession(session: FrameCaptureSession) {
        connection
            .prepareStatement(
                """
                INSERT INTO frame_session(session_id, source, started_at_epoch_millis, package_name, device_serial, imported_file)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    source = excluded.source,
                    started_at_epoch_millis = excluded.started_at_epoch_millis,
                    package_name = excluded.package_name,
                    device_serial = excluded.device_serial,
                    imported_file = excluded.imported_file
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, session.id)
                statement.setString(2, session.source.name)
                statement.setLong(3, session.startedAt.toEpochMilli())
                statement.setString(4, session.packageName)
                statement.setString(5, session.deviceSerial)
                statement.setString(6, session.importedFile)
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
                    session_id, frame_id, intended_vsync_ns, actual_vsync_ns, frame_completed_ns, present_ns,
                    expected_duration_ns, expected_duration_source, total_duration_ns, input_ns, animation_ns,
                    layout_measure_ns, draw_ns, sync_ns, command_issue_ns, swap_buffers_ns, gpu_ns,
                    platform_jank, eligible_for_jank, activity_name, window_id, layout_snapshot_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                frames.forEach { frame ->
                    statement.setString(1, frame.sessionId)
                    statement.setLong(2, frame.frameId)
                    statement.setNullableLong(3, frame.intendedVsyncNs)
                    statement.setNullableLong(4, frame.actualVsyncNs)
                    statement.setNullableLong(5, frame.frameCompletedNs)
                    statement.setNullableLong(6, frame.presentNs)
                    statement.setNullableLong(7, frame.expectedDurationNs)
                    statement.setString(8, frame.expectedDurationSource.name)
                    statement.setNullableLong(9, frame.totalDurationNs)
                    statement.setNullableLong(10, frame.stages.inputNs)
                    statement.setNullableLong(11, frame.stages.animationNs)
                    statement.setNullableLong(12, frame.stages.layoutMeasureNs)
                    statement.setNullableLong(13, frame.stages.drawNs)
                    statement.setNullableLong(14, frame.stages.syncNs)
                    statement.setNullableLong(15, frame.stages.commandIssueNs)
                    statement.setNullableLong(16, frame.stages.swapBuffersNs)
                    statement.setNullableLong(17, frame.stages.gpuNs)
                    if (frame.platformJank == null) {
                        statement.setNull(18, Types.INTEGER)
                    } else {
                        statement.setInt(18, if (frame.platformJank == true) 1 else 0)
                    }
                    statement.setInt(19, if (frame.eligibleForJank) 1 else 0)
                    statement.setString(20, frame.activityName)
                    statement.setString(21, frame.windowId)
                    statement.setString(22, frame.layoutSnapshotId)
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
            importedFile = getString("imported_file"),
        )

    private fun ResultSet.toFrame(
        states: Map<String, String>,
        source: FrameSource,
        packageName: String?,
    ): FrameSample =
        FrameSample(
            frameId = getLong("frame_id"),
            sessionId = getString("session_id"),
            source = source,
            packageName = packageName,
            intendedVsyncNs = nullableLong("intended_vsync_ns"),
            actualVsyncNs = nullableLong("actual_vsync_ns"),
            frameCompletedNs = nullableLong("frame_completed_ns"),
            presentNs = nullableLong("present_ns"),
            expectedDurationNs = nullableLong("expected_duration_ns"),
            expectedDurationSource = ExpectedDurationSource.valueOf(getString("expected_duration_source")),
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
            eligibleForJank = getInt("eligible_for_jank") != 0,
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

    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

    private fun ResultSet.nullableBoolean(column: String): Boolean? = getInt(column).let { if (wasNull()) null else it != 0 }

    public companion object {
        public fun open(databaseFile: Path): SqliteFrameSessionStore {
            databaseFile.parent?.let(Files::createDirectories)
            return SqliteFrameSessionStore(DriverManager.getConnection("jdbc:sqlite:$databaseFile"))
        }
    }
}
