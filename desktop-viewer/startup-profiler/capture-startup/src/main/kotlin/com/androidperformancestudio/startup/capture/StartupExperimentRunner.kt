@file:Suppress("LongMethod", "MaxLineLength", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.androidperformancestudio.startup.capture

import com.androidperformancestudio.startup.agent.protocol.AgentStartupEvent
import com.androidperformancestudio.startup.agent.protocol.AgentStartupResult
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupExperimentConfig
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSession
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.startup.parser.AmStartOutputParser
import com.androidperformancestudio.startup.parser.StartupEventLogParser
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

public data class StartupExperimentProgress(
    val completedRuns: Int,
    val totalRuns: Int,
    val message: String,
)

public data class StartupExperimentResult(
    val session: StartupSession,
    val runs: List<StartupRun>,
    val compilationOutput: String?,
    val warnings: List<String>,
)

internal interface StartupCommandRunner {
    suspend fun execute(arguments: List<String>): String

    suspend fun execute(
        arguments: List<String>,
        timeoutSeconds: Int,
    ): String = execute(arguments)
}

internal fun interface StartupAgentClientFactory {
    fun create(): StartupAgentConnection
}

internal interface StartupAgentConnection : AutoCloseable {
    suspend fun open()

    suspend fun arm(runId: String): AgentStartupResult

    suspend fun result(runId: String): AgentStartupResult

    override fun close()
}

public class StartupExperimentRunner internal constructor(
    private val serial: String,
    private val target: StartupTarget,
    private val commandRunner: StartupCommandRunner,
    private val agentFactory: StartupAgentClientFactory?,
    private val amParser: AmStartOutputParser = AmStartOutputParser(),
    private val eventParser: StartupEventLogParser = StartupEventLogParser(),
) {
    public constructor(
        adbExecutable: Path,
        serial: String,
        target: StartupTarget,
    ) : this(
        serial = serial,
        target = target,
        commandRunner = JvmStartupCommandRunner(adbExecutable, serial),
        agentFactory =
            if (target.debuggable) {
                StartupAgentClientFactory {
                    SocketStartupAgentConnection(adbExecutable, serial, target.packageName)
                }
            } else {
                null
            },
    )

    public suspend fun run(
        config: StartupExperimentConfig,
        onProgress: (StartupExperimentProgress) -> Unit = {},
    ): StartupExperimentResult {
        val session =
            StartupSession(
                id = UUID.randomUUID().toString(),
                deviceSerial = serial,
                packageName = target.packageName,
                componentName = target.componentName,
                requestedType = config.requestedType,
                compilationMode = config.compilationMode,
                warmupRuns = config.warmupRuns,
                measuredRuns = config.measuredRuns,
                createdAt = Instant.now(),
            )
        val warnings = mutableListOf<String>()
        val compilationOutput = prepareCompilation(config.compilationMode, warnings)
        repeat(config.warmupRuns) { index ->
            onProgress(
                StartupExperimentProgress(index, config.warmupRuns + config.measuredRuns, "Warm-up ${index + 1}/${config.warmupRuns}"),
            )
            executeRun(session.id, -(index + 1), config, compilationOutput)
        }
        val runs = mutableListOf<StartupRun>()
        repeat(config.measuredRuns) { index ->
            onProgress(
                StartupExperimentProgress(
                    completedRuns = config.warmupRuns + index,
                    totalRuns = config.warmupRuns + config.measuredRuns,
                    message = "Measured run ${index + 1}/${config.measuredRuns}",
                ),
            )
            runs += executeRun(session.id, index + 1, config, compilationOutput)
            delay(RUN_SETTLE_DELAY_MILLIS)
        }
        onProgress(StartupExperimentProgress(config.warmupRuns + config.measuredRuns, config.warmupRuns + config.measuredRuns, "Complete"))
        return StartupExperimentResult(session, runs, compilationOutput, warnings)
    }

    private suspend fun executeRun(
        sessionId: String,
        iteration: Int,
        config: StartupExperimentConfig,
        compilationOutput: String?,
    ): StartupRun {
        val runId = UUID.randomUUID().toString()
        prepareLaunchState(config.requestedType)
        val pidBefore = processId()
        val warnings = mutableListOf<String>()
        val eventLogBefore = loadRecentEventLog(warnings)
        var agentConnection: StartupAgentConnection? = null
        if (pidBefore != null && agentFactory != null) {
            agentConnection = connectAgent(warnings)
            agentConnection?.let { connection ->
                runCatching { connection.arm(runId) }.onFailure {
                    warnings +=
                        "Unable to arm Startup Agent: ${it.message}"
                }
            }
        }
        val amOutput = commandRunner.execute(startCommand(runId, config.requestedType), config.timeoutSeconds)
        val parsed = amParser.parse(amOutput)
        warnings += parsed.warnings
        val pidAfter = processId()
        if (agentConnection == null && agentFactory != null) agentConnection = connectAgent(warnings)
        val agentResult =
            agentConnection?.let { connection ->
                runCatching { connection.result(runId) }
                    .onFailure { warnings += "Unable to read Startup Agent result: ${it.message}" }
                    .getOrNull()
            }
        agentConnection?.close()
        val eventOutput = loadStartupEvents(eventLogBefore, warnings)
        val eventMetrics = eventParser.parse(eventOutput.orEmpty(), target.packageName)
        val platform =
            parsed.metrics.copy(
                displayedTimeMs = eventMetrics.displayedTimeMs,
                fullyDrawnTimeMs = eventMetrics.fullyDrawnTimeMs,
            )
        val milestones = agentResult?.events.orEmpty().map(AgentStartupEvent::toModel)
        val observedType = classify(parsed.metrics, pidBefore, pidAfter, milestones)
        if (observedType != config.requestedType && observedType != StartupType.UNKNOWN) {
            warnings += "Requested ${config.requestedType.name.lowercase()} startup but observed ${observedType.name.lowercase()}."
        }
        if (agentResult?.droppedEvents?.let { it > 0L } == true) warnings += agentResult.warnings
        if (platform.fullyDrawnTimeMs == null) warnings += "The app did not report a Fully Drawn event during this run."
        return StartupRun(
            id = runId,
            sessionId = sessionId,
            iteration = iteration,
            requestedType = config.requestedType,
            observedType = observedType,
            platform = platform,
            milestones = milestones,
            warnings = warnings.distinct(),
            rawEvidence =
                StartupRawEvidence(
                    amStartOutput = amOutput,
                    eventLogOutput = eventOutput,
                    compilationOutput = compilationOutput,
                    agentAvailable = agentResult != null,
                ),
            processIdBefore = pidBefore,
            processIdAfter = pidAfter,
        )
    }

    private suspend fun prepareCompilation(
        mode: CompilationMode,
        warnings: MutableList<String>,
    ): String? {
        if (mode == CompilationMode.CURRENT) return null
        return try {
            val args =
                if (mode == CompilationMode.RESET) {
                    listOf("cmd", "package", "compile", "--reset", target.packageName)
                } else {
                    listOf("cmd", "package", "compile", "-m", requireNotNull(mode.commandValue), "-f", target.packageName)
                }
            commandRunner.execute(args)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            warnings += "Unable to prepare ${mode.name.lowercase()} compilation mode: ${exception.message}"
            null
        }
    }

    private suspend fun prepareLaunchState(type: StartupType) {
        when (type) {
            StartupType.COLD -> commandRunner.execute(listOf("am", "force-stop", target.packageName))
            StartupType.HOT -> {
                if (processId() == null) commandRunner.execute(startCommand(UUID.randomUUID().toString(), StartupType.HOT, wait = false))
                commandRunner.execute(listOf("input", "keyevent", "HOME"))
            }
            StartupType.WARM -> {
                if (processId() == null) commandRunner.execute(startCommand(UUID.randomUUID().toString(), StartupType.HOT, wait = false))
                commandRunner.execute(listOf("input", "keyevent", "HOME"))
            }
            StartupType.UNKNOWN -> Unit
        }
        delay(PREPARE_DELAY_MILLIS)
    }

    private fun startCommand(
        runId: String,
        type: StartupType,
        wait: Boolean = true,
    ): List<String> =
        buildList {
            add("am")
            add("start")
            if (wait) add("-W")
            addAll(listOf("-n", target.componentName, "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"))
            if (type == StartupType.WARM) addAll(listOf("--activity-clear-task", "--activity-new-task"))
            addAll(listOf("--es", RUN_ID_EXTRA, runId))
        }

    private suspend fun processId(): Int? =
        runCatching {
            commandRunner
                .execute(listOf("pidof", target.packageName))
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.toIntOrNull()
        }.getOrNull()

    private suspend fun loadRecentEventLog(warnings: MutableList<String>): String? =
        try {
            commandRunner.execute(listOf("logcat", "-d", "-b", "events", "-v", "epoch", "-t", EVENT_LOG_LINES.toString()))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            warnings += "Unable to read startup event log: ${exception.message}"
            null
        }

    private suspend fun loadStartupEvents(
        before: String?,
        warnings: MutableList<String>,
    ): String? {
        if (before == null) return null
        var captured: String? = null
        repeat(EVENT_LOG_POLL_ATTEMPTS) {
            delay(EVENT_LOG_POLL_DELAY_MILLIS)
            val after = loadRecentEventLog(warnings) ?: return@repeat
            captured = eventLogDelta(before, after)
            if (eventParser.parse(captured.orEmpty(), target.packageName).fullyDrawnTimeMs != null) return captured
        }
        return captured
    }

    private fun eventLogDelta(
        before: String?,
        after: String,
    ): String {
        val previousLines = before.orEmpty().lineSequence().toHashSet()
        return after.lineSequence().filterNot(previousLines::contains).joinToString("\n")
    }

    private suspend fun connectAgent(warnings: MutableList<String>): StartupAgentConnection? {
        val factory = agentFactory ?: return null
        repeat(AGENT_CONNECT_ATTEMPTS) { attempt ->
            val connection = factory.create()
            try {
                connection.open()
                return connection
            } catch (exception: CancellationException) {
                connection.close()
                throw exception
            } catch (exception: Exception) {
                connection.close()
                if (attempt ==
                    AGENT_CONNECT_ATTEMPTS - 1
                ) {
                    warnings += "Startup Agent is unavailable; using platform timing only: ${exception.message}"
                }
                delay(AGENT_CONNECT_DELAY_MILLIS)
            }
        }
        return null
    }

    private fun classify(
        platform: PlatformLaunchMetrics,
        pidBefore: Int?,
        pidAfter: Int?,
        milestones: List<StartupMilestone>,
    ): StartupType {
        platform.launchState?.uppercase()?.let { value -> StartupType.entries.firstOrNull { it.name == value }?.let { return it } }
        if (pidBefore == null || pidAfter != pidBefore) return StartupType.COLD
        val activityCreated = milestones.any { it.kind == StartupMilestoneKind.ACTIVITY_CREATED }
        return if (activityCreated) StartupType.WARM else StartupType.HOT
    }

    private companion object {
        const val RUN_ID_EXTRA = "dev.agentperf.startup.RUN_ID"
        const val EVENT_LOG_LINES = 500
        const val EVENT_LOG_POLL_ATTEMPTS = 4
        const val EVENT_LOG_POLL_DELAY_MILLIS = 500L
        const val AGENT_CONNECT_ATTEMPTS = 5
        const val AGENT_CONNECT_DELAY_MILLIS = 200L
        const val PREPARE_DELAY_MILLIS = 300L
        const val RUN_SETTLE_DELAY_MILLIS = 500L
    }
}

private class JvmStartupCommandRunner(
    private val adbExecutable: Path,
    private val serial: String,
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
) : StartupCommandRunner {
    override suspend fun execute(arguments: List<String>): String = execute(arguments, DEFAULT_TIMEOUT_SECONDS)

    override suspend fun execute(
        arguments: List<String>,
        timeoutSeconds: Int,
    ): String {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial, "shell") + arguments,
                timeout = timeoutSeconds.seconds,
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
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MAX_OUTPUT = 4 * 1024 * 1024
    }
}

public class StartupCaptureException(
    message: String,
) : IllegalStateException(message)

private fun AgentStartupEvent.toModel(): StartupMilestone =
    StartupMilestone(
        kind = StartupMilestoneKind.valueOf(kind.name),
        elapsedRealtimeNs = elapsedRealtimeNs,
        source = StartupSource.AGENT,
        confidence = EvidenceConfidence.valueOf(confidence.name),
        activityName = activityName,
        processId = processId,
        processName = processName,
    )
