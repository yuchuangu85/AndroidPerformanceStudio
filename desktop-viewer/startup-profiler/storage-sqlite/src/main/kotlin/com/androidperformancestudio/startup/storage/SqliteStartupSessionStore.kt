@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength", "TooGenericExceptionCaught", "TooManyFunctions")

package com.androidperformancestudio.startup.storage

import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSession
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

public data class StoredStartupSession(
    val id: String,
    val packageName: String,
    val componentName: String,
    val createdAt: String,
    val runCount: Int,
)

public class SqliteStartupSessionStore private constructor(
    private val connection: Connection,
) : AutoCloseable {
    public fun save(
        session: StartupSession,
        runs: List<StartupRun>,
    ) {
        connection.autoCommit = false
        try {
            connection
                .prepareStatement(
                    """
                    INSERT OR REPLACE INTO startup_sessions
                    (id, device_serial, package_name, component_name, requested_type, compilation_mode,
                     warmup_runs, measured_runs, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, session.id)
                    statement.setString(2, session.deviceSerial)
                    statement.setString(3, session.packageName)
                    statement.setString(4, session.componentName)
                    statement.setString(5, session.requestedType.name)
                    statement.setString(6, session.compilationMode.name)
                    statement.setInt(7, session.warmupRuns)
                    statement.setInt(8, session.measuredRuns)
                    statement.setString(9, session.createdAt.toString())
                    statement.executeUpdate()
                }
            runs.forEach(::saveRun)
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    public fun listSessions(limit: Int = 50): List<StoredStartupSession> {
        require(limit in 1..1000) { "limit must be between 1 and 1000" }
        return connection
            .prepareStatement(
                """
                SELECT s.id, s.package_name, s.component_name, s.created_at, COUNT(r.id)
                FROM startup_sessions s LEFT JOIN startup_runs r ON r.session_id = s.id
                GROUP BY s.id ORDER BY s.created_at DESC LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                StoredStartupSession(
                                    rows.getString(1),
                                    rows.getString(2),
                                    rows.getString(3),
                                    rows.getString(4),
                                    rows.getInt(5),
                                ),
                            )
                        }
                    }
                }
            }
    }

    private fun saveRun(run: StartupRun) {
        connection
            .prepareStatement(
                """
                INSERT OR REPLACE INTO startup_runs
                (id, session_id, iteration, requested_type, observed_type, status, launch_state, activity,
                 this_time_ms, total_time_ms, wait_time_ms, displayed_time_ms, fully_drawn_time_ms,
                 complete, pid_before, pid_after, warnings, am_start_output, event_log_output, compilation_output, agent_available)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, run.id)
                statement.setString(2, run.sessionId)
                statement.setInt(3, run.iteration)
                statement.setString(4, run.requestedType.name)
                statement.setString(5, run.observedType.name)
                statement.setString(6, run.platform.status)
                statement.setString(7, run.platform.launchState)
                statement.setString(8, run.platform.activity)
                statement.setNullableLong(9, run.platform.thisTimeMs)
                statement.setNullableLong(10, run.platform.totalTimeMs)
                statement.setNullableLong(11, run.platform.waitTimeMs)
                statement.setNullableLong(12, run.platform.displayedTimeMs)
                statement.setNullableLong(13, run.platform.fullyDrawnTimeMs)
                statement.setInt(14, if (run.platform.complete) 1 else 0)
                statement.setNullableInt(15, run.processIdBefore)
                statement.setNullableInt(16, run.processIdAfter)
                statement.setString(17, run.warnings.joinToString("\n"))
                statement.setString(18, run.rawEvidence.amStartOutput)
                statement.setString(19, run.rawEvidence.eventLogOutput)
                statement.setString(20, run.rawEvidence.compilationOutput)
                statement.setInt(21, if (run.rawEvidence.agentAvailable) 1 else 0)
                statement.executeUpdate()
            }
        run.milestones.forEach { milestone ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO startup_milestones
                    (run_id, kind, elapsed_ns, duration_ms, source, confidence, activity, process_id, process_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, run.id)
                    statement.setString(2, milestone.kind.name)
                    statement.setNullableLong(3, milestone.elapsedRealtimeNs)
                    statement.setNullableLong(4, milestone.durationMs)
                    statement.setString(5, milestone.source.name)
                    statement.setString(6, milestone.confidence.name)
                    statement.setString(7, milestone.activityName)
                    statement.setNullableInt(8, milestone.processId)
                    statement.setString(9, milestone.processName)
                    statement.executeUpdate()
                }
        }
        run.phases.forEach { phase ->
            connection
                .prepareStatement(
                    "INSERT INTO startup_phases (run_id, name, start_kind, end_kind, duration_ns, confidence) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, run.id)
                    statement.setString(2, phase.name)
                    statement.setString(3, phase.start.name)
                    statement.setString(4, phase.end.name)
                    statement.setLong(5, phase.durationNs)
                    statement.setString(6, phase.confidence.name)
                    statement.executeUpdate()
                }
        }
    }

    override fun close() {
        connection.close()
    }

    public companion object {
        public fun open(path: Path): SqliteStartupSessionStore {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            createSchema(connection)
            return SqliteStartupSessionStore(connection)
        }

        private fun createSchema(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS startup_sessions (
                        id TEXT PRIMARY KEY, device_serial TEXT NOT NULL, package_name TEXT NOT NULL,
                        component_name TEXT NOT NULL, requested_type TEXT NOT NULL, compilation_mode TEXT NOT NULL,
                        warmup_runs INTEGER NOT NULL, measured_runs INTEGER NOT NULL, created_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS startup_runs (
                        id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES startup_sessions(id) ON DELETE CASCADE,
                        iteration INTEGER NOT NULL, requested_type TEXT NOT NULL, observed_type TEXT NOT NULL,
                        status TEXT, launch_state TEXT, activity TEXT, this_time_ms INTEGER, total_time_ms INTEGER,
                        wait_time_ms INTEGER, displayed_time_ms INTEGER, fully_drawn_time_ms INTEGER, complete INTEGER NOT NULL,
                        pid_before INTEGER, pid_after INTEGER, warnings TEXT NOT NULL, am_start_output TEXT NOT NULL,
                        event_log_output TEXT, compilation_output TEXT, agent_available INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS startup_milestones (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES startup_runs(id) ON DELETE CASCADE,
                        kind TEXT NOT NULL, elapsed_ns INTEGER, duration_ms INTEGER, source TEXT NOT NULL,
                        confidence TEXT NOT NULL, activity TEXT, process_id INTEGER, process_name TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS startup_phases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES startup_runs(id) ON DELETE CASCADE,
                        name TEXT NOT NULL, start_kind TEXT NOT NULL, end_kind TEXT NOT NULL,
                        duration_ns INTEGER NOT NULL, confidence TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}

private fun java.sql.PreparedStatement.setNullableLong(
    index: Int,
    value: Long?,
) {
    if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)
}

private fun java.sql.PreparedStatement.setNullableInt(
    index: Int,
    value: Int?,
) {
    if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
}
