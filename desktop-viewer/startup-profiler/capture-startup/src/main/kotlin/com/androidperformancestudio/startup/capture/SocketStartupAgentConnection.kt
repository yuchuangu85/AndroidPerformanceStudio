@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")

package com.androidperformancestudio.startup.capture

import com.androidperformancestudio.startup.agent.protocol.AgentSessionDescriptor
import com.androidperformancestudio.startup.agent.protocol.AgentStartupResult
import com.androidperformancestudio.startup.agent.protocol.AgentStartupResultCodec
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal class SocketStartupAgentConnection(
    private val adbExecutable: Path,
    private val serial: String,
    private val packageName: String,
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
    private val codec: AgentStartupResultCodec = AgentStartupResultCodec(),
) : StartupAgentConnection {
    private var descriptor: AgentSessionDescriptor? = null
    private var forwardedPort: Int? = null

    override suspend fun open() {
        val session =
            AgentSessionDescriptor.parse(
                executeAdb(listOf("-s", serial, "shell", "run-as", packageName, "cat", "files/agentperf/session.json")),
            )
        if (session.protocolMajor != SUPPORTED_PROTOCOL_MAJOR) {
            throw StartupCaptureException("Unsupported Agent protocol ${session.protocolMajor}.${session.protocolMinor}.")
        }
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        try {
            executeAdb(listOf("-s", serial, "forward", "tcp:$port", "localabstract:${session.socketName}"))
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
        executeAdb(listOf("-s", serial, "forward", "--remove", "tcp:$port"))
    }

    private suspend fun executeAdb(arguments: List<String>): String {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = arguments,
                timeout = COMMAND_TIMEOUT,
                maxCapturedCharactersPerStream = MAX_OUTPUT,
            )
        return when (val result = processRunner.run(request)) {
            is ProcessRunResult.Completed -> result.output.stdout.text
            is ProcessRunResult.Failed -> {
                val detail =
                    result.output
                        ?.stderr
                        ?.text
                        ?.trim()
                        .orEmpty()
                        .ifEmpty { result.error.message }
                throw StartupCaptureException(detail)
            }
        }
    }

    private companion object {
        const val SUPPORTED_PROTOCOL_MAJOR = 1
        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val MAX_OUTPUT = 64 * 1024
        val COMMAND_TIMEOUT = 10.seconds
    }
}
