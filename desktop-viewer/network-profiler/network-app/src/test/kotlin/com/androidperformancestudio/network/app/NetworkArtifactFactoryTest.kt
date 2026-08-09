package com.androidperformancestudio.network.app

import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.ArtifactProducer
import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.network.model.CacheDisposition
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.ConnectionUse
import com.androidperformancestudio.network.model.EvidenceCompleteness
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.HttpExchange
import com.androidperformancestudio.network.model.InstrumentationMode
import com.androidperformancestudio.network.model.NETWORK_REDACTION_POLICY_VERSION
import com.androidperformancestudio.network.model.NetworkCaptureResult
import com.androidperformancestudio.network.model.NetworkConfidence
import com.androidperformancestudio.network.model.NetworkCoverage
import com.androidperformancestudio.network.model.NetworkEvidenceCompleteness
import com.androidperformancestudio.network.model.NetworkEvidenceSource
import com.androidperformancestudio.network.model.NetworkPhase
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.model.NetworkSession
import com.androidperformancestudio.network.model.NetworkSessionStatus
import com.androidperformancestudio.network.model.NetworkTimeDomain
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NetworkArtifactFactoryTest {
    @Test
    fun `HAR import preserves producer and leaves unknowable capture intent UNKNOWN`() {
        val input = Files.createTempFile("network", ".har").also { Files.writeString(it, "har bytes") }
        val result = result(deviceSerial = null, producer = "Browser HAR 1.0")

        val artifact = NetworkArtifactFactory { Instant.EPOCH }.har(input, result)

        assertEquals(ArtifactAcquisitionKind.IMPORT, artifact.provenance.acquisition.kind)
        assertEquals(ArtifactProducer.Known("Browser HAR 1.0"), artifact.provenance.producer)
        assertEquals(null, artifact.requestedCapabilities)
        assertEquals(ArtifactCompleteness.UNKNOWN, artifact.completeness)
        assertNotEquals(artifact.id, NetworkArtifactFactory { Instant.EPOCH }.har(input, result).id)
    }

    @Test
    fun `partial Agent session stays PARTIAL and excludes raw serial`() {
        val artifact = NetworkArtifactFactory { Instant.EPOCH }.agent(result("raw-network-serial", null))

        assertEquals(ArtifactCompleteness.PARTIAL, artifact.completeness)
        assertTrue(artifact.limitations.any { it.capability == NetworkArtifactFactory.COMPLETE_SESSION })
        assertTrue(artifact.device?.rawSerial == null)
        assertFalse(CaptureArtifactJson.encode(artifact).contains("raw-network-serial"))
    }

    @Test
    fun `Agent digest covers redacted headers and timing evidence`() {
        val factory = NetworkArtifactFactory { Instant.EPOCH }
        val exchange = evidenceExchange()
        val call = evidenceCall(exchange)
        val baseline = result(null, null).copy(calls = listOf(call))
        val changedHeader =
            baseline.copy(
                calls =
                    listOf(
                        call.copy(
                            exchanges = listOf(exchange.copy(requestHeaders = mapOf("x-safe" to "two"))),
                        ),
                    ),
            )
        val changedTiming =
            baseline.copy(
                calls =
                    listOf(
                        call.copy(
                            exchanges =
                                listOf(
                                    exchange.copy(
                                        phases = exchange.phases.map { it.copy(endNs = 250) },
                                    ),
                                ),
                        ),
                    ),
            )

        assertNotEquals(factory.agent(baseline).sha256, factory.agent(changedHeader).sha256)
        assertNotEquals(factory.agent(baseline).sha256, factory.agent(changedTiming).sha256)
    }

    private fun evidenceExchange(): HttpExchange =
        HttpExchange(
            exchangeIndex = 0,
            connectionId = "connection-1",
            connectionUse = ConnectionUse.NEW,
            protocol = "h2",
            statusCode = 200,
            requestBytes = 10,
            responseBytes = 20,
            decodedResponseBytes = 20,
            phases =
                listOf(
                    NetworkPhase(
                        kind = NetworkPhaseKind.TOTAL,
                        startNs = 100,
                        endNs = 200,
                        confidence = NetworkConfidence.EXACT,
                    ),
                ),
            cacheDisposition = CacheDisposition.MISS,
            failure = null,
            requestHeaders = mapOf("x-safe" to "one"),
        )

    private fun evidenceCall(exchange: HttpExchange): HttpCall =
        HttpCall(
            callId = "call-1",
            instrumentationId = "instrumentation-1",
            method = "GET",
            redactedUrl = "https://example.test/items?<redacted>",
            startedNs = 100,
            endedNs = 200,
            exchanges = listOf(exchange),
            outcome = CallOutcome.COMPLETED,
            source = NetworkEvidenceSource.OKHTTP_EVENT_LISTENER,
        )

    private fun result(
        deviceSerial: String?,
        producer: String?,
    ): NetworkCaptureResult =
        NetworkCaptureResult(
            session =
                NetworkSession(
                    deviceSerial = deviceSerial,
                    packageName = "dev.example.app",
                    startedAt = Instant.EPOCH,
                    endedAt = null,
                    coverage =
                        NetworkCoverage(
                            processIds = setOf(42),
                            observedLibraries = emptySet(),
                            observedInstrumentationIds = emptySet(),
                            instrumentationMode = InstrumentationMode.EXPLICIT_FACTORY,
                            supportedEventKinds = emptySet(),
                            knownLimitations = setOf("capture interrupted"),
                            windowStartedNs = 0L,
                            windowEndedNs = null,
                        ),
                    completeness =
                        NetworkEvidenceCompleteness(
                            status = EvidenceCompleteness.PARTIAL,
                            droppedEvents = 1,
                            sequenceGaps = 0,
                            unpairedEvents = 0,
                            skippedRecords = 0,
                        ),
                    sourceTimeDomain = NetworkTimeDomain.DEVICE_MONOTONIC,
                    sourceTimeOriginNs = 0L,
                    clockMapping = null,
                    status = NetworkSessionStatus.PARTIAL,
                    redactionPolicyVersion = NETWORK_REDACTION_POLICY_VERSION,
                    sourceFormatVersion = "1",
                    sourceProducer = producer,
                ),
            calls = emptyList(),
        )
}
