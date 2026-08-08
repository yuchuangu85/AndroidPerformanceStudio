@file:Suppress("LongMethod", "MagicNumber", "ReturnCount")

package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
