@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")

package com.androidperformancestudio.frame.capture

import com.androidperformancestudio.frame.agent.protocol.AgentExpectedDurationSource
import com.androidperformancestudio.frame.agent.protocol.AgentFrameBatch
import com.androidperformancestudio.frame.agent.protocol.AgentFrameBatchCodec
import com.androidperformancestudio.frame.agent.protocol.AgentFrameSample
import com.androidperformancestudio.frame.agent.protocol.AgentSessionDescriptor
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameStages
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

public class FrameMetricsAgentCaptureException(
    message: String,
) : IllegalStateException(message)

internal interface AgentFrameTransport {
    suspend fun start(): AgentFrameBatch

    suspend fun fetch(afterCursor: Long): AgentFrameBatch

    suspend fun stop(): List<String>
}

public class FrameMetricsAgentCaptureSession internal constructor(
    private val target: GfxInfoCaptureTarget,
    private val sessionId: String,
    private val transport: AgentFrameTransport,
) {
    public constructor(
        adbExecutable: Path,
        target: GfxInfoCaptureTarget,
        sessionId: String,
    ) : this(
        target = target,
        sessionId = sessionId,
        transport = SocketAgentFrameTransport(adbExecutable, target),
    )

    private var cursor = -1L
    private var nextFrameId = 0L

    public suspend fun start(): List<String> {
        nextFrameId = 0L
        val initial = transport.start()
        cursor = initial.cursor
        return initial.warnings
    }

    public suspend fun poll(): GfxInfoPollBatch {
        val batch = transport.fetch(cursor)
        cursor = batch.cursor
        val frames =
            batch.frames.mapIndexed { index, frame ->
                frame.toFrameSample(
                    frameId = nextFrameId++,
                    sessionId = sessionId,
                    processId = target.processId,
                    droppedBeforeSample = if (index == 0) batch.droppedFrames else 0L,
                )
            }
        val dropWarning =
            batch.droppedFrames
                .takeIf { it > 0L }
                ?.let { "$it FrameMetrics reports were overwritten before the desktop viewer received them." }
        return GfxInfoPollBatch(frames = frames, warnings = batch.warnings + listOfNotNull(dropWarning))
    }

    public suspend fun stop(): List<String> = transport.stop()
}

private class SocketAgentFrameTransport(
    private val adbExecutable: Path,
    private val target: GfxInfoCaptureTarget,
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
    private val codec: AgentFrameBatchCodec = AgentFrameBatchCodec(),
) : AgentFrameTransport {
    private var descriptor: AgentSessionDescriptor? = null
    private var forwardedPort: Int? = null

    override suspend fun start(): AgentFrameBatch {
        val session =
            AgentSessionDescriptor.parse(
                executeAdb(
                    listOf(
                        "-s",
                        target.serial,
                        "shell",
                        "run-as",
                        target.packageName,
                        "cat",
                        "files/agentperf/session.json",
                    ),
                ),
            )
        if (session.protocolMajor != SUPPORTED_PROTOCOL_MAJOR) {
            throw FrameMetricsAgentCaptureException(
                "Unsupported Agent protocol ${session.protocolMajor}.${session.protocolMinor}.",
            )
        }
        val port = availableLoopbackPort()
        try {
            executeAdb(
                listOf(
                    "-s",
                    target.serial,
                    "forward",
                    "tcp:$port",
                    "localabstract:${session.socketName}",
                ),
            )
            descriptor = session
            forwardedPort = port
            return request("FRAME_CURSOR ${session.token}")
        } catch (error: Exception) {
            runCatching { removeForward(port) }
            descriptor = null
            forwardedPort = null
            throw error
        }
    }

    override suspend fun fetch(afterCursor: Long): AgentFrameBatch {
        val session = descriptor ?: throw FrameMetricsAgentCaptureException("FrameMetrics Agent capture is not started.")
        return request("FRAMES ${session.token} $afterCursor")
    }

    override suspend fun stop(): List<String> {
        val port = forwardedPort ?: return emptyList()
        descriptor = null
        forwardedPort = null
        return runCatching { removeForward(port) }
            .exceptionOrNull()
            ?.let { listOf("Unable to remove ADB frame forwarding on tcp:$port: ${it.message}") }
            .orEmpty()
    }

    private suspend fun request(command: String): AgentFrameBatch =
        withContext(Dispatchers.IO) {
            val port = forwardedPort ?: throw FrameMetricsAgentCaptureException("ADB frame forwarding is not configured.")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), SOCKET_TIMEOUT_MILLIS)
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                socket.getOutputStream().write("$command\n".toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
                codec.read(socket.getInputStream())
            }
        }

    private suspend fun removeForward(port: Int) {
        executeAdb(listOf("-s", target.serial, "forward", "--remove", "tcp:$port"))
    }

    private suspend fun executeAdb(arguments: List<String>): String {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = arguments,
                timeout = COMMAND_TIMEOUT,
                maxCapturedCharactersPerStream = MAX_COMMAND_OUTPUT,
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
                throw FrameMetricsAgentCaptureException(detail)
            }
        }
    }

    private fun availableLoopbackPort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    private companion object {
        const val SUPPORTED_PROTOCOL_MAJOR = 1
        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val MAX_COMMAND_OUTPUT = 64 * 1024
        val COMMAND_TIMEOUT = 10.seconds
    }
}

private fun AgentFrameSample.toFrameSample(
    frameId: Long,
    sessionId: String,
    processId: Int,
    droppedBeforeSample: Long,
): FrameSample =
    FrameSample(
        frameId = frameId,
        sessionId = sessionId,
        source = FrameSource.FRAME_METRICS,
        packageName = packageName,
        processId = processId,
        activityName = activityName,
        windowId = windowId,
        intendedVsyncNs = intendedVsyncNs,
        actualVsyncNs = actualVsyncNs,
        frameCompletedNs = frameCompletedNs,
        expectedDurationNs = expectedDurationNs,
        expectedDurationSource = expectedDurationSource.toModel(),
        refreshRateHz = refreshRateHz,
        frameTimelineVsyncId = frameTimelineVsyncId,
        totalDurationNs = totalDurationNs,
        stages =
            FrameStages(
                inputNs = stages.inputNs,
                animationNs = stages.animationNs,
                layoutMeasureNs = stages.layoutMeasureNs,
                drawNs = stages.drawNs,
                syncNs = stages.syncNs,
                commandIssueNs = stages.commandIssueNs,
                swapBuffersNs = stages.swapBuffersNs,
                gpuNs = stages.gpuNs,
            ),
        platformJank = platformJank,
        states = states,
        eligibleForJank = eligibleForJank,
        droppedBeforeSample = droppedBeforeSample,
    )

private fun AgentExpectedDurationSource.toModel(): ExpectedDurationSource =
    when (this) {
        AgentExpectedDurationSource.PLATFORM_DEADLINE -> ExpectedDurationSource.PLATFORM_DEADLINE
        AgentExpectedDurationSource.REFRESH_RATE -> ExpectedDurationSource.REFRESH_RATE
        AgentExpectedDurationSource.UNKNOWN -> ExpectedDurationSource.UNKNOWN
    }
