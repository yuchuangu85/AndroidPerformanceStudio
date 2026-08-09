@file:Suppress(
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "ktlint:standard:max-line-length",
)

package com.androidperformancestudio.battery.storage

import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.battery.model.BatterySessionStatus
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.battery.model.batteryDeviceLocalId
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

public data class StoredBatterySession(
    val id: String,
    val packageName: String,
    val uid: Int,
    val createdAt: String,
    val runCount: Int,
    val status: BatterySessionStatus,
)

public class SqliteBatterySessionStore private constructor(
    private val connection: Connection,
) : AutoCloseable {
    public fun save(
        session: BatterySession,
        runs: List<BatteryRun>,
        deltas: List<BatteryRunDelta>,
    ) {
        connection.autoCommit = false
        try {
            saveSession(session, BatterySessionStatus.COMPLETED)
            runs.forEach(::insertRun)
            deltas.forEach(::saveDelta)
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    public fun begin(session: BatterySession) {
        saveSession(session, BatterySessionStatus.RUNNING)
    }

    public fun saveRun(
        session: BatterySession,
        run: BatteryRun,
        delta: BatteryRunDelta,
    ) {
        connection.autoCommit = false
        try {
            saveSession(session, BatterySessionStatus.RUNNING)
            insertRun(run)
            saveDelta(delta)
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    public fun markStatus(
        sessionId: String,
        status: BatterySessionStatus,
    ) {
        connection.prepareStatement("UPDATE battery_sessions SET status=? WHERE id=?").use { statement ->
            statement.setString(1, status.name)
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
    }

    public fun interruptRunningSessions(): Int =
        connection.prepareStatement("UPDATE battery_sessions SET status=? WHERE status=?").use { statement ->
            statement.setString(1, BatterySessionStatus.INTERRUPTED.name)
            statement.setString(2, BatterySessionStatus.RUNNING.name)
            statement.executeUpdate()
        }

    public fun listSessions(limit: Int = 50): List<StoredBatterySession> {
        require(limit in 1..1000) { "limit must be between 1 and 1000" }
        return connection
            .prepareStatement(
                "SELECT s.id, s.package_name, s.uid, s.created_at, COUNT(r.id), s.status FROM battery_sessions s LEFT JOIN battery_runs r ON r.session_id=s.id GROUP BY s.id ORDER BY s.created_at DESC LIMIT ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                StoredBatterySession(
                                    rows.getString(1),
                                    rows.getString(2),
                                    rows.getInt(3),
                                    rows.getString(4),
                                    rows.getInt(5),
                                    BatterySessionStatus.valueOf(rows.getString(6)),
                                ),
                            )
                        }
                    }
                }
            }
    }

    private fun saveSession(
        session: BatterySession,
        status: BatterySessionStatus,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO battery_sessions
                (id, device_serial, package_name, uid, attribution_scope, capture_mode, capability_level, created_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET status=excluded.status
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, session.id)
                statement.setString(2, batteryDeviceLocalId(session.deviceSerial).value)
                statement.setString(3, session.packageName)
                statement.setInt(4, session.uid)
                statement.setString(5, session.attributionScope.name)
                statement.setString(6, session.config.mode.name)
                statement.setString(7, session.capabilities.level.name)
                statement.setString(8, session.createdAt.toString())
                statement.setString(9, status.name)
                statement.executeUpdate()
            }
    }

    private fun insertRun(run: BatteryRun) {
        connection
            .prepareStatement(
                "INSERT OR REPLACE INTO battery_runs (id, session_id, iteration, started_at, ended_at, sample_count) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, run.id)
                statement.setString(2, run.sessionId)
                statement.setInt(3, run.iteration)
                statement.setString(4, run.baseline.capturedAt.toString())
                statement.setString(5, run.finalSnapshot.capturedAt.toString())
                statement.setInt(6, run.samples.size)
                statement.executeUpdate()
            }
        (listOf(run.baseline) + run.samples + run.finalSnapshot).forEach { snapshot ->
            connection
                .prepareStatement(
                    "INSERT OR REPLACE INTO battery_snapshots (id, run_id, sequence, captured_at, stats_period_id, boot_id, level, temperature, powered, checkin, report, battery, history, warnings, conditions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, snapshot.id)
                    statement.setString(2, run.id)
                    statement.setInt(3, snapshot.sequence)
                    statement.setString(4, snapshot.capturedAt.toString())
                    statement.setString(5, snapshot.statsPeriodId)
                    statement.setString(6, snapshot.bootId)
                    statement.setNullableInt(7, snapshot.deviceState.levelPercent)
                    statement.setNullableInt(8, snapshot.deviceState.temperatureTenthsCelsius)
                    statement.setNullableInt(9, snapshot.deviceState.powered?.let { if (it) 1 else 0 })
                    statement.setString(10, snapshot.rawEvidence.checkin)
                    statement.setString(11, snapshot.rawEvidence.report)
                    statement.setString(12, snapshot.rawEvidence.battery)
                    statement.setString(13, snapshot.rawEvidence.history)
                    statement.setString(14, snapshot.warnings.joinToString("\n"))
                    statement.setString(
                        15,
                        snapshot.conditions.entries.joinToString("\n") { (key, value) -> "$key=${value.replace("\n", " ")}" },
                    )
                    statement.executeUpdate()
                }
        }
    }

    private fun saveDelta(delta: BatteryRunDelta) {
        connection.prepareStatement("DELETE FROM battery_resources WHERE run_id=?").use { statement ->
            statement.setString(1, delta.runId)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM battery_energy WHERE run_id=?").use { statement ->
            statement.setString(1, delta.runId)
            statement.executeUpdate()
        }
        connection
            .prepareStatement(
                "INSERT OR REPLACE INTO battery_deltas (run_id, duration_ms, total_network_bytes, warnings) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, delta.runId)
                statement.setLong(2, delta.durationMs)
                statement.setLong(3, delta.network.totalBytes)
                statement.setString(4, delta.warnings.joinToString("\n"))
                statement.executeUpdate()
            }
        saveTimers(delta.runId, "wakelock", delta.wakelocks)
        saveTimers(delta.runId, "alarm", delta.alarms)
        saveTimers(delta.runId, "job", delta.jobs)
        saveTimers(delta.runId, "sensor", delta.sensors)
        delta.energy.forEach { energy ->
            connection
                .prepareStatement(
                    "INSERT INTO battery_energy (run_id, component, energy_mah, energy_uws, source, scope, confidence) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, delta.runId)
                    statement.setString(2, energy.component)
                    statement.setNullableDouble(3, energy.energyMah)
                    statement.setNullableLong(4, energy.energyUws)
                    statement.setString(5, energy.source.name)
                    statement.setString(6, energy.attributionScope.name)
                    statement.setString(7, energy.confidence.name)
                    statement.executeUpdate()
                }
        }
    }

    private fun saveTimers(
        runId: String,
        kind: String,
        timers: List<ResourceTimer>,
    ) {
        timers.forEach { timer ->
            connection
                .prepareStatement(
                    "INSERT INTO battery_resources (run_id, kind, name, duration_ms, count, confidence) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, runId)
                    statement.setString(2, kind)
                    statement.setString(3, timer.name)
                    statement.setLong(4, timer.durationMs)
                    statement.setLong(5, timer.count)
                    statement.setString(6, timer.confidence.name)
                    statement.executeUpdate()
                }
        }
    }

    override fun close() = connection.close()

    public companion object {
        public fun open(path: Path): SqliteBatterySessionStore {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            createSchema(connection)
            return SqliteBatterySessionStore(connection)
        }

        private fun createSchema(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS battery_sessions (id TEXT PRIMARY KEY, device_serial TEXT NOT NULL, package_name TEXT NOT NULL, uid INTEGER NOT NULL, attribution_scope TEXT NOT NULL, capture_mode TEXT NOT NULL, capability_level TEXT NOT NULL, created_at TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'COMPLETED')",
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS battery_runs (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES battery_sessions(id) ON DELETE CASCADE, iteration INTEGER NOT NULL, started_at TEXT NOT NULL, ended_at TEXT NOT NULL, sample_count INTEGER NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS battery_snapshots (id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES battery_runs(id) ON DELETE CASCADE, sequence INTEGER NOT NULL, captured_at TEXT NOT NULL, stats_period_id TEXT, boot_id TEXT, level INTEGER, temperature INTEGER, powered INTEGER, checkin TEXT NOT NULL, report TEXT NOT NULL, battery TEXT NOT NULL, history TEXT, warnings TEXT NOT NULL, conditions TEXT NOT NULL DEFAULT '')",
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS battery_deltas (run_id TEXT PRIMARY KEY REFERENCES battery_runs(id) ON DELETE CASCADE, duration_ms INTEGER NOT NULL, total_network_bytes INTEGER NOT NULL, warnings TEXT NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS battery_resources (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES battery_runs(id) ON DELETE CASCADE, kind TEXT NOT NULL, name TEXT NOT NULL, duration_ms INTEGER NOT NULL, count INTEGER NOT NULL, confidence TEXT NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS battery_energy (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES battery_runs(id) ON DELETE CASCADE, component TEXT NOT NULL, energy_mah REAL, energy_uws INTEGER, source TEXT NOT NULL, scope TEXT NOT NULL, confidence TEXT NOT NULL)",
                )
                if (!connection.hasColumn("battery_sessions", "status")) {
                    statement.execute("ALTER TABLE battery_sessions ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED'")
                }
                if (!connection.hasColumn("battery_snapshots", "conditions")) {
                    statement.execute("ALTER TABLE battery_snapshots ADD COLUMN conditions TEXT NOT NULL DEFAULT ''")
                }
            }
        }
    }
}

private fun Connection.hasColumn(
    table: String,
    column: String,
): Boolean =
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { rows ->
            generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == column }
        }
    }

private fun PreparedStatement.setNullableInt(
    index: Int,
    value: Int?,
) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}

private fun PreparedStatement.setNullableLong(
    index: Int,
    value: Long?,
) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}

private fun PreparedStatement.setNullableDouble(
    index: Int,
    value: Double?,
) {
    if (value == null) setNull(index, Types.DOUBLE) else setDouble(index, value)
}
