package com.androidperformancestudio.network.model

import java.time.Instant
import java.util.UUID

public enum class NetworkEvidenceSource { OKHTTP_EVENT_LISTENER, HAR_IMPORT, RAW_EVENT_BUNDLE }

public enum class NetworkConfidence { EXACT, DERIVED, INFERRED, PARTIAL, UNKNOWN }

public enum class EvidenceCompleteness { COMPLETE, PARTIAL, UNKNOWN }

public enum class InstrumentationMode { EXPLICIT_FACTORY, INSTRUMENTED_PARTIAL, HAR_IMPORT, RAW_IMPORT }

public enum class NetworkSessionStatus { CAPTURING, COMPLETE, PARTIAL, FAILED, CANCELLED }

public enum class CallOutcome { COMPLETED, FAILED, CANCELLED, INCOMPLETE }

public enum class CacheDisposition { HIT, MISS, CONDITIONAL_HIT, UNKNOWN }

public enum class ConnectionUse { NEW, REUSED, UNKNOWN }

public enum class NetworkTimeDomain { DEVICE_MONOTONIC, HAR_WALL_CLOCK, SESSION_RELATIVE }

public enum class TimingAvailability { VALUE, NOT_APPLICABLE, UNAVAILABLE, INVALID }

public enum class NetworkPhaseKind { DISPATCHER_QUEUE, PROXY_SELECT, DNS, CONNECT, TLS, REQUEST_HEADERS, REQUEST_BODY, SERVER_WAIT, RESPONSE_HEADERS, RESPONSE_BODY, CONNECTION_HELD, TOTAL }

public data class NetworkCoverage(
    val processIds: Set<Int>,
    val observedLibraries: Set<String>,
    val observedInstrumentationIds: Set<String>,
    val instrumentationMode: InstrumentationMode,
    val supportedEventKinds: Set<String>,
    val knownLimitations: Set<String>,
    val windowStartedNs: Long?,
    val windowEndedNs: Long?,
)

public data class NetworkEvidenceCompleteness(
    val status: EvidenceCompleteness,
    val droppedEvents: Long,
    val sequenceGaps: Long,
    val unpairedEvents: Int,
    val skippedRecords: Int,
)

public data class ClockMapping(
    val sourceMonotonicReferenceNs: Long,
    val hostMonotonicReferenceNs: Long,
    val wallClockReference: Instant,
    val errorBoundNs: Long,
)

public data class NetworkSession(
    val id: String = UUID.randomUUID().toString(),
    val deviceSerial: String?,
    val packageName: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val coverage: NetworkCoverage,
    val completeness: NetworkEvidenceCompleteness,
    val sourceTimeDomain: NetworkTimeDomain,
    val sourceTimeOriginNs: Long,
    val clockMapping: ClockMapping?,
    val status: NetworkSessionStatus,
    val redactionPolicyVersion: Int,
    val sourceFormatVersion: String? = null,
    val sourceProducer: String? = null,
    val sourceFingerprint: String? = null,
    val warnings: List<String> = emptyList(),
)

public data class NetworkPhase(
    val kind: NetworkPhaseKind,
    val startNs: Long?,
    val endNs: Long?,
    val confidence: NetworkConfidence,
    val reportedDurationNs: Long? = null,
    val availability: TimingAvailability = TimingAvailability.VALUE,
    val parentKind: NetworkPhaseKind? = null,
) {
    public val durationNs: Long?
        get() = reportedDurationNs ?: startNs?.let { start -> endNs?.minus(start)?.takeIf { it >= 0 } }
}

public data class NetworkFailure(
    val type: String,
    val message: String?,
    val lastReliableEvent: String?,
)

public data class TlsHandshake(
    val tlsVersion: String?,
    val cipherSuite: String?,
    val confidence: NetworkConfidence,
)

public data class HttpExchange(
    val exchangeIndex: Int,
    val connectionId: String?,
    val connectionUse: ConnectionUse,
    val protocol: String?,
    val statusCode: Int?,
    val requestBytes: Long?,
    val responseBytes: Long?,
    val decodedResponseBytes: Long?,
    val phases: List<NetworkPhase>,
    val cacheDisposition: CacheDisposition,
    val failure: NetworkFailure?,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val tlsHandshake: TlsHandshake? = null,
    val sourceAttributes: Map<String, String> = emptyMap(),
)

public data class HttpCall(
    val callId: String,
    val instrumentationId: String?,
    val method: String,
    val redactedUrl: String,
    val startedNs: Long,
    val endedNs: Long?,
    val exchanges: List<HttpExchange>,
    val outcome: CallOutcome,
    val source: NetworkEvidenceSource,
) {
    public val durationNs: Long? get() = endedNs?.minus(startedNs)?.takeIf { it >= 0 }
}

public data class RawNetworkEvent(
    val sequence: Long,
    val callId: String,
    val instrumentationId: String?,
    val kind: String,
    val sourceTimestampNs: Long,
    val relativeTimestampNs: Long,
    val method: String?,
    val redactedUrl: String?,
    val statusCode: Int?,
    val byteCount: Long?,
    val protocol: String?,
    val connectionId: String?,
    val tlsVersion: String?,
    val cipherSuite: String?,
    val message: String?,
)

public data class NetworkCaptureResult(
    val session: NetworkSession,
    val calls: List<HttpCall>,
    val rawEvents: List<RawNetworkEvent> = emptyList(),
) {
    public val rawEventCount: Int get() = rawEvents.size
}
