package com.androidperformancestudio.network.model

import java.time.Instant
import java.util.UUID

public enum class NetworkEvidenceSource { OKHTTP_EVENT_LISTENER, HAR_IMPORT, RAW_EVENT_BUNDLE }

public enum class NetworkConfidence { EXACT, DERIVED, INFERRED, PARTIAL, UNKNOWN }

public enum class InstrumentationMode { EXPLICIT_FACTORY, INSTRUMENTED_PARTIAL, HAR_IMPORT, RAW_IMPORT }

public enum class NetworkSessionStatus { CAPTURING, COMPLETE, PARTIAL, FAILED, CANCELLED }

public enum class CallOutcome { SUCCESS, FAILED, CANCELLED, INCOMPLETE }

public enum class CacheDisposition { HIT, MISS, CONDITIONAL_HIT, UNKNOWN }

public enum class NetworkPhaseKind { DISPATCHER_QUEUE, PROXY_SELECT, DNS, CONNECT, TLS, REQUEST_HEADERS, REQUEST_BODY, SERVER_WAIT, RESPONSE_HEADERS, RESPONSE_BODY, CONNECTION_HELD, TOTAL }

public data class NetworkCoverage(
    val observedLibraries: Set<String>,
    val instrumentationMode: InstrumentationMode,
    val supportedEventKinds: Set<String>,
    val unsupportedStacks: Set<String>,
    val droppedEvents: Long,
    val completeness: NetworkConfidence,
)

public data class ClockMapping(
    val deviceMonotonicReferenceNs: Long,
    val hostReferenceNs: Long,
    val errorBoundNs: Long,
)

public data class NetworkSession(
    val id: String = UUID.randomUUID().toString(),
    val deviceSerial: String?,
    val packageName: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val coverage: NetworkCoverage,
    val clockMapping: ClockMapping?,
    val status: NetworkSessionStatus,
    val warnings: List<String> = emptyList(),
)

public data class NetworkPhase(
    val kind: NetworkPhaseKind,
    val startNs: Long,
    val endNs: Long?,
    val confidence: NetworkConfidence,
) {
    public val durationNs: Long? get() = endNs?.minus(startNs)?.takeIf { it >= 0 }
}

public data class NetworkFailure(
    val type: String,
    val message: String?,
    val lastReliableEvent: String?,
)

public data class HttpExchange(
    val exchangeIndex: Int,
    val connectionId: String?,
    val protocol: String?,
    val statusCode: Int?,
    val requestBytes: Long?,
    val responseBytes: Long?,
    val phases: List<NetworkPhase>,
    val cacheDisposition: CacheDisposition,
    val failure: NetworkFailure?,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
)

public data class HttpCall(
    val callId: String,
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

public data class NetworkCaptureResult(
    val session: NetworkSession,
    val calls: List<HttpCall>,
    val rawEventCount: Int,
)
