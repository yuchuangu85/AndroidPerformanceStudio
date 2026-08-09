@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")

package com.androidperformancestudio.startup.capture

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.startup.agent.protocol.AgentSessionDescriptor
import com.androidperformancestudio.startup.agent.protocol.AgentStartupResult
import com.androidperformancestudio.startup.agent.protocol.AgentStartupResultCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.seconds

internal class SocketStartupAgentConnection(
    private val adbClient: AdbClient,
    private val serial: String,
    private val packageName: String,
    private val codec: AgentStartupResultCodec = AgentStartupResultCodec(),
) : StartupAgentConnection {
    private var descriptor: AgentSessionDescriptor? = null
    private var forwardedPort: Int? = null

    override suspend fun open() {
        val session =
            AgentSessionDescriptor.parse(
                executeShell(listOf("run-as", packageName, "cat", "files/agentperf/session.json")),
            )
        if (session.protocolMajor != SUPPORTED_PROTOCOL_MAJOR) {
            throw StartupCaptureException("Unsupported Agent protocol ${session.protocolMajor}.${session.protocolMinor}.")
        }
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        try {
            adbClient.forward(
                serial,
                "tcp:$port",
                "localabstract:${session.socketName}",
                COMMAND_TIMEOUT,
                MAX_OUTPUT,
            )
            descriptor = session
            forwardedPort = port
            request("STARTUP_CAPABILITIES ${session.token}")
        } catch (error: Exception) {
            runCatching { removeForward(port) }
            descriptor = null
            forwardedPort = null
            throw error
        }
    }

    override suspend fun arm(runId: String): AgentStartupResult {
        val session = descriptor ?: throw StartupCaptureException("Startup Agent connection is not open.")
        return request("STARTUP_ARM ${session.token} $runId")
    }

    override suspend fun result(runId: String): AgentStartupResult {
        val session = descriptor ?: throw StartupCaptureException("Startup Agent connection is not open.")
        return request("STARTUP_RESULT ${session.token} $runId")
    }

    override fun close() {
        val port = forwardedPort ?: return
        descriptor = null
        forwardedPort = null
        runCatching { runBlocking { removeForward(port) } }
    }

    private suspend fun request(command: String): AgentStartupResult =
        withContext(Dispatchers.IO) {
            val port = forwardedPort ?: throw StartupCaptureException("ADB startup forwarding is not configured.")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), SOCKET_TIMEOUT_MILLIS)
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                socket.getOutputStream().write("$command\n".toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
                codec.read(socket.getInputStream())
            }
        }

    private suspend fun removeForward(port: Int) {
        adbClient.removeForward(serial, "tcp:$port", COMMAND_TIMEOUT, MAX_OUTPUT)
    }

    private suspend fun executeShell(arguments: List<String>): String =
        try {
            adbClient.shell(serial, arguments, COMMAND_TIMEOUT, MAX_OUTPUT).stdout
        } catch (error: RuntimeException) {
            throw StartupCaptureException(error.message ?: "ADB command failed").also { it.initCause(error) }
        }

    private companion object {
        const val SUPPORTED_PROTOCOL_MAJOR = 1
        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val MAX_OUTPUT = 64 * 1024
        val COMMAND_TIMEOUT = 10.seconds
    }
}
