@file:Suppress("LongMethod", "MagicNumber", "ReturnCount")

package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
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
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val log = root["log"]?.jsonObject ?: error("HAR log object is missing")
        require(log.string("version") == HAR_VERSION) { "Unsupported HAR version; expected $HAR_VERSION" }
        val entries = log["entries"]?.jsonArray ?: error("HAR entries array is missing")
        val parsedStarts = entries.mapNotNull { it.jsonObject.string("startedDateTime")?.let(::parseInstant) }
        val base = parsedStarts.minOrNull() ?: Instant.EPOCH
        val warnings = mutableListOf<String>()
        val calls = entries.mapIndexedNotNull { index, entry ->
            runCatching { parseEntry(entry.jsonObject, index, base) }.onFailure { warnings += "Entry #$index skipped: ${it.message}" }.getOrNull()
        }
        require(calls.isNotEmpty()) { "HAR contains no valid entries" }
        val partial = calls.any { call -> call.exchanges.any { exchange -> exchange.phases.none { it.kind == NetworkPhaseKind.DNS } || exchange.phases.any { it.durationNs == null } } }
        val producer = log["creator"]?.jsonObject?.let { creator ->
            listOfNotNull(creator.string("name"), creator.string("version")).joinToString(" ").ifBlank { "unknown HAR producer" }
        } ?: "unknown HAR producer"
        return NetworkCaptureResult(
            NetworkSession(
                id = UUID.randomUUID().toString(), deviceSerial = null, packageName = null, startedAt = base,
                endedAt = calls.mapNotNull { it.endedNs }.maxOrNull()?.let { base.plusNanos(it) },
                coverage = NetworkCoverage(setOf(producer), InstrumentationMode.HAR_IMPORT, setOf("HAR $HAR_VERSION"), emptySet(), 0, if (partial) NetworkConfidence.PARTIAL else NetworkConfidence.EXACT),
                clockMapping = null, status = if (warnings.isEmpty()) NetworkSessionStatus.COMPLETE else NetworkSessionStatus.PARTIAL,
                warnings = warnings + if (partial) listOf("HAR contains unavailable timings; missing phases remain absent rather than 0 ms.") else emptyList(),
            ),
            calls, entries.size,
        )
    }

    private fun parseEntry(entry: JsonObject, index: Int, base: Instant): HttpCall {
        val request = entry["request"]?.jsonObject ?: error("request is missing")
        val response = entry["response"]?.jsonObject ?: JsonObject(emptyMap())
        val started = entry.string("startedDateTime")?.let(::parseInstant) ?: base
        val startedNs = java.time.Duration.between(base, started).toNanos().coerceAtLeast(0)
        val totalMs = entry.double("time")?.takeIf { it >= 0 }
        val timings = entry["timings"]?.jsonObject ?: JsonObject(emptyMap())
        var cursor = startedNs

        // HAR timings → NetworkPhaseKind mapping with INFERRED confidence
        // See class KDoc for the full mapping table
        val phases = buildList {
            listOf(
                "blocked" to NetworkPhaseKind.DISPATCHER_QUEUE,
                "dns" to NetworkPhaseKind.DNS,
                "connect" to NetworkPhaseKind.CONNECT,
                "ssl" to NetworkPhaseKind.TLS,
                "send" to NetworkPhaseKind.REQUEST_BODY,
                "wait" to NetworkPhaseKind.SERVER_WAIT,
                "receive" to NetworkPhaseKind.RESPONSE_BODY,
            ).forEach { (field, kind) ->
                timings.double(field)?.takeIf { it >= 0 }?.let { milliseconds ->
                    val end = cursor + (milliseconds * 1_000_000.0).toLong()
                    add(NetworkPhase(kind, cursor, end, NetworkConfidence.INFERRED))
                    cursor = end
                }
            }
            totalMs?.let { add(NetworkPhase(NetworkPhaseKind.TOTAL, startedNs, startedNs + (it * 1_000_000.0).toLong(), NetworkConfidence.INFERRED)) }
        }

        // Connection reuse detection: dns=-1, connect=-1, ssl=-1 → reused
        val dnsMs = timings.double("dns")?.takeIf { it >= 0 }
        val connectMs = timings.double("connect")?.takeIf { it >= 0 }
        val sslMs = timings.double("ssl")?.takeIf { it >= 0 }
        val connectionReused = dnsMs == null && connectMs == null && sslMs == null

        val status = response.int("status")?.takeIf { it > 0 }
        val failure = entry.string("_error")?.let { NetworkFailure("HAR_ERROR", it, null) }
        val endedNs = totalMs?.let { startedNs + (it * 1_000_000.0).toLong() }

        // responseBytes: prefer bodySize, fall back to content.size
        val responseBodySize = response.long("bodySize")?.takeIf { it >= 0 }
            ?: response["content"]?.jsonObject?.long("size")?.takeIf { it >= 0 }

        return HttpCall(
            callId = entry.string("_requestId") ?: "har-$index",
            method = request.string("method") ?: "UNKNOWN",
            redactedUrl = NetworkUrlRedactor.default().redact(request.string("url") ?: "redacted://unknown"),
            startedNs = startedNs, endedNs = endedNs,
            exchanges = listOf(
                HttpExchange(
                    0, entry.string("connection"), response.string("httpVersion"), status,
                    request.long("bodySize")?.takeIf { it >= 0 }, responseBodySize,
                    phases, CacheDisposition.UNKNOWN, failure, redactHeaders(request["headers"]), redactHeaders(response["headers"]),
                    connectionReused = connectionReused,
                ),
            ),
            outcome = when {
                failure != null -> CallOutcome.FAILED
                endedNs == null -> CallOutcome.INCOMPLETE
                else -> CallOutcome.SUCCESS
            },
            source = NetworkEvidenceSource.HAR_IMPORT,
        )
    }

    private fun redactHeaders(element: JsonElement?): Map<String, String> = (element as? JsonArray).orEmpty().mapNotNull { value ->
        val header = value as? JsonObject ?: return@mapNotNull null
        val name = header.string("name") ?: return@mapNotNull null
        val raw = header.string("value").orEmpty()
        name to if (SENSITIVE_HEADERS.any { name.equals(it, true) } || name.contains("token", true) || name.contains("key", true)) "<redacted>" else raw
    }.toMap()

    private companion object {
        const val HAR_VERSION = "1.2"
        val SENSITIVE_HEADERS = setOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)
