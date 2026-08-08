@file:Suppress("LongMethod", "TooManyFunctions")

package com.androidperformancestudio.network.storage

import com.androidperformancestudio.network.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.Base64

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
            statement.execute("CREATE TABLE IF NOT EXISTS network_session_v2(id TEXT PRIMARY KEY, started_at TEXT NOT NULL, ended_at TEXT, device_serial TEXT, package_name TEXT, status TEXT NOT NULL, time_domain TEXT NOT NULL, time_origin_ns INTEGER NOT NULL, redaction_version INTEGER NOT NULL, source_format TEXT, source_producer TEXT, source_fingerprint TEXT, process_ids TEXT NOT NULL, libraries TEXT NOT NULL, instrumentation_ids TEXT NOT NULL, instrumentation_mode TEXT NOT NULL, event_kinds TEXT NOT NULL, limitations TEXT NOT NULL, window_start_ns INTEGER, window_end_ns INTEGER, completeness_status TEXT NOT NULL, dropped_events INTEGER NOT NULL, sequence_gaps INTEGER NOT NULL, unpaired_events INTEGER NOT NULL, skipped_records INTEGER NOT NULL, clock_source_ns INTEGER, clock_host_ns INTEGER, clock_wall TEXT, clock_error_ns INTEGER, warnings TEXT NOT NULL, call_count INTEGER NOT NULL)")
            statement.execute("CREATE TABLE IF NOT EXISTS network_call_v2(session_id TEXT NOT NULL, call_id TEXT NOT NULL, instrumentation_id TEXT, method TEXT NOT NULL, url TEXT NOT NULL, started_ns INTEGER NOT NULL, ended_ns INTEGER, outcome TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY(session_id,call_id), FOREIGN KEY(session_id) REFERENCES network_session_v2(id) ON DELETE CASCADE)")
            statement.execute("CREATE TABLE IF NOT EXISTS http_exchange_v2(session_id TEXT NOT NULL, call_id TEXT NOT NULL, exchange_index INTEGER NOT NULL, connection_id TEXT, connection_use TEXT NOT NULL, protocol TEXT, status_code INTEGER, request_bytes INTEGER, response_bytes INTEGER, decoded_response_bytes INTEGER, cache_disposition TEXT NOT NULL, failure_type TEXT, failure_message TEXT, failure_last_event TEXT, request_headers TEXT NOT NULL, response_headers TEXT NOT NULL, tls_version TEXT, cipher_suite TEXT, tls_confidence TEXT, source_attributes TEXT NOT NULL, PRIMARY KEY(session_id,call_id,exchange_index), FOREIGN KEY(session_id,call_id) REFERENCES network_call_v2(session_id,call_id) ON DELETE CASCADE)")
            statement.execute("CREATE TABLE IF NOT EXISTS network_phase_v2(session_id TEXT NOT NULL, call_id TEXT NOT NULL, exchange_index INTEGER NOT NULL, phase_index INTEGER NOT NULL, kind TEXT NOT NULL, start_ns INTEGER, end_ns INTEGER, confidence TEXT NOT NULL, reported_duration_ns INTEGER, availability TEXT NOT NULL, parent_kind TEXT, PRIMARY KEY(session_id,call_id,exchange_index,phase_index), FOREIGN KEY(session_id,call_id,exchange_index) REFERENCES http_exchange_v2(session_id,call_id,exchange_index) ON DELETE CASCADE)")
            statement.execute("CREATE TABLE IF NOT EXISTS raw_network_event_v2(session_id TEXT NOT NULL, sequence INTEGER NOT NULL, call_id TEXT NOT NULL, instrumentation_id TEXT, kind TEXT NOT NULL, source_timestamp_ns INTEGER NOT NULL, relative_timestamp_ns INTEGER NOT NULL, method TEXT, url TEXT, status_code INTEGER, byte_count INTEGER, protocol TEXT, connection_id TEXT, tls_version TEXT, cipher_suite TEXT, message TEXT, PRIMARY KEY(session_id,sequence), FOREIGN KEY(session_id) REFERENCES network_session_v2(id) ON DELETE CASCADE)")
            statement.execute("PRAGMA user_version = 2")
        }
    }

    public fun save(result: NetworkCaptureResult) {
        connection.autoCommit = false
        try {
            saveSession(result)
            saveCalls(result)
            saveRawEvents(result)
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    public fun load(sessionId: String): NetworkCaptureResult? {
        val session = loadSession(sessionId) ?: return null
        // ponytail: N+1 reads keep the schema boring; replace with joined streaming only if large-session loads measure slow.
        val calls = connection.prepareStatement("SELECT call_id,instrumentation_id,method,url,started_ns,ended_ns,outcome,source FROM network_call_v2 WHERE session_id=? ORDER BY started_ns,call_id").use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(loadCall(sessionId, rows)) } }
        }
        val rawEvents = connection.prepareStatement("SELECT sequence,call_id,instrumentation_id,kind,source_timestamp_ns,relative_timestamp_ns,method,url,status_code,byte_count,protocol,connection_id,tls_version,cipher_suite,message FROM raw_network_event_v2 WHERE session_id=? ORDER BY sequence").use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(RawNetworkEvent(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getLong(6), rows.getString(7), rows.getString(8), rows.nullableInt(9), rows.nullableLong(10), rows.getString(11), rows.getString(12), rows.getString(13), rows.getString(14), rows.getString(15))) } }
        }
        return NetworkCaptureResult(session, calls, rawEvents)
    }

    public fun listRecent(limit: Int = 50): List<StoredNetworkSession> = connection.prepareStatement("SELECT id,started_at,instrumentation_mode,call_count,status FROM network_session_v2 ORDER BY started_at DESC LIMIT ?").use { statement ->
        statement.setInt(1, limit)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(StoredNetworkSession(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4), rows.getString(5))) } }
    }

    private fun saveSession(result: NetworkCaptureResult) {
        val session = result.session
        connection.prepareStatement("INSERT OR REPLACE INTO network_session_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
            var i = 1
            statement.setString(i++, session.id)
            statement.setString(i++, session.startedAt.toString())
            statement.setString(i++, session.endedAt?.toString())
            statement.setString(i++, session.deviceSerial)
            statement.setString(i++, session.packageName)
            statement.setString(i++, session.status.name)
            statement.setString(i++, session.sourceTimeDomain.name)
            statement.setLong(i++, session.sourceTimeOriginNs)
            statement.setInt(i++, session.redactionPolicyVersion)
            statement.setString(i++, session.sourceFormatVersion)
            statement.setString(i++, session.sourceProducer)
            statement.setString(i++, session.sourceFingerprint)
            statement.setString(i++, encodeList(session.coverage.processIds.map(Int::toString)))
            statement.setString(i++, encodeList(session.coverage.observedLibraries))
            statement.setString(i++, encodeList(session.coverage.observedInstrumentationIds))
            statement.setString(i++, session.coverage.instrumentationMode.name)
            statement.setString(i++, encodeList(session.coverage.supportedEventKinds))
            statement.setString(i++, encodeList(session.coverage.knownLimitations))
            statement.setNullableLong(i++, session.coverage.windowStartedNs)
            statement.setNullableLong(i++, session.coverage.windowEndedNs)
            statement.setString(i++, session.completeness.status.name)
            statement.setLong(i++, session.completeness.droppedEvents)
            statement.setLong(i++, session.completeness.sequenceGaps)
            statement.setInt(i++, session.completeness.unpairedEvents)
            statement.setInt(i++, session.completeness.skippedRecords)
            statement.setNullableLong(i++, session.clockMapping?.sourceMonotonicReferenceNs)
            statement.setNullableLong(i++, session.clockMapping?.hostMonotonicReferenceNs)
            statement.setString(i++, session.clockMapping?.wallClockReference?.toString())
            statement.setNullableLong(i++, session.clockMapping?.errorBoundNs)
            statement.setString(i++, encodeList(session.warnings))
            statement.setInt(i, result.calls.size)
            statement.executeUpdate()
        }
    }

    private fun saveCalls(result: NetworkCaptureResult) {
        connection.prepareStatement("INSERT INTO network_call_v2 VALUES(?,?,?,?,?,?,?,?,?)").use { callStatement ->
            connection.prepareStatement("INSERT INTO http_exchange_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { exchangeStatement ->
                connection.prepareStatement("INSERT INTO network_phase_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { phaseStatement ->
                    result.calls.forEach { call ->
                        callStatement.setString(1, result.session.id)
                        callStatement.setString(2, call.callId)
                        callStatement.setString(3, call.instrumentationId)
                        callStatement.setString(4, call.method)
                        callStatement.setString(5, call.redactedUrl)
                        callStatement.setLong(6, call.startedNs)
                        callStatement.setNullableLong(7, call.endedNs)
                        callStatement.setString(8, call.outcome.name)
                        callStatement.setString(9, call.source.name)
                        callStatement.executeUpdate()
                        call.exchanges.forEach { exchange ->
                            var i = 1
                            exchangeStatement.setString(i++, result.session.id)
                            exchangeStatement.setString(i++, call.callId)
                            exchangeStatement.setInt(i++, exchange.exchangeIndex)
                            exchangeStatement.setString(i++, exchange.connectionId)
                            exchangeStatement.setString(i++, exchange.connectionUse.name)
                            exchangeStatement.setString(i++, exchange.protocol)
                            exchangeStatement.setNullableInt(i++, exchange.statusCode)
                            exchangeStatement.setNullableLong(i++, exchange.requestBytes)
                            exchangeStatement.setNullableLong(i++, exchange.responseBytes)
                            exchangeStatement.setNullableLong(i++, exchange.decodedResponseBytes)
                            exchangeStatement.setString(i++, exchange.cacheDisposition.name)
                            exchangeStatement.setString(i++, exchange.failure?.type)
                            exchangeStatement.setString(i++, exchange.failure?.message)
                            exchangeStatement.setString(i++, exchange.failure?.lastReliableEvent)
                            exchangeStatement.setString(i++, encodeMap(exchange.requestHeaders))
                            exchangeStatement.setString(i++, encodeMap(exchange.responseHeaders))
                            exchangeStatement.setString(i++, exchange.tlsHandshake?.tlsVersion)
                            exchangeStatement.setString(i++, exchange.tlsHandshake?.cipherSuite)
                            exchangeStatement.setString(i++, exchange.tlsHandshake?.confidence?.name)
                            exchangeStatement.setString(i, encodeMap(exchange.sourceAttributes))
                            exchangeStatement.executeUpdate()
                            exchange.phases.forEachIndexed { phaseIndex, phase ->
                                phaseStatement.setString(1, result.session.id)
                                phaseStatement.setString(2, call.callId)
                                phaseStatement.setInt(3, exchange.exchangeIndex)
                                phaseStatement.setInt(4, phaseIndex)
                                phaseStatement.setString(5, phase.kind.name)
                                phaseStatement.setNullableLong(6, phase.startNs)
                                phaseStatement.setNullableLong(7, phase.endNs)
                                phaseStatement.setString(8, phase.confidence.name)
                                phaseStatement.setNullableLong(9, phase.reportedDurationNs)
                                phaseStatement.setString(10, phase.availability.name)
                                phaseStatement.setString(11, phase.parentKind?.name)
                                phaseStatement.executeUpdate()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun saveRawEvents(result: NetworkCaptureResult) {
        connection.prepareStatement("INSERT INTO raw_network_event_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
            result.rawEvents.forEach { event ->
                statement.setString(1, result.session.id)
                statement.setLong(2, event.sequence)
                statement.setString(3, event.callId)
                statement.setString(4, event.instrumentationId)
                statement.setString(5, event.kind)
                statement.setLong(6, event.sourceTimestampNs)
                statement.setLong(7, event.relativeTimestampNs)
                statement.setString(8, event.method)
                statement.setString(9, event.redactedUrl)
                statement.setNullableInt(10, event.statusCode)
                statement.setNullableLong(11, event.byteCount)
                statement.setString(12, event.protocol)
                statement.setString(13, event.connectionId)
                statement.setString(14, event.tlsVersion)
                statement.setString(15, event.cipherSuite)
                statement.setString(16, event.message)
                statement.executeUpdate()
            }
        }
    }

    private fun loadSession(id: String): NetworkSession? = connection.prepareStatement("SELECT * FROM network_session_v2 WHERE id=?").use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { row ->
            if (!row.next()) return@use null
            val clock = row.getString("clock_wall")?.let { wall -> ClockMapping(row.getLong("clock_source_ns"), row.getLong("clock_host_ns"), Instant.parse(wall), row.getLong("clock_error_ns")) }
            NetworkSession(
                id = row.getString("id"), deviceSerial = row.getString("device_serial"), packageName = row.getString("package_name"), startedAt = Instant.parse(row.getString("started_at")), endedAt = row.getString("ended_at")?.let(Instant::parse),
                coverage = NetworkCoverage(decodeList(row.getString("process_ids")).mapTo(mutableSetOf(), String::toInt), decodeList(row.getString("libraries")), decodeList(row.getString("instrumentation_ids")), InstrumentationMode.valueOf(row.getString("instrumentation_mode")), decodeList(row.getString("event_kinds")), decodeList(row.getString("limitations")), row.nullableLong("window_start_ns"), row.nullableLong("window_end_ns")),
                completeness = NetworkEvidenceCompleteness(EvidenceCompleteness.valueOf(row.getString("completeness_status")), row.getLong("dropped_events"), row.getLong("sequence_gaps"), row.getInt("unpaired_events"), row.getInt("skipped_records")),
                sourceTimeDomain = NetworkTimeDomain.valueOf(row.getString("time_domain")), sourceTimeOriginNs = row.getLong("time_origin_ns"), clockMapping = clock, status = NetworkSessionStatus.valueOf(row.getString("status")), redactionPolicyVersion = row.getInt("redaction_version"), sourceFormatVersion = row.getString("source_format"), sourceProducer = row.getString("source_producer"), sourceFingerprint = row.getString("source_fingerprint"), warnings = decodeList(row.getString("warnings")).toList(),
            )
        }
    }

    private fun loadCall(sessionId: String, row: ResultSet): HttpCall {
        val callId = row.getString("call_id")
        val exchanges = connection.prepareStatement("SELECT * FROM http_exchange_v2 WHERE session_id=? AND call_id=? ORDER BY exchange_index").use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, callId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(loadExchange(sessionId, callId, rows)) } }
        }
        return HttpCall(callId, row.getString("instrumentation_id"), row.getString("method"), row.getString("url"), row.getLong("started_ns"), row.nullableLong("ended_ns"), exchanges, CallOutcome.valueOf(row.getString("outcome")), NetworkEvidenceSource.valueOf(row.getString("source")))
    }

    private fun loadExchange(sessionId: String, callId: String, row: ResultSet): HttpExchange {
        val index = row.getInt("exchange_index")
        val phases = connection.prepareStatement("SELECT * FROM network_phase_v2 WHERE session_id=? AND call_id=? AND exchange_index=? ORDER BY phase_index").use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, callId)
            statement.setInt(3, index)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(NetworkPhase(NetworkPhaseKind.valueOf(rows.getString("kind")), rows.nullableLong("start_ns"), rows.nullableLong("end_ns"), NetworkConfidence.valueOf(rows.getString("confidence")), rows.nullableLong("reported_duration_ns"), TimingAvailability.valueOf(rows.getString("availability")), rows.getString("parent_kind")?.let(NetworkPhaseKind::valueOf))) } }
        }
        val tlsVersion = row.getString("tls_version")
        val cipherSuite = row.getString("cipher_suite")
        val tls = if (tlsVersion != null || cipherSuite != null) TlsHandshake(tlsVersion, cipherSuite, NetworkConfidence.valueOf(row.getString("tls_confidence"))) else null
        val failureType = row.getString("failure_type")
        return HttpExchange(index, row.getString("connection_id"), ConnectionUse.valueOf(row.getString("connection_use")), row.getString("protocol"), row.nullableInt("status_code"), row.nullableLong("request_bytes"), row.nullableLong("response_bytes"), row.nullableLong("decoded_response_bytes"), phases, CacheDisposition.valueOf(row.getString("cache_disposition")), failureType?.let { NetworkFailure(it, row.getString("failure_message"), row.getString("failure_last_event")) }, decodeMap(row.getString("request_headers")), decodeMap(row.getString("response_headers")), tls, decodeMap(row.getString("source_attributes")))
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

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}

private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}

private fun ResultSet.nullableLong(index: Int): Long? = getLong(index).let { if (wasNull()) null else it }

private fun ResultSet.nullableInt(index: Int): Int? = getInt(index).let { if (wasNull()) null else it }

private fun ResultSet.nullableLong(name: String): Long? = getLong(name).let { if (wasNull()) null else it }

private fun ResultSet.nullableInt(name: String): Int? = getInt(name).let { if (wasNull()) null else it }

private val encoder = Base64.getUrlEncoder().withoutPadding()
private val decoder = Base64.getUrlDecoder()

private fun encode(value: String): String = encoder.encodeToString(value.toByteArray())

private fun decode(value: String): String = decoder.decode(value).toString(Charsets.UTF_8)

private fun encodeList(values: Iterable<String>): String = values.joinToString(",", transform = ::encode)

private fun decodeList(value: String): Set<String> = value.takeIf(String::isNotEmpty)?.split(',')?.mapTo(linkedSetOf(), ::decode) ?: emptySet()

private fun encodeMap(values: Map<String, String>): String = values.entries.joinToString(",") { "${encode(it.key)}=${encode(it.value)}" }

private fun decodeMap(value: String): Map<String, String> = value.takeIf(String::isNotEmpty)?.split(',')?.associate { item -> decode(item.substringBefore('=')) to decode(item.substringAfter('=')) } ?: emptyMap()
