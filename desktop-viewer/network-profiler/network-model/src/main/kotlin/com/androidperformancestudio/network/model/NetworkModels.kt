package com.androidperformancestudio.network.model

import java.time.Instant
import java.util.UUID

public enum class NetworkEvidenceSource { OKHTTP_EVENT_LISTENER, HAR_IMPORT, RAW_EVENT_BUNDLE }

/**
 * Confidence of a [NetworkPhase] timing value.
 *
 * | Level      | Meaning                                                                     |
 * |------------|-----------------------------------------------------------------------------|
 * | EXACT      | Directly measured from a pair of OkHttp EventListener callbacks             |
 * | DERIVED    | Computed from two different callback sources (e.g. SERVER_WAIT)             |
 * | INFERRED   | Interpreted from a secondary data source with some ambiguity (e.g. HAR)     |
 * | APPROXIMATED| Estimated when the true boundary is not observable (e.g. PROXY_SELECT)     |
 * | PARTIAL    | Start is known but end is missing (incomplete event sequence)               |
 * | UNKNOWN    | No timing data whatsoever                                                   |
 *
 * Phases whose confidence is APPROXIMATED or INFERRED should be treated as
 * rough estimates, never as precise measurements. The UI should visually
 * differentiate EXACT/DERIVED phases from INFERRED/APPROXIMATED ones (e.g.
 * dashed bars vs solid bars).
 */
public enum class NetworkConfidence { EXACT, DERIVED, INFERRED, APPROXIMATED, PARTIAL, UNKNOWN }

public enum class EvidenceCompleteness { COMPLETE, PARTIAL, UNKNOWN }

public enum class InstrumentationMode { EXPLICIT_FACTORY, INSTRUMENTED_PARTIAL, HAR_IMPORT, RAW_IMPORT }

public enum class NetworkSessionStatus { CAPTURING, COMPLETE, PARTIAL, FAILED, CANCELLED }

public enum class CallOutcome { COMPLETED, FAILED, CANCELLED, INCOMPLETE }

public enum class CacheDisposition { HIT, MISS, CONDITIONAL_HIT, UNKNOWN }

public enum class ConnectionUse { NEW, REUSED, UNKNOWN }

public enum class NetworkTimeDomain { DEVICE_MONOTONIC, HAR_WALL_CLOCK, SESSION_RELATIVE }

public enum class TimingAvailability { VALUE, NOT_APPLICABLE, UNAVAILABLE, INVALID }

/**
 * Network phase kinds and their derivation from OkHttp EventListener callbacks.
 *
 * ## Phase ⇔ OkHttp EventListener mapping
 *
 * | Phase               | Start event           | End event             | Confidence | Notes                                    |
 * |---------------------|-----------------------|-----------------------|------------|------------------------------------------|
 * | DISPATCHER_QUEUE    | callStart             | dnsStart / connectStart | APPROXIMATED | Queue time is inferred; not a dedicated callback |
 * | PROXY_SELECT        | —                     | —                     | APPROXIMATED | Not exposed by EventListener              |
 * | DNS                 | dnsStart              | dnsEnd                | EXACT      | Absent when connection is reused          |
 * | CONNECT             | connectStart          | connectEnd            | EXACT      | Absent when connection is reused; includes TCP handshake only |
 * | TLS                 | secureConnectStart    | secureConnectEnd      | EXACT      | Absent for plaintext or resumed sessions  |
 * | REQUEST_HEADERS     | requestHeadersStart   | requestHeadersEnd     | EXACT      |                                           |
 * | REQUEST_BODY        | requestBodyStart      | requestBodyEnd        | EXACT      | Absent when there is no body              |
 * | SERVER_WAIT         | requestBodyEnd \| requestHeadersEnd | responseHeadersStart | DERIVED | Computed as gap between request completion and first response |
 * | RESPONSE_HEADERS    | responseHeadersStart  | responseHeadersEnd    | EXACT      |                                           |
 * | RESPONSE_BODY       | responseBodyStart     | responseBodyEnd       | EXACT      | Absent when there is no body              |
 * | CONNECTION_HELD     | connectionAcquired    | connectionReleased    | EXACT      | Duration the connection was held by this call |
 * | TOTAL               | first event           | last event            | EXACT      | Sum may not equal sum of phases (overhead)|
 *
 * ### HAR import mapping
 *
 * | HAR timing | NetworkPhaseKind     | Confidence | Notes                                   |
 * |------------|----------------------|------------|-----------------------------------------|
 * | blocked    | DISPATCHER_QUEUE     | INFERRED   | Queue + stall time; Chrome/Fiddler differ|
 * | dns        | DNS                  | INFERRED   | -1 means unavailable (not 0)            |
 * | connect    | CONNECT              | INFERRED   | TCP only; TLS is separate (ssl)         |
 * | ssl        | TLS                  | INFERRED   | Subsumed in connect in older HAR versions|
 * | send       | REQUEST_BODY         | INFERRED   | Includes request header time in some tools|
 * | wait       | SERVER_WAIT          | INFERRED   | TTFB; different tools measure differently|
 * | receive    | RESPONSE_BODY        | INFERRED   | Chunked vs buffered timing varies        |
 */
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

/**
 * TLS handshake information extracted from OkHttp's
 * [okhttp3.Handshake] or equivalent.
 *
 * Collected from the Agent's `secureConnectEnd` event. Absent for
 * plaintext HTTP or when the connection was reused (no new handshake).
 */
public data class TlsHandshake(
    /** TLS version string, e.g. "TLSv1.3", "TLSv1.2". */
    val tlsVersion: String?,
    /** Cipher suite negotiated, e.g. "TLS_AES_128_GCM_SHA256". */
    val cipherSuite: String?,
    /** Confidence in the captured handshake values. */
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
    /** TLS handshake info. Present only when a new TLS connection was established for this exchange. */
    val tlsHandshake: TlsHandshake? = null,
    val sourceAttributes: Map<String, String> = emptyMap(),
    /** Whether this exchange used a reused (keep-alive) connection, i.e. no DNS/CONNECT/TLS phases. */
    val connectionReused: Boolean = false,
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
