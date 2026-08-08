@file:Suppress("LongMethod", "MagicNumber", "ReturnCount", "TooManyFunctions")

package com.androidperformancestudio.network.capture

import com.androidperformancestudio.network.model.*
import com.androidperformancestudio.network.protocol.AgentCommand
import com.androidperformancestudio.network.protocol.AgentNetworkEvent
import com.androidperformancestudio.network.protocol.NETWORK_AGENT_PORT
import com.androidperformancestudio.network.protocol.NETWORK_AGENT_PROTOCOL_VERSION
import com.androidperformancestudio.network.protocol.NetworkAgentCodec
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

public data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

public interface AdbCommandRunner {
    public fun run(arguments: List<String>, timeout: Duration = Duration.ofSeconds(10)): CommandResult
}

public class ProcessAdbCommandRunner : AdbCommandRunner {
    override fun run(arguments: List<String>, timeout: Duration): CommandResult {
        val process = ProcessBuilder(arguments).start()
        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        return CommandResult(if (finished) process.exitValue() else -1, process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText(), !finished)
    }
}

public class ActiveNetworkCapture internal constructor(
    public val serial: String,
    public val packageName: String,
    internal val socket: Socket,
    internal val localPort: Int,
    internal val processId: Int?,
    internal val startedAt: Instant,
    internal val hostReferenceNs: Long,
    internal val deviceReferenceNs: Long,
    internal val mappingErrorNs: Long,
    internal val startSequence: Long,
    internal val droppedBaseline: Long,
    internal val events: MutableList<AgentNetworkEvent>,
    internal var latestSequence: Long,
    internal var droppedEvents: Long,
)

public class NetworkAgentCapture(
    private val adb: Path = Path.of("adb"),
    private val runner: AdbCommandRunner = ProcessAdbCommandRunner(),
) {
    public fun start(serial: String, packageName: String): ActiveNetworkCapture {
        require(serial.isNotBlank()) { "Device serial is required" }
        require(PACKAGE_PATTERN.matches(packageName)) { "Invalid Android package name" }
        val tokenResult = runner.run(listOf(adb.toString(), "-s", serial, "shell", "run-as", packageName, "cat", "files/aps-network/token"))
        check(tokenResult.exitCode == 0 && tokenResult.stdout.isNotBlank()) { "Network Agent token is unavailable. Ensure the debuggable app is running and the Agent initializer is installed: ${tokenResult.stderr.trim()}" }
        val forward = runner.run(listOf(adb.toString(), "-s", serial, "forward", "tcp:0", "tcp:$NETWORK_AGENT_PORT"))
        check(forward.exitCode == 0) { "Unable to create ADB forward: ${forward.stderr.trim()}" }
        val localPort = forward.stdout.trim().toIntOrNull() ?: error("ADB did not return an allocated local port")
        val socket = runCatching { Socket(InetAddress.getLoopbackAddress(), localPort).apply { soTimeout = 10_000 } }.getOrElse { failure ->
            runner.run(listOf(adb.toString(), "-s", serial, "forward", "--remove", "tcp:$localPort"))
            throw failure
        }
        return try {
            val wallBefore = Instant.now()
            val hostBefore = System.nanoTime()
            NetworkAgentCodec.writeCommand(socket.getOutputStream(), AgentCommand("HELLO", token = tokenResult.stdout.trim()))
            val ready = NetworkAgentCodec.readResponse(socket.getInputStream())
            val hostAfter = System.nanoTime()
            check(ready.type == "READY") { ready.message ?: "Network Agent rejected the session" }
            val halfRoundTrip = ((hostAfter - hostBefore).coerceAtLeast(0)) / 2
            ActiveNetworkCapture(
                serial = serial,
                packageName = packageName,
                socket = socket,
                localPort = localPort,
                processId = ready.processId,
                startedAt = wallBefore.plusNanos(halfRoundTrip),
                hostReferenceNs = hostBefore + halfRoundTrip,
                deviceReferenceNs = ready.deviceMonotonicNs,
                mappingErrorNs = halfRoundTrip,
                startSequence = ready.latestSequence,
                droppedBaseline = ready.droppedEvents,
                events = mutableListOf(),
                latestSequence = ready.latestSequence,
                droppedEvents = ready.droppedEvents,
            )
        } catch (failure: Exception) {
            runCatching(socket::close)
            runner.run(listOf(adb.toString(), "-s", serial, "forward", "--remove", "tcp:$localPort"))
            throw failure
        }
    }

    public fun poll(capture: ActiveNetworkCapture, maxEvents: Int = 1_000): List<AgentNetworkEvent> {
        NetworkAgentCodec.writeCommand(capture.socket.getOutputStream(), AgentCommand("POLL", maxEvents = maxEvents.coerceIn(1, 5_000)))
        val response = NetworkAgentCodec.readResponse(capture.socket.getInputStream())
        check(response.type == "EVENTS") { response.message ?: "Unexpected Agent response: ${response.type}" }
        capture.events += response.events
        capture.latestSequence = response.latestSequence
        capture.droppedEvents = response.droppedEvents
        return response.events
    }

    public fun stop(capture: ActiveNetworkCapture): NetworkCaptureResult {
        var finalResponse: com.androidperformancestudio.network.protocol.AgentResponse? = null
        try {
            do {
                NetworkAgentCodec.writeCommand(capture.socket.getOutputStream(), AgentCommand("STOP", maxEvents = 5_000))
                val response = NetworkAgentCodec.readResponse(capture.socket.getInputStream())
                check(response.type in setOf("STOPPING", "STOPPED")) { response.message ?: "Unexpected Agent response: ${response.type}" }
                capture.events += response.events
                capture.latestSequence = response.latestSequence
                capture.droppedEvents = response.droppedEvents
                finalResponse = response
            } while (response.type == "STOPPING")
        } finally {
            capture.socket.close()
            runner.run(listOf(adb.toString(), "-s", capture.serial, "forward", "--remove", "tcp:${capture.localPort}"))
        }

        val end = requireNotNull(finalResponse)
        val ordered = capture.events.distinctBy { it.sequence }.sortedBy { it.sequence }
        val assembly = NetworkEventAssembler().assembleWithDiagnostics(ordered, capture.deviceReferenceNs)
        val expectedEventCount = (end.latestSequence - capture.startSequence).coerceAtLeast(0)
        val sequenceGaps = (expectedEventCount - ordered.size).coerceAtLeast(0)
        val dropped = (end.droppedEvents - capture.droppedBaseline).coerceAtLeast(0)
        val completenessStatus = if (sequenceGaps == 0L && dropped == 0L && assembly.unpairedEvents == 0) EvidenceCompleteness.COMPLETE else EvidenceCompleteness.PARTIAL
        val status = if (completenessStatus == EvidenceCompleteness.COMPLETE) NetworkSessionStatus.COMPLETE else NetworkSessionStatus.PARTIAL
        val warnings = buildList {
            if (dropped > 0) add("Network Agent dropped $dropped events during this capture.")
            if (sequenceGaps > 0) add("Network event sequence contains $sequenceGaps missing positions.")
            if (assembly.unpairedEvents > 0) add("${assembly.unpairedEvents} phase starts have no matching end event.")
            add("Coverage is limited to OkHttp clients configured with NetworkProfiler.eventListenerFactory.")
        }
        val rawEvents = ordered.map { event ->
            RawNetworkEvent(
                sequence = event.sequence,
                callId = event.callId,
                instrumentationId = event.instrumentationId,
                kind = event.kind,
                sourceTimestampNs = event.monotonicNs,
                relativeTimestampNs = (event.monotonicNs - capture.deviceReferenceNs).coerceAtLeast(0),
                method = event.method,
                redactedUrl = event.url,
                statusCode = event.statusCode,
                byteCount = event.byteCount,
                protocol = event.protocol,
                connectionId = event.connectionId,
                tlsVersion = event.tlsVersion,
                cipherSuite = event.cipherSuite,
                message = event.message,
            )
        }
        val sessionId = UUID.randomUUID().toString()
        return NetworkCaptureResult(
            session = NetworkSession(
                id = sessionId,
                deviceSerial = "device-${minimizedId(sessionId, capture.serial)}",
                packageName = capture.packageName,
                startedAt = capture.startedAt,
                endedAt = capture.startedAt.plusNanos((end.deviceMonotonicNs - capture.deviceReferenceNs).coerceAtLeast(0)),
                coverage = NetworkCoverage(
                    processIds = setOfNotNull(capture.processId),
                    observedLibraries = setOf("OkHttp"),
                    observedInstrumentationIds = ordered.mapNotNull { it.instrumentationId }.toSet(),
                    instrumentationMode = InstrumentationMode.EXPLICIT_FACTORY,
                    supportedEventKinds = ordered.map { it.kind }.toSet(),
                    knownLimitations = setOf("WebView", "Cronet", "URLConnection", "native sockets", "unconfigured OkHttp clients"),
                    windowStartedNs = 0,
                    windowEndedNs = (end.deviceMonotonicNs - capture.deviceReferenceNs).coerceAtLeast(0),
                ),
                completeness = NetworkEvidenceCompleteness(completenessStatus, dropped, sequenceGaps, assembly.unpairedEvents, 0),
                sourceTimeDomain = NetworkTimeDomain.DEVICE_MONOTONIC,
                sourceTimeOriginNs = capture.deviceReferenceNs,
                clockMapping = ClockMapping(capture.deviceReferenceNs, capture.hostReferenceNs, capture.startedAt, capture.mappingErrorNs),
                status = status,
                redactionPolicyVersion = NETWORK_REDACTION_POLICY_VERSION,
                sourceFormatVersion = NETWORK_AGENT_PROTOCOL_VERSION.toString(),
                sourceProducer = "APS Network Agent",
                warnings = warnings,
            ),
            calls = assembly.calls,
            rawEvents = rawEvents,
        )
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        fun minimizedId(salt: String, value: String): String = MessageDigest.getInstance("SHA-256").digest("$salt:$value".toByteArray()).take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

public data class NetworkAssemblyResult(
    val calls: List<HttpCall>,
    val unpairedEvents: Int,
)

public class NetworkEventAssembler {
    public fun assemble(events: List<AgentNetworkEvent>, timeOriginNs: Long = 0): List<HttpCall> = assembleWithDiagnostics(events, timeOriginNs).calls

    public fun assembleWithDiagnostics(events: List<AgentNetworkEvent>, timeOriginNs: Long = 0): NetworkAssemblyResult {
        var unpaired = 0
        val calls = events.groupBy { it.callId }.values.mapNotNull { callEvents ->
            val result = assembleCall(callEvents, timeOriginNs) ?: return@mapNotNull null
            unpaired += result.second
            result.first
        }.sortedBy { it.startedNs }
        return NetworkAssemblyResult(markConnectionReuse(calls), unpaired)
    }

    private fun assembleCall(events: List<AgentNetworkEvent>, originNs: Long): Pair<HttpCall, Int>? {
        val ordered = events.sortedBy { it.sequence }
        val first = ordered.firstOrNull() ?: return null
        val callStart = ordered.firstOrNull { it.kind == "callStart" }
        val terminal = ordered.lastOrNull { it.kind in CALL_TERMINALS }
        val ranges = exchangeRanges(ordered)
        var unpaired = 0
        val exchanges = ranges.mapIndexed { index, range ->
            val result = assembleExchange(ordered.subList(range.first, range.last + 1), index, originNs)
            unpaired += result.second
            result.first
        }
        val outcome = when {
            callStart == null -> CallOutcome.INCOMPLETE
            terminal?.kind == "callEnd" -> CallOutcome.COMPLETED
            terminal?.kind == "canceled" -> CallOutcome.CANCELLED
            terminal?.kind == "callFailed" -> CallOutcome.FAILED
            else -> CallOutcome.INCOMPLETE
        }
        return HttpCall(
            callId = first.callId,
            instrumentationId = first.instrumentationId,
            method = first.method ?: "UNKNOWN",
            redactedUrl = first.url ?: "redacted://unknown",
            startedNs = ((callStart ?: first).monotonicNs - originNs).coerceAtLeast(0),
            endedNs = terminal?.let { (it.monotonicNs - originNs).coerceAtLeast(0) },
            exchanges = exchanges,
            outcome = outcome,
            source = NetworkEvidenceSource.OKHTTP_EVENT_LISTENER,
        ) to unpaired
    }

    private fun exchangeRanges(events: List<AgentNetworkEvent>): List<IntRange> {
        val requests = events.indices.filter { events[it].kind == "requestHeadersStart" }
        if (requests.isEmpty()) return listOf(events.indices)
        val starts = requests.mapIndexed { index, requestIndex ->
            val lower = if (index == 0) 0 else requests[index - 1] + 1
            val lastTerminal = (requestIndex - 1 downTo lower).firstOrNull { events[it].kind in EXCHANGE_BOUNDARIES }
            lastTerminal?.plus(1) ?: lower
        }.distinct()
        return starts.mapIndexed { index, start -> start..((starts.getOrNull(index + 1) ?: events.size) - 1) }
    }

    private fun assembleExchange(events: List<AgentNetworkEvent>, index: Int, originNs: Long): Pair<HttpExchange, Int> {
        val phases = mutableListOf<NetworkPhase>()
        var unpaired = 0
        listOf(
            PhasePair("proxySelectStart", setOf("proxySelectEnd"), NetworkPhaseKind.PROXY_SELECT, null),
            PhasePair("dnsStart", setOf("dnsEnd"), NetworkPhaseKind.DNS, null),
            PhasePair("connectStart", setOf("connectEnd", "connectFailed"), NetworkPhaseKind.CONNECT, null),
            PhasePair("secureConnectStart", setOf("secureConnectEnd"), NetworkPhaseKind.TLS, NetworkPhaseKind.CONNECT),
            PhasePair("connectionAcquired", setOf("connectionReleased"), NetworkPhaseKind.CONNECTION_HELD, null),
            PhasePair("requestHeadersStart", setOf("requestHeadersEnd", "requestFailed"), NetworkPhaseKind.REQUEST_HEADERS, null),
            PhasePair("requestBodyStart", setOf("requestBodyEnd", "requestFailed"), NetworkPhaseKind.REQUEST_BODY, null),
            PhasePair("responseHeadersStart", setOf("responseHeadersEnd", "responseFailed"), NetworkPhaseKind.RESPONSE_HEADERS, null),
            PhasePair("responseBodyStart", setOf("responseBodyEnd", "responseFailed"), NetworkPhaseKind.RESPONSE_BODY, null),
        ).forEach { pair ->
            val result = pairPhases(events, pair, originNs)
            phases += result.first
            unpaired += result.second
        }
        val requestEnd = events.lastOrNull { it.kind in setOf("requestBodyEnd", "requestHeadersEnd") }
        val responseStart = events.firstOrNull { it.kind == "responseHeadersStart" && (requestEnd == null || it.monotonicNs >= requestEnd.monotonicNs) }
        if (requestEnd != null && responseStart != null) phases += NetworkPhase(NetworkPhaseKind.SERVER_WAIT, requestEnd.relative(originNs), responseStart.relative(originNs), NetworkConfidence.DERIVED)
        val totalStart = events.firstOrNull { it.kind !in setOf("callStart") }
        val totalEnd = events.lastOrNull { it.kind in EXCHANGE_END_EVENTS }
        if (totalStart != null) phases += NetworkPhase(NetworkPhaseKind.TOTAL, totalStart.relative(originNs), totalEnd?.relative(originNs), if (totalEnd == null) NetworkConfidence.PARTIAL else NetworkConfidence.DERIVED)

        val connectionId = events.lastOrNull { it.connectionId != null }?.connectionId
        val statusEvent = events.lastOrNull { it.kind in setOf("responseHeadersEnd", "cacheHit", "satisfactionFailure") && it.statusCode != null }
        val failureEvent = events.lastOrNull { it.kind in EXCHANGE_FAILURES }
        val tlsEvent = events.lastOrNull { it.kind == "secureConnectEnd" && (it.tlsVersion != null || it.cipherSuite != null) }
        val cache = when {
            events.any { it.kind == "cacheConditionalHit" } -> CacheDisposition.CONDITIONAL_HIT
            events.any { it.kind == "cacheHit" } -> CacheDisposition.HIT
            events.any { it.kind == "cacheMiss" } -> CacheDisposition.MISS
            else -> CacheDisposition.UNKNOWN
        }
        return HttpExchange(
            exchangeIndex = index,
            connectionId = connectionId,
            connectionUse = if (events.any { it.kind == "connectStart" }) ConnectionUse.NEW else ConnectionUse.UNKNOWN,
            protocol = events.lastOrNull { it.protocol != null }?.protocol,
            statusCode = statusEvent?.statusCode,
            requestBytes = events.lastOrNull { it.kind == "requestBodyEnd" }?.byteCount,
            responseBytes = events.lastOrNull { it.kind == "responseBodyEnd" }?.byteCount,
            decodedResponseBytes = null,
            phases = phases.sortedBy { it.startNs ?: Long.MAX_VALUE },
            cacheDisposition = cache,
            failure = failureEvent?.let { failure -> NetworkFailure(failure.kind, failure.message, events.getOrNull(events.indexOf(failure) - 1)?.kind) },
            tlsHandshake = tlsEvent?.let { TlsHandshake(it.tlsVersion, it.cipherSuite, NetworkConfidence.EXACT) },
        ) to unpaired
    }

    private fun pairPhases(events: List<AgentNetworkEvent>, pair: PhasePair, originNs: Long): Pair<List<NetworkPhase>, Int> {
        val starts = ArrayDeque<AgentNetworkEvent>()
        val phases = mutableListOf<NetworkPhase>()
        events.forEach { event ->
            when {
                event.kind == pair.start -> starts.addLast(event)
                event.kind in pair.ends && starts.isNotEmpty() -> {
                    val start = starts.removeFirst()
                    phases += NetworkPhase(pair.kind, start.relative(originNs), event.relative(originNs), NetworkConfidence.EXACT, parentKind = pair.parent)
                }
            }
        }
        starts.forEach { start -> phases += NetworkPhase(pair.kind, start.relative(originNs), null, NetworkConfidence.PARTIAL, availability = TimingAvailability.UNAVAILABLE, parentKind = pair.parent) }
        return phases to starts.size
    }

    private fun markConnectionReuse(calls: List<HttpCall>): List<HttpCall> {
        val seen = mutableSetOf<String>()
        val uses = mutableMapOf<Pair<String, Int>, ConnectionUse>()
        calls.flatMap { call -> call.exchanges.map { exchange -> Triple(call.callId, exchange, exchange.phases.mapNotNull { it.startNs }.minOrNull() ?: call.startedNs) } }
            .sortedBy { it.third }
            .forEach { (callId, exchange) ->
                val connectionId = exchange.connectionId
                val use = when {
                    exchange.connectionUse == ConnectionUse.NEW -> ConnectionUse.NEW
                    connectionId != null && connectionId in seen -> ConnectionUse.REUSED
                    else -> ConnectionUse.UNKNOWN
                }
                uses[callId to exchange.exchangeIndex] = use
                connectionId?.let(seen::add)
            }
        return calls.map { call -> call.copy(exchanges = call.exchanges.map { it.copy(connectionUse = uses[call.callId to it.exchangeIndex] ?: it.connectionUse) }) }
    }

    private fun AgentNetworkEvent.relative(originNs: Long): Long = (monotonicNs - originNs).coerceAtLeast(0)

    private data class PhasePair(
        val start: String,
        val ends: Set<String>,
        val kind: NetworkPhaseKind,
        val parent: NetworkPhaseKind?,
    )

    private companion object {
        val CALL_TERMINALS = setOf("callEnd", "callFailed", "canceled")
        val EXCHANGE_FAILURES = setOf("connectFailed", "requestFailed", "responseFailed", "satisfactionFailure", "callFailed", "canceled")
        val EXCHANGE_BOUNDARIES = setOf("responseBodyEnd", "responseHeadersEnd", "responseFailed", "requestFailed", "connectionReleased")
        val EXCHANGE_END_EVENTS = EXCHANGE_BOUNDARIES + CALL_TERMINALS
    }
}
