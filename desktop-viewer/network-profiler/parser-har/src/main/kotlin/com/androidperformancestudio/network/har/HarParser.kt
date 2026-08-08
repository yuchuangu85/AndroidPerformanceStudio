@file:Suppress("LongMethod", "MagicNumber", "ReturnCount")

package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Parses HAR (HTTP Archive) files and converts them into the internal
 * [NetworkCaptureResult] model.
 *
 * ## HAR timings → NetworkPhaseKind mapping
 *
 * | HAR timing | NetworkPhaseKind     | Confidence | Notes                                      |
 * |------------|----------------------|------------|---------------------------------------------|
 * | blocked    | DISPATCHER_QUEUE     | INFERRED   | Blocked/stalled time before the request     |
 * | dns        | DNS                  | INFERRED   | -1 means DNS was unavailable (not zero)     |
 * | connect    | CONNECT              | INFERRED   | TCP connect only; TLS is separate (ssl)     |
 * | ssl        | TLS                  | INFERRED   | -1 means no TLS / reused connection         |
 * | send       | REQUEST_BODY         | INFERRED   | In Chrome this includes request header time |
 * | wait       | SERVER_WAIT          | INFERRED   | Time to first byte (TTFB)                   |
 * | receive    | RESPONSE_BODY        | INFERRED   | Response body download time                 |
 * | (omitted)  | REQUEST_HEADERS      | —          | HAR does not separate headers from body     |
 * | (omitted)  | RESPONSE_HEADERS     | —          | HAR does not separate headers from body     |
 *
 * All HAR-derived phases use [NetworkConfidence.INFERRED] because HAR
 * timings are wall-clock-based and may be collected by different tools
 * (Chrome DevTools, Fiddler, Charles Proxy) with varying precision and
 * starting points.
 *
 * ### Creator-specific handling
 *
 * Different HAR producers have subtle differences in timing semantics:
 * - **Chrome DevTools**: `send` includes request header time; `blocked`
 *   includes proxy negotiation and queue time.
 * - **Fiddler**: `send` starts after Fiddler receives the full request, so
 *   server-side processing may already be in-flight.
 * - **Charles Proxy**: `connect` may include SSL time when `ssl` is -1.
 *
 * These differences are recorded in the session's `coverage.observedLibraries`
 * field as `"<creator.name> <creator.version>"` to aid interpretation.
 *
 * ### Connection reuse detection
 *
 * HAR entries with `dns=-1`, `connect=-1`, and `ssl=-1` are marked as
 * [HttpExchange.connectionReused] = true (connection taken from pool).
 *
 * @param maxBytes maximum HAR file size in bytes (default 512 MiB).
 */
public class HarParser(
    private val maxBytes: Long = 512L * 1024L * 1024L,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    public fun parse(path: Path): NetworkCaptureResult {
        require(Files.isRegularFile(path)) { "HAR file does not exist: $path" }
        require(Files.size(path) <= maxBytes) { "HAR exceeds $maxBytes bytes" }
        val bytes = Files.readAllBytes(path)
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val log = root["log"]?.jsonObject ?: error("HAR log object is missing")
        val version = log.string("version") ?: error("HAR log.version is missing")
        require(version in SUPPORTED_VERSIONS) { "Unsupported HAR version: $version" }
        val entries = log["entries"]?.jsonArray ?: error("HAR entries array is missing")
        val parsedStarts = entries.mapNotNull { it.jsonObject.string("startedDateTime")?.let(::parseInstantOrNull) }
        val base = parsedStarts.minOrNull() ?: Instant.EPOCH
        val warnings = mutableListOf<String>()
        val redactor = NetworkUrlRedactor()
        var invalidTimingCount = 0
        val calls = entries.mapIndexedNotNull { index, entry ->
            runCatching { parseEntry(entry.jsonObject, index, base, redactor) }
                .onFailure { warnings += "Entry #$index skipped: ${it.message}" }
                .getOrNull()
                ?.also { parsed -> invalidTimingCount += parsed.invalidTimingCount }
                ?.call
        }
        require(calls.isNotEmpty()) { "HAR contains no valid entries" }
        if (invalidTimingCount > 0) warnings += "HAR contains $invalidTimingCount invalid timing values."
        val skipped = entries.size - calls.size
        val completenessStatus = if (skipped == 0 && invalidTimingCount == 0) EvidenceCompleteness.COMPLETE else EvidenceCompleteness.PARTIAL
        val producerObject = log["creator"] as? JsonObject
        val producerName = producerObject?.string("name") ?: "unknown HAR producer"
        val producerVersion = producerObject?.string("version")
        val endedNs = calls.mapNotNull { it.endedNs }.maxOrNull()
        return NetworkCaptureResult(
            session = NetworkSession(
                id = UUID.randomUUID().toString(),
                deviceSerial = null,
                packageName = null,
                startedAt = base,
                endedAt = endedNs?.let(base::plusNanos),
                coverage = NetworkCoverage(
                    processIds = emptySet(),
                    observedLibraries = setOf(producerName),
                    observedInstrumentationIds = emptySet(),
                    instrumentationMode = InstrumentationMode.HAR_IMPORT,
                    supportedEventKinds = setOf("HAR $version"),
                    knownLimitations = setOf("HAR does not identify unrecorded application traffic"),
                    windowStartedNs = 0,
                    windowEndedNs = endedNs,
                ),
                completeness = NetworkEvidenceCompleteness(completenessStatus, 0, 0, 0, skipped),
                sourceTimeDomain = NetworkTimeDomain.HAR_WALL_CLOCK,
                sourceTimeOriginNs = 0,
                clockMapping = null,
                status = if (completenessStatus == EvidenceCompleteness.COMPLETE) NetworkSessionStatus.COMPLETE else NetworkSessionStatus.PARTIAL,
                redactionPolicyVersion = NETWORK_REDACTION_POLICY_VERSION,
                sourceFormatVersion = version,
                sourceProducer = listOfNotNull(producerName, producerVersion).joinToString(" "),
                sourceFingerprint = sha256(bytes),
                warnings = warnings,
            ),
            calls = calls,
        )
    }

    private fun parseEntry(entry: JsonObject, index: Int, base: Instant, redactor: NetworkUrlRedactor): ParsedEntry {
        val request = entry["request"]?.jsonObject ?: error("request is missing")
        val response = entry["response"] as? JsonObject ?: JsonObject(emptyMap())
        val started = entry.string("startedDateTime")?.let(::parseInstantOrNull) ?: base
        val startedNs = java.time.Duration.between(base, started).toNanos().coerceAtLeast(0)
        val totalMs = entry.double("time")?.takeIf { it >= 0 }
        val timings = entry["timings"] as? JsonObject ?: JsonObject(emptyMap())
        var invalidTimingCount = 0
        val phases = HAR_TIMINGS.map { mapping ->
            val raw = timings.double(mapping.field)
            val availability = when {
                raw == null -> TimingAvailability.UNAVAILABLE
                raw >= 0 -> TimingAvailability.VALUE
                raw == -1.0 && mapping.kind == NetworkPhaseKind.TLS && request.string("url")?.startsWith("http://", true) == true -> TimingAvailability.NOT_APPLICABLE
                raw == -1.0 -> TimingAvailability.UNAVAILABLE
                else -> TimingAvailability.INVALID.also { invalidTimingCount++ }
            }
            NetworkPhase(
                kind = mapping.kind,
                startNs = null,
                endNs = null,
                confidence = if (availability == TimingAvailability.VALUE) NetworkConfidence.INFERRED else NetworkConfidence.UNKNOWN,
                reportedDurationNs = raw?.takeIf { it >= 0 }?.let(::millisecondsToNs),
                availability = availability,
                parentKind = mapping.parent,
            )
        }.toMutableList()
        phases += NetworkPhase(
            kind = NetworkPhaseKind.TOTAL,
            startNs = startedNs,
            endNs = totalMs?.let { startedNs + millisecondsToNs(it) },
            confidence = if (totalMs == null) NetworkConfidence.UNKNOWN else NetworkConfidence.INFERRED,
            reportedDurationNs = totalMs?.let(::millisecondsToNs),
            availability = if (totalMs == null) TimingAvailability.UNAVAILABLE else TimingAvailability.VALUE,
        )
        val status = response.int("status")?.takeIf { it > 0 }
        val failure = entry.string("_error")?.let { NetworkFailure("HAR_ERROR", "<redacted>", null) }
        val endedNs = totalMs?.let { startedNs + millisecondsToNs(it) }
        val requestHeaders = NetworkHeaderRedactor.redact(headerPairs(request["headers"]))
        val responseHeaders = NetworkHeaderRedactor.redact(headerPairs(response["headers"]))
        val bodySize = response.long("bodySize")?.takeIf { it >= 0 }
        val decodedSize = (response["content"] as? JsonObject)?.long("size")?.takeIf { it >= 0 }
        val knownTimingNames = HAR_TIMINGS.mapTo(mutableSetOf()) { it.field }
        val unknownTimings = timings.entries.mapNotNull { (name, value) ->
            if (name in knownTimingNames || (value as? JsonPrimitive)?.doubleOrNull == null) null else "har.timings.$name" to value.content
        }.toMap()
        // Connection reuse detection: dns=-1, connect=-1, ssl=-1 → reused from pool
        val dnsMs = timings.double("dns")?.takeIf { it >= 0 }
        val connectMs = timings.double("connect")?.takeIf { it >= 0 }
        val sslMs = timings.double("ssl")?.takeIf { it >= 0 }
        val connectionReused = dnsMs == null && connectMs == null && sslMs == null
        val call = HttpCall(
            callId = entry.string("_requestId") ?: "har-$index",
            instrumentationId = null,
            method = request.string("method") ?: "UNKNOWN",
            redactedUrl = redactor.redact(request.string("url") ?: "redacted://unknown"),
            startedNs = startedNs,
            endedNs = endedNs,
            exchanges = listOf(
                HttpExchange(
                    exchangeIndex = 0,
                    connectionId = entry.string("connection"),
                    connectionUse = ConnectionUse.UNKNOWN,
                    protocol = response.string("httpVersion"),
                    statusCode = status,
                    requestBytes = request.long("bodySize")?.takeIf { it >= 0 },
                    responseBytes = bodySize,
                    decodedResponseBytes = decodedSize,
                    phases = phases,
                    cacheDisposition = CacheDisposition.UNKNOWN,
                    failure = failure,
                    requestHeaders = requestHeaders,
                    responseHeaders = responseHeaders,
                    sourceAttributes = unknownTimings,
                    connectionReused = connectionReused,
                ),
            ),
            outcome = when {
                failure != null -> CallOutcome.FAILED
                endedNs == null -> CallOutcome.INCOMPLETE
                else -> CallOutcome.COMPLETED
            },
            source = NetworkEvidenceSource.HAR_IMPORT,
        )
        return ParsedEntry(call, invalidTimingCount)
    }

    private fun headerPairs(element: JsonElement?): List<Pair<String, String>> = (element as? JsonArray).orEmpty().mapNotNull { value ->
        val header = value as? JsonObject ?: return@mapNotNull null
        val name = header.string("name") ?: return@mapNotNull null
        name to header.string("value").orEmpty()
    }

    private fun parseInstantOrNull(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    private fun millisecondsToNs(value: Double): Long = (value * 1_000_000.0).toLong()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class ParsedEntry(
        val call: HttpCall,
        val invalidTimingCount: Int,
    )

    private data class HarTiming(
        val field: String,
        val kind: NetworkPhaseKind,
        val parent: NetworkPhaseKind? = null,
    )

    private companion object {
        val SUPPORTED_VERSIONS = setOf("1.1", "1.2")
        val HAR_TIMINGS = listOf(
            HarTiming("blocked", NetworkPhaseKind.DISPATCHER_QUEUE),
            HarTiming("dns", NetworkPhaseKind.DNS),
            HarTiming("connect", NetworkPhaseKind.CONNECT),
            HarTiming("ssl", NetworkPhaseKind.TLS, NetworkPhaseKind.CONNECT),
            HarTiming("send", NetworkPhaseKind.REQUEST_BODY),
            HarTiming("wait", NetworkPhaseKind.SERVER_WAIT),
            HarTiming("receive", NetworkPhaseKind.RESPONSE_BODY),
        )
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)
