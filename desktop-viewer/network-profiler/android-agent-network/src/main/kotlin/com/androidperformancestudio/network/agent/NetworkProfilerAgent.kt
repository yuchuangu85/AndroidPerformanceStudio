@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.network.agent

import android.content.Context
import android.util.Base64
import androidx.startup.Initializer
import com.androidperformancestudio.network.model.NetworkUrlRedactor
import com.androidperformancestudio.network.protocol.AgentCommand
import com.androidperformancestudio.network.protocol.AgentNetworkEvent
import com.androidperformancestudio.network.protocol.AgentResponse
import com.androidperformancestudio.network.protocol.NETWORK_AGENT_PORT
import com.androidperformancestudio.network.protocol.NETWORK_AGENT_PROTOCOL_VERSION
import com.androidperformancestudio.network.protocol.NetworkAgentCodec
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

public class NetworkProfilerInitializer : Initializer<Unit> {
    override fun create(context: Context) { NetworkAgentServer.start(context.applicationContext) }
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

public object NetworkProfiler {
    private val nextInstrumentationId = AtomicLong()

    public fun eventListenerFactory(existingFactory: EventListener.Factory? = null): EventListener.Factory {
        val instrumentationId = "okhttp-factory-${nextInstrumentationId.incrementAndGet()}"
        return EventListener.Factory { call -> CompositeEventListener(existingFactory?.create(call), ProfilerEventListener(call, instrumentationId)) }
    }
}

private data class EventBoundary(val sequence: Long, val dropped: Long, val monotonicNs: Long)

private object EventBuffer {
    private const val CAPACITY = 20_000
    private var sequence = 0L
    private var dropped = 0L
    private val events = ArrayDeque<AgentNetworkEvent>(CAPACITY)

    @Synchronized fun add(event: AgentNetworkEvent) {
        sequence++
        if (events.size == CAPACITY) dropped++ else events.addLast(event.copy(sequence = sequence))
    }

    @Synchronized fun beginSession(): EventBoundary {
        events.clear()
        return EventBoundary(sequence, dropped, System.nanoTime())
    }

    @Synchronized fun snapshot(): EventBoundary = EventBoundary(sequence, dropped, System.nanoTime())

    @Synchronized fun drain(afterSequence: Long, throughSequence: Long, max: Int): List<AgentNetworkEvent> = buildList {
        while (events.firstOrNull()?.sequence?.let { it <= afterSequence } == true) events.removeFirst()
        while (size < max) {
            val event = events.firstOrNull() ?: break
            if (event.sequence > throughSequence) break
            add(events.removeFirst())
        }
    }

    @Synchronized fun hasEvents(afterSequence: Long, throughSequence: Long): Boolean =
        events.any { it.sequence in (afterSequence + 1)..throughSequence }
}

private object AgentPrivacy {
    @Volatile private var redactor = NetworkUrlRedactor()

    fun beginSession() { redactor = NetworkUrlRedactor() }
    fun redact(raw: String): String = redactor.redact(raw)
}

private object ConnectionIdentities {
    // ponytail: process-lifetime map; use weak identity keys only if long debug sessions show measurable growth.
    private val ids = Collections.synchronizedMap(IdentityHashMap<Connection, String>())
    private val nextId = AtomicLong()

    fun id(connection: Connection): String = synchronized(ids) {
        ids.getOrPut(connection) { "connection-${nextId.incrementAndGet()}" }
    }
}

private class ProfilerEventListener(private val call: Call, private val instrumentationId: String) : EventListener() {
    private val callId = Integer.toHexString(System.identityHashCode(call)) + "-" + System.nanoTime().toString(16)

    private fun event(
        kind: String,
        status: Int? = null,
        bytes: Long? = null,
        protocol: String? = null,
        connection: String? = null,
        tlsVersion: String? = null,
        cipherSuite: String? = null,
        message: String? = null,
    ) {
        val request = call.request()
        EventBuffer.add(
            AgentNetworkEvent(
                sequence = 0,
                callId = callId,
                kind = kind,
                monotonicNs = System.nanoTime(),
                instrumentationId = instrumentationId,
                method = request.method,
                url = AgentPrivacy.redact(request.url.toString()),
                statusCode = status,
                byteCount = bytes,
                protocol = protocol,
                connectionId = connection,
                tlsVersion = tlsVersion,
                cipherSuite = cipherSuite,
                message = message,
            ),
        )
    }

    override fun callStart(call: Call) = event("callStart")
    override fun callEnd(call: Call) = event("callEnd")
    override fun callFailed(call: Call, ioe: IOException) = event("callFailed", message = ioe.javaClass.simpleName)
    override fun canceled(call: Call) = event("canceled")
    override fun proxySelectStart(call: Call, url: HttpUrl) = event("proxySelectStart")
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) = event("proxySelectEnd", message = proxies.joinToString("+") { it.type().name })
    override fun dnsStart(call: Call, domainName: String) = event("dnsStart")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = event("dnsEnd", message = inetAddressList.size.toString())
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = event("connectStart", message = proxy.type().name)
    override fun secureConnectStart(call: Call) = event("secureConnectStart")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = event("secureConnectEnd", tlsVersion = handshake?.tlsVersion?.javaName, cipherSuite = handshake?.cipherSuite?.javaName)
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = event("connectEnd", protocol = protocol?.toString())
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = event("connectFailed", protocol = protocol?.toString(), message = ioe.javaClass.simpleName)
    override fun connectionAcquired(call: Call, connection: Connection) = event("connectionAcquired", protocol = connection.protocol().toString(), connection = ConnectionIdentities.id(connection))
    override fun connectionReleased(call: Call, connection: Connection) = event("connectionReleased", connection = ConnectionIdentities.id(connection))
    override fun requestHeadersStart(call: Call) = event("requestHeadersStart")
    override fun requestHeadersEnd(call: Call, request: Request) = event("requestHeadersEnd")
    override fun requestBodyStart(call: Call) = event("requestBodyStart")
    override fun requestBodyEnd(call: Call, byteCount: Long) = event("requestBodyEnd", bytes = byteCount)
    override fun requestFailed(call: Call, ioe: IOException) = event("requestFailed", message = ioe.javaClass.simpleName)
    override fun responseHeadersStart(call: Call) = event("responseHeadersStart")
    override fun responseHeadersEnd(call: Call, response: Response) = event("responseHeadersEnd", status = response.code, protocol = response.protocol.toString())
    override fun responseBodyStart(call: Call) = event("responseBodyStart")
    override fun responseBodyEnd(call: Call, byteCount: Long) = event("responseBodyEnd", bytes = byteCount)
    override fun responseFailed(call: Call, ioe: IOException) = event("responseFailed", message = ioe.javaClass.simpleName)
    override fun satisfactionFailure(call: Call, response: Response) = event("satisfactionFailure", status = response.code)
    override fun cacheHit(call: Call, response: Response) = event("cacheHit", status = response.code)
    override fun cacheMiss(call: Call) = event("cacheMiss")
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) = event("cacheConditionalHit", status = cachedResponse.code)
}

private class CompositeEventListener(private val delegate: EventListener?, private val profiler: EventListener) : EventListener() {
    override fun callStart(call: Call) = both { it.callStart(call) }
    override fun callEnd(call: Call) = both { it.callEnd(call) }
    override fun callFailed(call: Call, ioe: IOException) = both { it.callFailed(call, ioe) }
    override fun canceled(call: Call) = both { it.canceled(call) }
    override fun proxySelectStart(call: Call, url: HttpUrl) = both { it.proxySelectStart(call, url) }
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) = both { it.proxySelectEnd(call, url, proxies) }
    override fun dnsStart(call: Call, domainName: String) = both { it.dnsStart(call, domainName) }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = both { it.dnsEnd(call, domainName, inetAddressList) }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = both { it.connectStart(call, inetSocketAddress, proxy) }
    override fun secureConnectStart(call: Call) = both { it.secureConnectStart(call) }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = both { it.secureConnectEnd(call, handshake) }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = both { it.connectEnd(call, inetSocketAddress, proxy, protocol) }
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = both { it.connectFailed(call, inetSocketAddress, proxy, protocol, ioe) }
    override fun connectionAcquired(call: Call, connection: Connection) = both { it.connectionAcquired(call, connection) }
    override fun connectionReleased(call: Call, connection: Connection) = both { it.connectionReleased(call, connection) }
    override fun requestHeadersStart(call: Call) = both { it.requestHeadersStart(call) }
    override fun requestHeadersEnd(call: Call, request: Request) = both { it.requestHeadersEnd(call, request) }
    override fun requestBodyStart(call: Call) = both { it.requestBodyStart(call) }
    override fun requestBodyEnd(call: Call, byteCount: Long) = both { it.requestBodyEnd(call, byteCount) }
    override fun requestFailed(call: Call, ioe: IOException) = both { it.requestFailed(call, ioe) }
    override fun responseHeadersStart(call: Call) = both { it.responseHeadersStart(call) }
    override fun responseHeadersEnd(call: Call, response: Response) = both { it.responseHeadersEnd(call, response) }
    override fun responseBodyStart(call: Call) = both { it.responseBodyStart(call) }
    override fun responseBodyEnd(call: Call, byteCount: Long) = both { it.responseBodyEnd(call, byteCount) }
    override fun responseFailed(call: Call, ioe: IOException) = both { it.responseFailed(call, ioe) }
    override fun satisfactionFailure(call: Call, response: Response) = both { it.satisfactionFailure(call, response) }
    override fun cacheHit(call: Call, response: Response) = both { it.cacheHit(call, response) }
    override fun cacheMiss(call: Call) = both { it.cacheMiss(call) }
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) = both { it.cacheConditionalHit(call, cachedResponse) }

    private inline fun both(block: (EventListener) -> Unit) { delegate?.let(block); block(profiler) }
}

private object NetworkAgentServer {
    private val started = AtomicBoolean()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val directory = File(context.filesDir, "aps-network").apply { mkdirs() }
        val tokenFile = File(directory, "token")
        val token = ByteArray(32).also(java.security.SecureRandom()::nextBytes).let { bytes -> Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        tokenFile.writeText(token)
        Thread({ serve(token) }, "aps-network-agent").apply { isDaemon = true; start() }
    }

    private fun serve(token: String) {
        runCatching {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), NETWORK_AGENT_PORT))
                while (true) server.accept().use { socket -> handle(socket.getInputStream(), socket.getOutputStream(), token) }
            }
        }
    }

    private fun handle(input: java.io.InputStream, output: java.io.OutputStream, token: String) {
        val hello = NetworkAgentCodec.readCommand(input)
        if (hello.type != "HELLO" || hello.token != token || hello.protocolVersion != NETWORK_AGENT_PROTOCOL_VERSION) {
            NetworkAgentCodec.writeResponse(output, AgentResponse("ERROR", message = "Authentication or protocol mismatch"))
            return
        }
        AgentPrivacy.beginSession()
        val start = EventBuffer.beginSession()
        NetworkAgentCodec.writeResponse(output, response("READY", start, emptyList(), false))
        var stopBoundary: EventBoundary? = null
        while (true) {
            val command = NetworkAgentCodec.readCommand(input)
            if (command.type !in setOf("POLL", "STOP")) {
                NetworkAgentCodec.writeResponse(output, AgentResponse("ERROR", message = "Unsupported command: ${command.type}"))
                continue
            }
            if (command.type == "STOP" && stopBoundary == null) stopBoundary = EventBuffer.snapshot()
            val boundary = stopBoundary ?: EventBuffer.snapshot()
            val upper = boundary.sequence
            val events = EventBuffer.drain(start.sequence, upper, command.maxEvents.coerceIn(1, 5_000))
            val more = EventBuffer.hasEvents(start.sequence, upper)
            val type = when {
                command.type == "STOP" && more -> "STOPPING"
                command.type == "STOP" -> "STOPPED"
                else -> "EVENTS"
            }
            NetworkAgentCodec.writeResponse(output, response(type, boundary, events, more))
            if (type == "STOPPED") return
        }
    }

    private fun response(type: String, boundary: EventBoundary, events: List<AgentNetworkEvent>, hasMore: Boolean): AgentResponse =
        AgentResponse(
            type = type,
            processId = android.os.Process.myPid(),
            events = events,
            droppedEvents = boundary.dropped,
            latestSequence = boundary.sequence,
            hasMore = hasMore,
            deviceMonotonicNs = boundary.monotonicNs,
        )
}
