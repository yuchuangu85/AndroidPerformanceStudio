package com.androidperformancestudio.network.export

import com.androidperformancestudio.network.model.*
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class NetworkExporterTest {
    @Test fun `exports partial HAR with explicit privacy marker`() {
        val call = HttpCall("c", "GET", "https://example.test", 0, 10, listOf(HttpExchange(0, null, "h2", 200, null, 10, listOf(NetworkPhase(NetworkPhaseKind.TOTAL, 0, 10, NetworkConfidence.EXACT)), CacheDisposition.UNKNOWN, null)), CallOutcome.SUCCESS, NetworkEvidenceSource.OKHTTP_EVENT_LISTENER)
        val result = NetworkCaptureResult(NetworkSession(startedAt = Instant.EPOCH, endedAt = Instant.EPOCH, deviceSerial = null, packageName = null, coverage = NetworkCoverage(setOf("OkHttp"), InstrumentationMode.EXPLICIT_FACTORY, emptySet(), emptySet(), 0, NetworkConfidence.PARTIAL), clockMapping = null, status = NetworkSessionStatus.COMPLETE), listOf(call), 1)
        val out = createTempDirectory().resolve("out.har")
        NetworkExporter().writePartialHar(result, out)
        val text = out.readText()
        assertTrue(text.contains("\"partial\": true"))
        assertTrue(text.contains("\"bodiesCaptured\": false"))
    }
}
