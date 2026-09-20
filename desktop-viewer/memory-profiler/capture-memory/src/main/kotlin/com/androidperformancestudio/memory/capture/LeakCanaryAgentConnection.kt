@file:Suppress("TooGenericExceptionCaught")

package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.memory.leak.protocol.LEAK_CANARY_AGENT_PROTOCOL_VERSION
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentCodec
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentCommand
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentResponse
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentSessionCodec
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentSessionDescriptor
import com.androidperformancestudio.platform.adb.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

public class LeakCanaryAgentConnection(
    private val adbClient: AdbClient,
    private val serial: String,
    private val packageName: String,
) : AutoCloseable {
    private var descriptor: LeakCanaryAgentSessionDescriptor? = null
    private var localForward: String? = null

    public suspend fun open() {
        if (localForward != null) return
        val session = readDescriptor()
        require(session.protocolMajor == LEAK_CANARY_AGENT_PROTOCOL_VERSION) {
            "Unsupported LeakCanary Agent protocol ${session.protocolMajor}.${session.protocolMinor}."
        }
        val localPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val local = "tcp:$localPort"
        try {
            adbClient.forward(
                serial = serial,
                local = local,
                remote = "tcp:${session.socketPort}",
                timeout = 10.seconds,
                maxOutputBytesPerStream = MAX_OUTPUT_BYTES,
            )
            descriptor = session
            localForward = local
            request(
                LeakCanaryAgentCommand(
                    type = "PING",
                    token = session.token,
                ),
            )
        } catch (error: Exception) {
            runCatching { adbClient.removeForward(serial, local, 10.seconds, MAX_OUTPUT_BYTES) }
            descriptor = null
            localForward = null
            throw error
        }
    }

    public suspend fun startSession(): LeakCanaryAgentResponse =
        request(LeakCanaryAgentCommand(type = "START_SESSION", token = requireDescriptor().token))

    public suspend fun poll(
        cursor: Long,
        maxEvents: Int = 500,
    ): LeakCanaryAgentResponse =
        request(
            LeakCanaryAgentCommand(
                type = "POLL",
                token = requireDescriptor().token,
                cursor = cursor,
                maxEvents = maxEvents,
            ),
        )

    public suspend fun forceCheck(): LeakCanaryAgentResponse =
        request(LeakCanaryAgentCommand(type = "FORCE_CHECK", token = requireDescriptor().token))

    public suspend fun stopSession(): LeakCanaryAgentResponse =
        request(LeakCanaryAgentCommand(type = "STOP_SESSION", token = requireDescriptor().token))

    override fun close() {
        val local = localForward ?: return
        val token = descriptor?.token
        runCatching {
            if (token != null) {
                runBlocking { request(LeakCanaryAgentCommand(type = "STOP_SESSION", token = token)) }
            }
        }
        descriptor = null
        localForward = null
        runCatching { runBlocking { adbClient.removeForward(serial, local, 10.seconds, MAX_OUTPUT_BYTES) } }
    }

    private suspend fun readDescriptor(): LeakCanaryAgentSessionDescriptor {
        var lastError: Throwable? = null
        SESSION_PATHS.forEach { path ->
            try {
                val output =
                    adbClient
                        .shell(
                            serial = serial,
                            arguments = listOf("run-as", packageName, "cat", path),
                            timeout = 10.seconds,
                            maxOutputBytesPerStream = MAX_OUTPUT_BYTES,
                        ).stdout
                        .trim()
                if (output.isBlank() || output.startsWith("cat:")) {
                    error(output.ifBlank { "empty session descriptor" })
                }
                return LeakCanaryAgentSessionCodec.decode(output)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "LeakCanary Agent session descriptor was not found. " +
                "Rebuild and reinject the current bridge AAR; expected ${SESSION_PATHS.first()}.",
            lastError,
        )
    }

    private suspend fun request(command: LeakCanaryAgentCommand): LeakCanaryAgentResponse =
        withContext(Dispatchers.IO) {
            val local = checkNotNull(localForward) { "LeakCanary Agent forwarding is not open." }
            val port = local.substringAfter("tcp:").toInt()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), SOCKET_TIMEOUT_MILLIS)
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                LeakCanaryAgentCodec.writeCommand(socket.getOutputStream(), command)
                val response = LeakCanaryAgentCodec.readResponse(socket.getInputStream())
                if (response.type == "ERROR") {
                    error(response.message ?: "LeakCanary Agent request failed")
                }
                response
            }
        }

    private fun requireDescriptor(): LeakCanaryAgentSessionDescriptor =
        checkNotNull(descriptor) { "LeakCanary Agent connection is not open." }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val MAX_OUTPUT_BYTES = 64 * 1024
        val SESSION_PATHS =
            listOf(
                "files/aps-leakcanary/session.json",
                "files/app-leakcanary/session.json",
                "files/app-leakcanary/session.jaon",
                "files/aps-leakcanary/session.jaon",
            )
    }
}
