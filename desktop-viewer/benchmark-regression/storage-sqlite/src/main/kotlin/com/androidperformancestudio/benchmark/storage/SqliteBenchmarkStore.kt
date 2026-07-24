package com.androidperformancestudio.benchmark.storage

import com.androidperformancestudio.benchmark.model.BenchmarkRun
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

public data class StoredBenchmarkRun(
    val id: String,
    val sourcePath: String,
    val importedAt: String,
    val deviceModel: String?,
    val caseCount: Int,
)

public class SqliteBenchmarkStore private constructor(
    private val connection: Connection,
) : AutoCloseable {
    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("CREATE TABLE IF NOT EXISTS benchmark_run(id TEXT PRIMARY KEY, source_path TEXT NOT NULL, imported_at TEXT NOT NULL, device_model TEXT, case_count INTEGER NOT NULL)")
            statement.execute("CREATE TABLE IF NOT EXISTS benchmark_metric(run_id TEXT NOT NULL, case_identity TEXT NOT NULL, metric_name TEXT NOT NULL, unit TEXT NOT NULL, median REAL, samples INTEGER NOT NULL, PRIMARY KEY(run_id, case_identity, metric_name), FOREIGN KEY(run_id) REFERENCES benchmark_run(id) ON DELETE CASCADE)")
        }
    }

    public fun save(run: BenchmarkRun) {
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT OR REPLACE INTO benchmark_run(id,source_path,imported_at,device_model,case_count) VALUES(?,?,?,?,?)").use { statement ->
                statement.setString(1, run.id)
                statement.setString(2, run.sourceFile.toString())
                statement.setString(3, run.importedAt.toString())
                statement.setString(4, run.device.model)
                statement.setInt(5, run.cases.size)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM benchmark_metric WHERE run_id=?").use { statement ->
                statement.setString(1, run.id)
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO benchmark_metric(run_id,case_identity,metric_name,unit,median,samples) VALUES(?,?,?,?,?,?)").use { statement ->
                run.cases.forEach { case ->
                    case.metrics.forEach { metric ->
                        statement.setString(1, run.id)
                        statement.setString(2, case.identity)
                        statement.setString(3, metric.name)
                        statement.setString(4, metric.unit)
                        metric.representativeValue()?.let { statement.setDouble(5, it) } ?: statement.setNull(5, java.sql.Types.REAL)
                        statement.setInt(6, metric.samples.size)
                        statement.addBatch()
                    }
                }
                statement.executeBatch()
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    public fun listRecent(limit: Int = 50): List<StoredBenchmarkRun> =
        connection.prepareStatement("SELECT id,source_path,imported_at,device_model,case_count FROM benchmark_run ORDER BY imported_at DESC LIMIT ?").use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(StoredBenchmarkRun(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getInt(5)))
                }
            }
        }

    override fun close() {
        connection.close()
    }

    public companion object {
        public fun open(path: Path): SqliteBenchmarkStore {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            return SqliteBenchmarkStore(DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}"))
        }
    }
}
