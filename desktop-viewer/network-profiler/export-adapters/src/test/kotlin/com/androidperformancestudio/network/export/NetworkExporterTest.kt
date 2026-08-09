package com.androidperformancestudio.network.export

import com.androidperformancestudio.network.analysis.NetworkAnalyzer
import com.androidperformancestudio.network.model.*
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkExporterTest {
    @Test fun `exports partial HAR and raw evidence bundle with privacy metadata`() {
        val result = result()
        val directory = createTempDirectory()
        val har = directory.resolve("out.har")
        val bundle = directory.resolve("out.zip")
        val exporter = NetworkExporter()
        exporter.writePartialHar(result, har)
        exporter.writeRawBundle(result, NetworkAnalyzer().summarize(result.calls), bundle)
        assertTrue(har.readText().contains("\"partial\": true"))
        ZipFile(bundle.toFile()).use { zip ->
            assertNotNull(zip.getEntry("network-session.json"))
            assertNotNull(zip.getEntry("raw-events.json"))
            val sessionJson = zip.getInputStream(zip.getEntry("network-session.json")).bufferedReader().readText()
            assertTrue(sessionJson.contains("deviceLocalId"))
            assertTrue(!sessionJson.contains("\"deviceSerial\""))
            assertTrue(!sessionJson.contains("\"device\""))
            assertTrue(zip.getInputStream(zip.getEntry("manifest.txt")).bufferedReader().readText().contains("privacy=minimized_network_evidence"))
        }
    }

    private fun result(): NetworkCaptureResult {
        val exchange = HttpExchange(0, "connection", ConnectionUse.NEW, "h2", 200, null, 10, null, listOf(NetworkPhase(NetworkPhaseKind.TOTAL, 0, 10, NetworkConfidence.EXACT)), CacheDisposition.UNKNOWN, null)
        val call = HttpCall("c", "client", "GET", "https://example.test/", 0, 10, listOf(exchange), CallOutcome.COMPLETED, NetworkEvidenceSource.OKHTTP_EVENT_LISTENER)
        val coverage = NetworkCoverage(setOf(1), setOf("OkHttp"), setOf("client"), InstrumentationMode.EXPLICIT_FACTORY, setOf("callStart"), emptySet(), 0, 10)
        val completeness = NetworkEvidenceCompleteness(EvidenceCompleteness.COMPLETE, 0, 0, 0, 0)
        val session = NetworkSession(startedAt = Instant.EPOCH, endedAt = Instant.EPOCH.plusNanos(10), deviceSerial = "device", packageName = "pkg.name", coverage = coverage, completeness = completeness, sourceTimeDomain = NetworkTimeDomain.DEVICE_MONOTONIC, sourceTimeOriginNs = 100, clockMapping = ClockMapping(100, 200, Instant.EPOCH, 5), status = NetworkSessionStatus.COMPLETE, redactionPolicyVersion = NETWORK_REDACTION_POLICY_VERSION)
        val raw = RawNetworkEvent(1, "c", "client", "callStart", 100, 0, "GET", "https://example.test/", null, null, null, null, null, null, null)
        return NetworkCaptureResult(session, listOf(call), listOf(raw))
    }
}
