package com.androidperformancestudio.network.storage

import com.androidperformancestudio.network.model.*
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteNetworkStoreTest {
    @Test fun `round trips complete minimized network evidence idempotently`() {
        val result = fixture()
        val database = createTempDirectory().resolve("db.sqlite")
        SqliteNetworkStore.open(database).use { store ->
            store.save(result)
            store.save(result)
            assertEquals(1, store.listRecent().size)
            assertEquals(
                result.copy(session = result.session.copy(deviceSerial = networkDeviceLocalId("device"))),
                store.load(result.session.id),
            )
        }
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            val stored = connection.createStatement().executeQuery("SELECT device_serial FROM network_session_v2").use {
                check(it.next())
                it.getString(1)
            }
            assertEquals(networkDeviceLocalId("device"), stored)
        }
    }

    private fun fixture(): NetworkCaptureResult {
        val coverage = NetworkCoverage(setOf(12), setOf("OkHttp"), setOf("client-1"), InstrumentationMode.EXPLICIT_FACTORY, setOf("callStart", "callEnd"), setOf("WebView"), 0, 10)
        val completeness = NetworkEvidenceCompleteness(EvidenceCompleteness.COMPLETE, 0, 0, 0, 0)
        val session = NetworkSession(
            id = "session",
            deviceSerial = "device",
            packageName = "example.app",
            startedAt = Instant.EPOCH,
            endedAt = Instant.EPOCH.plusNanos(10),
            coverage = coverage,
            completeness = completeness,
            sourceTimeDomain = NetworkTimeDomain.DEVICE_MONOTONIC,
            sourceTimeOriginNs = 100,
            clockMapping = ClockMapping(100, 200, Instant.EPOCH, 5),
            status = NetworkSessionStatus.COMPLETE,
            redactionPolicyVersion = NETWORK_REDACTION_POLICY_VERSION,
            sourceFormatVersion = "1",
            sourceProducer = "agent",
            sourceFingerprint = "hash",
            warnings = listOf("warning"),
        )
        val phase = NetworkPhase(NetworkPhaseKind.TLS, 1, 3, NetworkConfidence.EXACT, parentKind = NetworkPhaseKind.CONNECT)
        val exchange = HttpExchange(0, "connection-1", ConnectionUse.NEW, "h2", 200, 3, 4, 5, listOf(phase), CacheDisposition.HIT, NetworkFailure("responseFailed", "IOException", "responseBodyStart"), mapOf("Authorization" to "<redacted>"), mapOf("Content-Type" to "text/plain"), TlsHandshake("TLSv1.3", "TLS_AES_128_GCM_SHA256", NetworkConfidence.EXACT), mapOf("har.timings.custom" to "1"))
        val call = HttpCall("call", "client-1", "GET", "https://example.test/_aps/id", 0, 10, listOf(exchange), CallOutcome.COMPLETED, NetworkEvidenceSource.OKHTTP_EVENT_LISTENER)
        val raw = RawNetworkEvent(1, "call", "client-1", "callStart", 100, 0, "GET", call.redactedUrl, null, null, null, null, null, null, null)
        return NetworkCaptureResult(session, listOf(call), listOf(raw))
    }
}
