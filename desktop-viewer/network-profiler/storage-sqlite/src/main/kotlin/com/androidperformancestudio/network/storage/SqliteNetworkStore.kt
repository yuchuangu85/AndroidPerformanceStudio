@file:Suppress("LongMethod")

package com.androidperformancestudio.network.storage

import com.androidperformancestudio.network.model.NetworkCaptureResult
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

public data class StoredNetworkSession(
    val id: String,
    val startedAt: String,
    val sourceMode: String,
    val callCount: Int,
    val status: String,
)

public class SqliteNetworkStore private constructor(
    private val connection: Connection,
) : AutoCloseable {
    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("CREATE TABLE IF NOT EXISTS network_session(id TEXT PRIMARY KEY, started_at TEXT NOT NULL, ended_at TEXT, device_serial TEXT, package_name TEXT, source_mode TEXT NOT NULL, call_count INTEGER NOT NULL, status TEXT NOT NULL)")
            statement.execute("CREATE TABLE IF NOT EXISTS http_call(session_id TEXT NOT NULL, call_id TEXT NOT NULL, method TEXT NOT NULL, url TEXT NOT NULL, started_ns INTEGER NOT NULL, ended_ns INTEGER, outcome TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY(session_id,call_id), FOREIGN KEY(session_id) REFERENCES network_session(id) ON DELETE CASCADE)")
            statement.execute("CREATE TABLE IF NOT EXISTS http_exchange(session_id TEXT NOT NULL, call_id TEXT NOT NULL, exchange_index INTEGER NOT NULL, protocol TEXT, status_code INTEGER, request_bytes INTEGER, response_bytes INTEGER, connection_id TEXT, failure_type TEXT, PRIMARY KEY(session_id,call_id,exchange_index), FOREIGN KEY(session_id,call_id) REFERENCES http_call(session_id,call_id) ON DELETE CASCADE)")
        }
    }

    public fun save(result: NetworkCaptureResult) {
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT OR REPLACE INTO network_session(id,started_at,ended_at,device_serial,package_name,source_mode,call_count,status) VALUES(?,?,?,?,?,?,?,?)").use { statement ->
                statement.setString(1, result.session.id)
                statement.setString(2, result.session.startedAt.toString())
                statement.setString(3, result.session.endedAt?.toString())
                statement.setString(4, result.session.deviceSerial)
                statement.setString(5, result.session.packageName)
                statement.setString(6, result.session.coverage.instrumentationMode.name)
                statement.setInt(7, result.calls.size)
                statement.setString(8, result.session.status.name)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM http_call WHERE session_id=?").use {
                it.setString(1, result.session.id)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO http_call(session_id,call_id,method,url,started_ns,ended_ns,outcome,source) VALUES(?,?,?,?,?,?,?,?)").use { callStatement ->
                connection.prepareStatement("INSERT INTO http_exchange(session_id,call_id,exchange_index,protocol,status_code,request_bytes,response_bytes,connection_id,failure_type) VALUES(?,?,?,?,?,?,?,?,?)").use { exchangeStatement ->
                    result.calls.forEach { call ->
                        callStatement.setString(1, result.session.id)
                        callStatement.setString(2, call.callId)
                        callStatement.setString(3, call.method)
                        callStatement.setString(4, call.redactedUrl)
                        callStatement.setLong(5, call.startedNs)
                        call.endedNs?.let { callStatement.setLong(6, it) } ?: callStatement.setNull(6, java.sql.Types.BIGINT)
                        callStatement.setString(7, call.outcome.name)
                        callStatement.setString(8, call.source.name)
                        callStatement.addBatch()
                        call.exchanges.forEach { exchange ->
                            exchangeStatement.setString(1, result.session.id)
                            exchangeStatement.setString(2, call.callId)
                            exchangeStatement.setInt(3, exchange.exchangeIndex)
                            exchangeStatement.setString(4, exchange.protocol)
                            exchange.statusCode?.let { exchangeStatement.setInt(5, it) } ?: exchangeStatement.setNull(5, java.sql.Types.INTEGER)
                            exchange.requestBytes?.let { exchangeStatement.setLong(6, it) } ?: exchangeStatement.setNull(6, java.sql.Types.BIGINT)
                            exchange.responseBytes?.let { exchangeStatement.setLong(7, it) } ?: exchangeStatement.setNull(7, java.sql.Types.BIGINT)
                            exchangeStatement.setString(8, exchange.connectionId)
                            exchangeStatement.setString(9, exchange.failure?.type)
                            exchangeStatement.addBatch()
                        }
                    }
                    callStatement.executeBatch()
                    exchangeStatement.executeBatch()
                }
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    public fun listRecent(limit: Int = 50): List<StoredNetworkSession> = connection.prepareStatement("SELECT id,started_at,source_mode,call_count,status FROM network_session ORDER BY started_at DESC LIMIT ?").use { statement ->
        statement.setInt(1, limit)
        statement.executeQuery().use { result -> buildList { while (result.next()) add(StoredNetworkSession(result.getString(1), result.getString(2), result.getString(3), result.getInt(4), result.getString(5))) } }
    }

    override fun close() {
        connection.close()
    }

    public companion object {
        public fun open(path: Path): SqliteNetworkStore {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            return SqliteNetworkStore(DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}"))
        }
    }
}
