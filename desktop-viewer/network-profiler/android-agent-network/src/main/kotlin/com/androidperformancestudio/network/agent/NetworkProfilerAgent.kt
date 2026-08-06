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
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

public class NetworkProfilerInitializer : Initializer<Unit> {
    override fun create(context: Context) { NetworkAgentServer.start(context.applicationContext) }
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

public object NetworkProfiler {
    public fun eventListenerFactory(existingFactory: EventListener.Factory? = null): EventListener.Factory =
        EventListener.Factory { call -> CompositeEventListener(existingFactory?.create(call), ProfilerEventListener(call)) }
}

private object EventBuffer {
    private const val CAPACITY = 20_000
    private val sequence = AtomicLong()
    private val dropped = AtomicLong()
    private val events = ArrayBlockingQueue<AgentNetworkEvent>(CAPACITY)
    fun add(event: AgentNetworkEvent) { if (!events.offer(event.copy(sequence = sequence.incrementAndGet()))) dropped.incrementAndGet() }
    fun drain(max: Int): List<AgentNetworkEvent> = buildList { repeat(max.coerceAtMost(CAPACITY)) { events.poll()?.let(::add) ?: return@buildList } }
    fun dropped(): Long = dropped.get()
}

private class ProfilerEventListener(private val call: Call) : EventListener() {
    private val callId = Integer.toHexString(System.identityHashCode(call)) + "-" + System.nanoTime().toString(16)
    private fun event(kind: String, status: Int? = null, bytes: Long? = null, protocol: String? = null, connection: String? = null, message: String? = null, cipherSuite: String? = null) {
        val request = call.request()
        EventBuffer.add(AgentNetworkEvent(0, callId, kind, System.nanoTime(), request.method, redact(request), status, bytes, protocol, connection, message, cipherSuite))
    }
    override fun callStart(call: Call) = event("callStart")
    override fun callEnd(call: Call) = event("callEnd")
    override fun callFailed(call: Call, ioe: java.io.IOException) = event("callFailed", message = ioe.javaClass.simpleName + ": " + ioe.message)
    override fun canceled(call: Call) = event("canceled")
    override fun dnsStart(call: Call, domainName: String) = event("dnsStart", message = domainName)
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = event("dnsEnd", message = inetAddressList.size.toString())
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = event("connectStart", message = proxy.type().name)
    override fun secureConnectStart(call: Call) = event("secureConnectStart")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = event("secureConnectEnd", message = handshake?.tlsVersion?.javaName, cipherSuite = handshake?.cipherSuite?.javaName)
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = event("connectEnd", protocol = protocol?.toString())
    override fun connectionAcquired(call: Call, connection: Connection) = event("connectionAcquired", protocol = connection.protocol().toString(), connection = Integer.toHexString(System.identityHashCode(connection)))
    override fun connectionReleased(call: Call, connection: Connection) = event("connectionReleased", connection = Integer.toHexString(System.identityHashCode(connection)))
    override fun requestHeadersStart(call: Call) = event("requestHeadersStart")
    override fun requestHeadersEnd(call: Call, request: Request) = event("requestHeadersEnd")
    override fun requestBodyStart(call: Call) = event("requestBodyStart")
    override fun requestBodyEnd(call: Call, byteCount: Long) = event("requestBodyEnd", bytes = byteCount)
    override fun responseHeadersStart(call: Call) = event("responseHeadersStart")
    override fun responseHeadersEnd(call: Call, response: Response) = event("responseHeadersEnd", status = response.code, protocol = response.protocol.toString())
    override fun responseBodyStart(call: Call) = event("responseBodyStart")
    override fun responseBodyEnd(call: Call, byteCount: Long) = event("responseBodyEnd", bytes = byteCount)
    override fun cacheHit(call: Call, response: Response) = event("cacheHit", status = response.code)
    override fun cacheMiss(call: Call) = event("cacheMiss")
    private fun redact(request: Request): String = redactor.redact(request.url.toString())
    private val redactor = NetworkUrlRedactor.default()
}

private class CompositeEventListener(private val delegate: EventListener?, private val profiler: EventListener) : EventListener() {
    override fun callStart(call: Call) { delegate?.callStart(call); profiler.callStart(call) }
    override fun callEnd(call: Call) { delegate?.callEnd(call); profiler.callEnd(call) }
    override fun callFailed(call: Call, ioe: java.io.IOException) { delegate?.callFailed(call, ioe); profiler.callFailed(call, ioe) }
    override fun canceled(call: Call) { delegate?.canceled(call); profiler.canceled(call) }
    override fun dnsStart(call: Call, domainName: String) { delegate?.dnsStart(call, domainName); profiler.dnsStart(call, domainName) }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { delegate?.dnsEnd(call, domainName, inetAddressList); profiler.dnsEnd(call, domainName, inetAddressList) }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { delegate?.connectStart(call, inetSocketAddress, proxy); profiler.connectStart(call, inetSocketAddress, proxy) }
    override fun secureConnectStart(call: Call) { delegate?.secureConnectStart(call); profiler.secureConnectStart(call) }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { delegate?.secureConnectEnd(call, handshake); profiler.secureConnectEnd(call, handshake) }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { delegate?.connectEnd(call, inetSocketAddress, proxy, protocol); profiler.connectEnd(call, inetSocketAddress, proxy, protocol) }
    override fun connectionAcquired(call: Call, connection: Connection) { delegate?.connectionAcquired(call, connection); profiler.connectionAcquired(call, connection) }
    override fun connectionReleased(call: Call, connection: Connection) { delegate?.connectionReleased(call, connection); profiler.connectionReleased(call, connection) }
    override fun requestHeadersStart(call: Call) { delegate?.requestHeadersStart(call); profiler.requestHeadersStart(call) }
    override fun requestHeadersEnd(call: Call, request: Request) { delegate?.requestHeadersEnd(call, request); profiler.requestHeadersEnd(call, request) }
    override fun requestBodyStart(call: Call) { delegate?.requestBodyStart(call); profiler.requestBodyStart(call) }
    override fun requestBodyEnd(call: Call, byteCount: Long) { delegate?.requestBodyEnd(call, byteCount); profiler.requestBodyEnd(call, byteCount) }
    override fun responseHeadersStart(call: Call) { delegate?.responseHeadersStart(call); profiler.responseHeadersStart(call) }
    override fun responseHeadersEnd(call: Call, response: Response) { delegate?.responseHeadersEnd(call, response); profiler.responseHeadersEnd(call, response) }
    override fun responseBodyStart(call: Call) { delegate?.responseBodyStart(call); profiler.responseBodyStart(call) }
    override fun responseBodyEnd(call: Call, byteCount: Long) { delegate?.responseBodyEnd(call, byteCount); profiler.responseBodyEnd(call, byteCount) }
}

private object NetworkAgentServer {
    private val started = AtomicBoolean()
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val directory = File(context.filesDir, "aps-network").apply { mkdirs() }
        val tokenFile = File(directory, "token")
        val token =
            ByteArray(32)
                .also(java.security.SecureRandom()::nextBytes)
                .let { bytes -> Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
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
            NetworkAgentCodec.writeResponse(output, AgentResponse("ERROR", message = "Authentication or protocol mismatch")); return
        }
        NetworkAgentCodec.writeResponse(output, AgentResponse("READY", processId = android.os.Process.myPid()))
        while (true) {
            val command = NetworkAgentCodec.readCommand(input)
            val events = EventBuffer.drain(command.maxEvents)
            val type = if (command.type == "STOP") "STOPPED" else "EVENTS"
            NetworkAgentCodec.writeResponse(output, AgentResponse(type, processId = android.os.Process.myPid(), events = events, droppedEvents = EventBuffer.dropped()))
            if (command.type == "STOP") return
        }
    }
}
