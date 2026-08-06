@file:Suppress("LongMethod", "MagicNumber", "ReturnCount", "TooManyFunctions")

package com.androidperformancestudio.network.capture

import com.androidperformancestudio.network.model.CacheDisposition
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.ClockMapping
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.HttpExchange
import com.androidperformancestudio.network.model.InstrumentationMode
import com.androidperformancestudio.network.model.NetworkCaptureResult
import com.androidperformancestudio.network.model.NetworkConfidence
import com.androidperformancestudio.network.model.NetworkCoverage
import com.androidperformancestudio.network.model.NetworkEvidenceSource
import com.androidperformancestudio.network.model.NetworkFailure
import com.androidperformancestudio.network.model.NetworkPhase
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.model.NetworkSession
import com.androidperformancestudio.network.model.NetworkSessionStatus
import com.androidperformancestudio.network.model.TlsHandshake
import com.androidperformancestudio.network.protocol.AgentCommand
import com.androidperformancestudio.network.protocol.AgentNetworkEvent
import com.androidperformancestudio.network.protocol.NETWORK_AGENT_PORT
import com.androidperformancestudio.network.protocol.NetworkAgentCodec
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Path
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
    internal val startedAt: Instant,
    internal val hostReferenceNs: Long,
    internal val deviceReferenceNs: Long,
    internal val events: MutableList<AgentNetworkEvent>,
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
        val socket = Socket(InetAddress.getLoopbackAddress(), localPort).apply { soTimeout = 10_000 }
        val hostBefore = System.nanoTime()
        NetworkAgentCodec.writeCommand(socket.getOutputStream(), AgentCommand("HELLO", token = tokenResult.stdout.trim()))
        val ready = NetworkAgentCodec.readResponse(socket.getInputStream())
        val hostAfter = System.nanoTime()
        check(ready.type == "READY") { ready.message ?: "Network Agent rejected the session" }
        return ActiveNetworkCapture(serial, packageName, socket, localPort, Instant.now(), (hostBefore + hostAfter) / 2, ready.deviceMonotonicNs, mutableListOf(), ready.droppedEvents)
    }

    public fun poll(capture: ActiveNetworkCapture, maxEvents: Int = 1_000): List<AgentNetworkEvent> {
        NetworkAgentCodec.writeCommand(capture.socket.getOutputStream(), AgentCommand("POLL", maxEvents = maxEvents.coerceIn(1, 5_000)))
        val response = NetworkAgentCodec.readResponse(capture.socket.getInputStream())
        check(response.type == "EVENTS") { response.message ?: "Unexpected Agent response: ${response.type}" }
        capture.events += response.events
        capture.droppedEvents = response.droppedEvents
        return response.events
    }

    public fun stop(capture: ActiveNetworkCapture): NetworkCaptureResult {
        val response = runCatching {
            NetworkAgentCodec.writeCommand(capture.socket.getOutputStream(), AgentCommand("STOP", maxEvents = 5_000))
            NetworkAgentCodec.readResponse(capture.socket.getInputStream())
        }.getOrNull()
        if (response != null) {
            capture.events += response.events
            capture.droppedEvents = response.droppedEvents
        }
        capture.socket.close()
        runner.run(listOf(adb.toString(), "-s", capture.serial, "forward", "--remove", "tcp:${capture.localPort}"))
        val calls = NetworkEventAssembler().assemble(capture.events)
        val warnings = buildList {
            if (capture.droppedEvents > 0) add("Network Agent dropped ${capture.droppedEvents} events because its bounded queue was full.")
            add("Coverage is limited to OkHttp clients configured with NetworkProfiler.eventListenerFactory.")
        }
        return NetworkCaptureResult(
            NetworkSession(
                id = UUID.randomUUID().toString(), deviceSerial = capture.serial, packageName = capture.packageName,
                startedAt = capture.startedAt, endedAt = Instant.now(),
                coverage = NetworkCoverage(setOf("OkHttp"), InstrumentationMode.EXPLICIT_FACTORY, capture.events.map { it.kind }.toSet(), setOf("WebView", "Cronet", "URLConnection", "native sockets"), capture.droppedEvents, if (capture.droppedEvents == 0L) NetworkConfidence.PARTIAL else NetworkConfidence.UNKNOWN),
                clockMapping = ClockMapping(capture.deviceReferenceNs, capture.hostReferenceNs, 5_000_000),
                status = if (response == null || capture.droppedEvents > 0) NetworkSessionStatus.PARTIAL else NetworkSessionStatus.COMPLETE,
                warnings = warnings,
            ),
            calls, capture.events.size,
        )
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

/**
 * Assembles raw [AgentNetworkEvent] streams into structured [HttpCall] objects.
 *
 * ## Phase calculation summary
 *
 * Each [NetworkPhase] is derived from specific EventListener callback pairs:
 * - **DISPATCHER_QUEUE**: `callStart` → first of `dnsStart`/`connectStart` (APPROXIMATED)
 * - **DNS**: `dnsStart` → `dnsEnd` (EXACT; absent for reused connections)
 * - **CONNECT**: `connectStart` → `connectEnd` (EXACT; TCP only, absent for reused connections)
 * - **TLS**: `secureConnectStart` → `secureConnectEnd` (EXACT; absent for plaintext/reused)
 * - **REQUEST_HEADERS**: `requestHeadersStart` → `requestHeadersEnd` (EXACT)
 * - **REQUEST_BODY**: `requestBodyStart` → `requestBodyEnd` (EXACT; absent if no body)
 * - **SERVER_WAIT**: `max(requestBodyEnd, requestHeadersEnd)` → `responseHeadersStart` (DERIVED)
 * - **RESPONSE_HEADERS**: `responseHeadersStart` → `responseHeadersEnd` (EXACT)
 * - **RESPONSE_BODY**: `responseBodyStart` → `responseBodyEnd` (EXACT; absent if no body)
 * - **CONNECTION_HELD**: `connectionAcquired` → `connectionReleased` (EXACT)
 * - **TOTAL**: first event's monotonicNs → last event's monotonicNs (EXACT or PARTIAL)
 *
 * ### Connection reuse detection
 *
 * An exchange whose phases contain no DNS, CONNECT, or TLS phases is marked
 * as `connectionReused = true`. This indicates the underlying TCP+TLS
 * connection was taken from the connection pool rather than established fresh.
 */
public class NetworkEventAssembler {
    public fun assemble(events: List<AgentNetworkEvent>): List<HttpCall> =
        events.groupBy { it.callId }.values.mapNotNull(::assembleCall).sortedBy { it.startedNs }

    private fun assembleCall(events: List<AgentNetworkEvent>): HttpCall? {
        val ordered = events.sortedBy { it.sequence }
        val start = ordered.firstOrNull { it.kind == "callStart" } ?: ordered.firstOrNull() ?: return null
        val end = ordered.lastOrNull { it.kind in setOf("callEnd", "callFailed", "canceled") }
        val statusEvent = ordered.lastOrNull { it.statusCode != null }
        val requestBytes = ordered.lastOrNull { it.kind == "requestBodyEnd" }?.byteCount
        val responseBytes = ordered.lastOrNull { it.kind == "responseBodyEnd" }?.byteCount
        val failureEvent = ordered.lastOrNull { it.kind in setOf("callFailed", "canceled") }

        // Extract TLS handshake info from secureConnectEnd event
        val secureConnectEndEvent = ordered.lastOrNull { it.kind == "secureConnectEnd" }
        val tlsHandshake = if (secureConnectEndEvent != null && (secureConnectEndEvent.message != null || secureConnectEndEvent.cipherSuite != null)) {
            TlsHandshake(
                tlsVersion = secureConnectEndEvent.message?.takeIf { it.isNotBlank() },
                cipherSuite = secureConnectEndEvent.cipherSuite?.takeIf { it.isNotBlank() },
            )
        } else null

        val phases = buildList {
            // DISPATCHER_QUEUE: callStart → first of dnsStart/connectStart (APPROXIMATED)
            val firstAfterStart = ordered.firstOrNull { it.kind in setOf("dnsStart", "connectStart", "requestHeadersStart") && it.monotonicNs >= start.monotonicNs }
            if (firstAfterStart != null && firstAfterStart.monotonicNs > start.monotonicNs) {
                add(NetworkPhase(NetworkPhaseKind.DISPATCHER_QUEUE, start.monotonicNs, firstAfterStart.monotonicNs, NetworkConfidence.APPROXIMATED))
            }
            phase(ordered, "dnsStart", "dnsEnd", NetworkPhaseKind.DNS, NetworkConfidence.EXACT)?.let(::add)
            phase(ordered, "connectStart", "connectEnd", NetworkPhaseKind.CONNECT, NetworkConfidence.EXACT)?.let(::add)
            phase(ordered, "secureConnectStart", "secureConnectEnd", NetworkPhaseKind.TLS, NetworkConfidence.EXACT)?.let(::add)
            phase(ordered, "requestHeadersStart", "requestHeadersEnd", NetworkPhaseKind.REQUEST_HEADERS, NetworkConfidence.EXACT)?.let(::add)
            phase(ordered, "requestBodyStart", "requestBodyEnd", NetworkPhaseKind.REQUEST_BODY, NetworkConfidence.EXACT)?.let(::add)
            // SERVER_WAIT: max(requestBodyEnd, requestHeadersEnd) → responseHeadersStart (DERIVED)
            val requestEnd = ordered.lastOrNull { it.kind in setOf("requestBodyEnd", "requestHeadersEnd") }
            val responseStart = ordered.firstOrNull { it.kind == "responseHeadersStart" && (requestEnd == null || it.monotonicNs >= requestEnd.monotonicNs) }
            if (requestEnd != null && responseStart != null) add(NetworkPhase(NetworkPhaseKind.SERVER_WAIT, requestEnd.monotonicNs, responseStart.monotonicNs, NetworkConfidence.DERIVED))
            phase(ordered, "responseHeadersStart", "responseHeadersEnd", NetworkPhaseKind.RESPONSE_HEADERS, NetworkConfidence.EXACT)?.let(::add)
            phase(ordered, "responseBodyStart", "responseBodyEnd", NetworkPhaseKind.RESPONSE_BODY, NetworkConfidence.EXACT)?.let(::add)
            // CONNECTION_HELD: connectionAcquired → connectionReleased (EXACT)
            val acquired = ordered.firstOrNull { it.kind == "connectionAcquired" }
            val released = ordered.lastOrNull { it.kind == "connectionReleased" && (acquired == null || it.monotonicNs >= acquired.monotonicNs) }
            if (acquired != null) {
                add(NetworkPhase(NetworkPhaseKind.CONNECTION_HELD, acquired.monotonicNs, released?.monotonicNs, if (released != null) NetworkConfidence.EXACT else NetworkConfidence.PARTIAL))
            }
            add(NetworkPhase(NetworkPhaseKind.TOTAL, start.monotonicNs, end?.monotonicNs, if (end == null) NetworkConfidence.PARTIAL else NetworkConfidence.EXACT))
        }

        // Connection reuse: no new DNS/CONNECT/TLS → reused from pool
        val connectionReused = phases.none { it.kind in setOf(NetworkPhaseKind.DNS, NetworkPhaseKind.CONNECT, NetworkPhaseKind.TLS) }

        val cache = when {
            ordered.any { it.kind == "cacheHit" } -> CacheDisposition.HIT
            ordered.any { it.kind == "cacheMiss" } -> CacheDisposition.MISS
            else -> CacheDisposition.UNKNOWN
        }
        return HttpCall(
            callId = start.callId,
            method = start.method ?: "UNKNOWN",
            redactedUrl = start.url ?: "redacted://unknown",
            startedNs = start.monotonicNs,
            endedNs = end?.monotonicNs,
            exchanges = listOf(HttpExchange(0, ordered.lastOrNull { it.connectionId != null }?.connectionId, statusEvent?.protocol, statusEvent?.statusCode, requestBytes, responseBytes, phases, cache, failureEvent?.let { NetworkFailure(it.kind, it.message, ordered.getOrNull(ordered.indexOf(it) - 1)?.kind) }, tlsHandshake = tlsHandshake, connectionReused = connectionReused)),
            outcome = when (end?.kind) {
                "callEnd" -> CallOutcome.SUCCESS
                "canceled" -> CallOutcome.CANCELLED
                "callFailed" -> CallOutcome.FAILED
                else -> CallOutcome.INCOMPLETE
            },
            source = NetworkEvidenceSource.OKHTTP_EVENT_LISTENER,
        )
    }

    private fun phase(events: List<AgentNetworkEvent>, start: String, end: String, kind: NetworkPhaseKind, confidence: NetworkConfidence): NetworkPhase? {
        val from = events.firstOrNull { it.kind == start } ?: return null
        val to = events.firstOrNull { it.kind == end && it.monotonicNs >= from.monotonicNs }
        return NetworkPhase(kind, from.monotonicNs, to?.monotonicNs, if (to == null) NetworkConfidence.PARTIAL else confidence)
    }
}
