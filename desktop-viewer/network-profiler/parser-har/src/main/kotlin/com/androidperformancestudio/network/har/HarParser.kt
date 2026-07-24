@file:Suppress("LongMethod", "MagicNumber", "ReturnCount")

package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.model.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

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
        val entries = log["entries"]?.jsonArray ?: error("HAR entries array is missing")
        val parsedStarts = entries.mapNotNull { it.jsonObject.string("startedDateTime")?.let(::parseInstant) }
        val base = parsedStarts.minOrNull() ?: Instant.EPOCH
        val warnings = mutableListOf<String>()
        val calls = entries.mapIndexedNotNull { index, entry ->
            runCatching { parseEntry(entry.jsonObject, index, base) }.onFailure { warnings += "Entry #$index skipped: ${it.message}" }.getOrNull()
        }
        require(calls.isNotEmpty()) { "HAR contains no valid entries" }
        val partial = calls.any { call -> call.exchanges.any { exchange -> exchange.phases.none { it.kind == NetworkPhaseKind.DNS } || exchange.phases.any { it.durationNs == null } } }
        val producer = log["creator"]?.jsonObject?.string("name") ?: "unknown HAR producer"
        return NetworkCaptureResult(
            NetworkSession(
                id = UUID.randomUUID().toString(), deviceSerial = null, packageName = null, startedAt = base,
                endedAt = calls.mapNotNull { it.endedNs }.maxOrNull()?.let { base.plusNanos(it) },
                coverage = NetworkCoverage(setOf(producer), InstrumentationMode.HAR_IMPORT, setOf("HAR 1.2"), emptySet(), 0, if (partial) NetworkConfidence.PARTIAL else NetworkConfidence.EXACT),
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
                    add(NetworkPhase(kind, cursor, end, NetworkConfidence.DERIVED))
                    cursor = end
                }
            }
            totalMs?.let { add(NetworkPhase(NetworkPhaseKind.TOTAL, startedNs, startedNs + (it * 1_000_000.0).toLong(), NetworkConfidence.EXACT)) }
        }
        val status = response.int("status")?.takeIf { it > 0 }
        val failure = entry.string("_error")?.let { NetworkFailure("HAR_ERROR", it, null) }
        val endedNs = totalMs?.let { startedNs + (it * 1_000_000.0).toLong() }
        return HttpCall(
            callId = entry.string("_requestId") ?: "har-$index",
            method = request.string("method") ?: "UNKNOWN",
            redactedUrl = redactUrl(request.string("url") ?: "redacted://unknown"),
            startedNs = startedNs, endedNs = endedNs,
            exchanges = listOf(
                HttpExchange(
                    0, entry.string("connection"), response.string("httpVersion"), status,
                    request.long("bodySize")?.takeIf { it >= 0 }, response.long("bodySize")?.takeIf { it >= 0 } ?: response["content"]?.jsonObject?.long("size")?.takeIf { it >= 0 },
                    phases, CacheDisposition.UNKNOWN, failure, redactHeaders(request["headers"]), redactHeaders(response["headers"]),
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

    private fun redactUrl(raw: String): String = runCatching {
        val uri = URI(raw)
        val query = uri.rawQuery?.split('&')?.joinToString("&") { part -> URLEncoder.encode(part.substringBefore('='), StandardCharsets.UTF_8) + "=<redacted>" }
        URI(uri.scheme, uri.userInfo?.let { "<redacted>" }, uri.host, uri.port, uri.path, query, null).toString()
    }.getOrElse { "redacted://invalid-url" }

    private fun parseInstant(raw: String): Instant = Instant.parse(raw)

    private companion object {
        val SENSITIVE_HEADERS = setOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
