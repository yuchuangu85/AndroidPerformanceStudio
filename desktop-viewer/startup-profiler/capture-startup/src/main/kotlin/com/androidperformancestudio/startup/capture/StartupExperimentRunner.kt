@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "ThrowsCount",
)

package com.androidperformancestudio.startup.capture

import com.androidperformancestudio.startup.agent.protocol.AgentStartupEvent
import com.androidperformancestudio.startup.agent.protocol.AgentStartupResult
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupCompilationEvidence
import com.androidperformancestudio.startup.model.StartupEnvironmentEvidence
import com.androidperformancestudio.startup.model.StartupExperimentConfig
import com.androidperformancestudio.startup.model.StartupMetricEvidence
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupRunContext
import com.androidperformancestudio.startup.model.StartupSession
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.startup.model.StartupTraceEvidence
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.startup.parser.AmStartOutputParser
import com.androidperformancestudio.startup.parser.StartupEventLogParser
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

public data class StartupExperimentProgress(
    val completedRuns: Int,
    val totalRuns: Int,
    val stage: StartupExperimentProgressStage,
    val stageRun: Int = 0,
    val stageTotalRuns: Int = 0,
)

public enum class StartupExperimentProgressStage {
    WARM_UP,
    MEASURED_RUN,
    COMPLETE,
}

public data class StartupExperimentResult(
    val session: StartupSession,
    val runs: List<StartupRun>,
    val compilationOutput: String?,
    val warnings: List<String>,
    val compilationEvidence: StartupCompilationEvidence? = null,
)

internal interface StartupCommandRunner {
    suspend fun execute(arguments: List<String>): String

    suspend fun execute(
        arguments: List<String>,
        timeoutSeconds: Int,
    ): String = execute(arguments)

    suspend fun pull(
        remote: String,
        local: Path,
        timeoutSeconds: Int,
    ): Unit = throw StartupCaptureException("ADB pull is unavailable")
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
    private val traceDirectory: Path? = null,
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
        traceDirectory = Path.of(System.getProperty("user.home"), ".android-performance-studio", "startup-profiler", "traces"),
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
        val compilationBefore = readCompilationSnapshot(warnings)
        val compilationWarmups = if (config.compilationMode == CompilationMode.SPEED_PROFILE) config.warmupRuns else 0
        if (config.warmupRuns > 0 && compilationWarmups == 0) {
            warnings += "Compilation warm-ups are only used with speed-profile mode and were skipped."
        }
        if (compilationWarmups > 0) prepareCompilation(CompilationMode.RESET, warnings)
        repeat(compilationWarmups) { index ->
            onProgress(
                StartupExperimentProgress(
                    completedRuns = index,
                    totalRuns = compilationWarmups + config.measuredRuns,
                    stage = StartupExperimentProgressStage.WARM_UP,
                    stageRun = index + 1,
                    stageTotalRuns = compilationWarmups,
                ),
            )
            executeRun(
                session.id,
                -(index + 1),
                config.copy(
                    compilationMode = CompilationMode.CURRENT,
                    capturePerfettoTrace = false,
                    profileSource = StartupProfileSource.UNVERIFIED,
                ),
                null,
            )
        }
        if (compilationWarmups > 0) commandRunner.execute(listOf("am", "force-stop", target.packageName))
        val compilationOutput = prepareCompilation(config.compilationMode, warnings)
        val compilationAfter = readCompilationSnapshot(warnings)
        val compilationEvidence =
            compilationEvidence(config.compilationMode, config.profileSource, compilationBefore, compilationAfter, compilationOutput)
        compilationEvidence.failureReason?.let(warnings::add)
        val runs = mutableListOf<StartupRun>()
        repeat(config.measuredRuns) { index ->
            onProgress(
                StartupExperimentProgress(
                    completedRuns = compilationWarmups + index,
                    totalRuns = compilationWarmups + config.measuredRuns,
                    stage = StartupExperimentProgressStage.MEASURED_RUN,
                    stageRun = index + 1,
                    stageTotalRuns = config.measuredRuns,
                ),
            )
            runs += executeRun(session.id, index + 1, config, compilationEvidence)
            delay(RUN_SETTLE_DELAY_MILLIS)
        }
        onProgress(
            StartupExperimentProgress(
                completedRuns = compilationWarmups + config.measuredRuns,
                totalRuns = compilationWarmups + config.measuredRuns,
                stage = StartupExperimentProgressStage.COMPLETE,
            ),
        )
        return StartupExperimentResult(session, runs, compilationOutput, warnings, compilationEvidence)
    }

    private suspend fun executeRun(
        sessionId: String,
        iteration: Int,
        config: StartupExperimentConfig,
        compilationEvidence: StartupCompilationEvidence?,
    ): StartupRun {
        val runId = UUID.randomUUID().toString()
        val environment = readEnvironmentEvidence()
        prepareLaunchState(config.requestedType)
        val pidBefore = processId()
        val warnings = mutableListOf<String>()
        val eventLogBefore = loadRecentEventLog(warnings)
        var agentConnection: StartupAgentConnection? = null
        if (pidBefore != null && agentFactory != null) {
            agentConnection = connectAgent(warnings)
            agentConnection?.let { connection ->
                try {
                    connection.arm(runId)
                } catch (exception: CancellationException) {
                    connection.close()
                    throw exception
                } catch (exception: Exception) {
                    warnings += "Unable to arm Startup Agent: ${exception.message}"
                }
            }
        }
        val traceStart = startTrace(runId, config)
        val amOutput =
            try {
                commandRunner.execute(startCommand(runId, config.requestedType), config.timeoutSeconds)
            } catch (exception: Exception) {
                agentConnection?.close()
                stopTrace(traceStart, config.timeoutSeconds)
                throw exception
            }
        val parsed = amParser.parse(amOutput)
        warnings += parsed.warnings
        val pidAfter = processId()
        if (agentConnection == null && agentFactory != null) agentConnection = connectAgent(warnings)
        val agentResult =
            agentConnection?.let { connection ->
                try {
                    connection.result(runId)
                } catch (exception: CancellationException) {
                    connection.close()
                    throw exception
                } catch (exception: Exception) {
                    warnings += "Unable to read Startup Agent result: ${exception.message}"
                    null
                }
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
        environmentWarnings(environment).forEach(warnings::add)
        val traceEvidence = stopTrace(traceStart, config.timeoutSeconds)
        traceEvidence?.failureReason?.let { warnings += "Perfetto trace unavailable: $it" }
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
                    compilationOutput = compilationEvidence?.preparationOutput,
                    agentAvailable = agentResult != null,
                ),
            processIdBefore = pidBefore,
            processIdAfter = pidAfter,
            context = StartupRunContext(serial, target.packageName, target.componentName),
            ttidEvidence =
                if (platform.displayedTimeMs != null) {
                    StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT)
                } else {
                    StartupMetricEvidence(unavailableReason = "No displayed event was observed.")
                },
            ttfdEvidence =
                if (platform.fullyDrawnTimeMs != null) {
                    StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT)
                } else {
                    StartupMetricEvidence(unavailableReason = "The app did not call reportFullyDrawn() during this run.")
                },
            agentFirstFrameEvidence =
                if (milestones.any { it.kind == StartupMilestoneKind.FIRST_FRAME || it.kind == StartupMilestoneKind.FIRST_DRAW_CALLBACK }) {
                    StartupMetricEvidence(StartupSource.AGENT, EvidenceConfidence.EXACT)
                } else {
                    StartupMetricEvidence(unavailableReason = "Startup Agent first-frame evidence is unavailable.")
                },
            compilationEvidence = compilationEvidence,
            environmentEvidence = environment,
            traceEvidence = traceEvidence,
        )
    }

    private suspend fun readEnvironmentEvidence(): StartupEnvironmentEvidence {
        val failures = mutableListOf<String>()

        suspend fun read(
            label: String,
            command: List<String>,
        ): String? {
            val value =
                try {
                    commandRunner.execute(command).trim().takeIf(String::isNotEmpty)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    failures += "$label: ${exception.message}"
                    null
                }
            if (value == null && failures.none { it.startsWith("$label:") }) failures += "$label: no value returned"
            return value
        }
        val model = read("device model", listOf("getprop", "ro.product.model"))
        val api = read("API level", listOf("getprop", "ro.build.version.sdk"))?.toIntOrNull()
        if (api == null && failures.none { it.startsWith("API level:") }) failures += "API level: invalid value"
        val emulator =
            try {
                when (val qemu = commandRunner.execute(listOf("getprop", "ro.kernel.qemu")).trim()) {
                    "1" -> true
                    "", "0" -> false
                    else -> {
                        failures += "emulator state: invalid value '$qemu'"
                        null
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failures += "emulator state: ${exception.message}"
                null
            }
        val battery = read("battery state", listOf("dumpsys", "battery"))
        val thermal = read("thermal status", listOf("dumpsys", "thermalservice"))
        val batteryPercent = battery?.lineValue("level")?.toIntOrNull()
        val charging = battery?.let(::parseCharging)
        val thermalStatus = parseThermalStatus(thermal)
        if (batteryPercent == null && battery != null) failures += "battery level: unavailable"
        if (charging == null && battery != null) failures += "charging state: unavailable"
        if (thermalStatus == null && thermal != null) failures += "thermal status: unavailable"
        return StartupEnvironmentEvidence(
            deviceModel = model,
            apiLevel = api,
            emulator = emulator,
            batteryPercent = batteryPercent,
            charging = charging,
            thermalStatus = thermalStatus,
            capturedAt = Instant.now(),
            failures = failures,
        )
    }

    private fun environmentWarnings(evidence: StartupEnvironmentEvidence): List<String> =
        buildList {
            evidence.failures.forEach { add("Unable to read startup environment evidence: $it") }
            if (evidence.emulator == true) add("Startup measurements on an emulator are not comparable with physical-device runs.")
            val batteryPercent = evidence.batteryPercent
            if (batteryPercent != null && batteryPercent < LOW_BATTERY_PERCENT && evidence.charging != true) {
                add("Battery is below $LOW_BATTERY_PERCENT%; startup timing may be power constrained.")
            }
            val thermalStatus = evidence.thermalStatus
            if (thermalStatus != null && thermalStatus >= THERMAL_STATUS_SEVERE) {
                add("Platform thermal status is severe or higher; exclude this run from regression decisions.")
            }
        }

    private suspend fun readCompilationSnapshot(warnings: MutableList<String>): CompilationSnapshot? =
        try {
            parseCompilationSnapshot(commandRunner.execute(listOf("dumpsys", "package", target.packageName)))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            warnings += "Unable to read compiler/Profile state: ${exception.message}"
            null
        }

    private fun compilationEvidence(
        mode: CompilationMode,
        profileSource: StartupProfileSource,
        before: CompilationSnapshot?,
        after: CompilationSnapshot?,
        output: String?,
    ): StartupCompilationEvidence {
        val expected =
            when (mode) {
                CompilationMode.CURRENT -> after?.compilerFilter
                CompilationMode.RESET -> "verify"
                else -> mode.commandValue
            }
        val verified =
            after?.compilerFilter != null &&
                when (mode) {
                    CompilationMode.CURRENT -> true
                    CompilationMode.RESET -> after.compilerFilter in RESET_COMPILER_FILTERS
                    else -> after.compilerFilter == expected
                }
        return StartupCompilationEvidence(
            requestedMode = mode,
            compilerFilterBefore = before?.compilerFilter,
            compilerFilterAfter = after?.compilerFilter,
            profileStateBefore = before?.profileState,
            profileStateAfter = after?.profileState,
            preparationOutput = output,
            verified = verified,
            failureReason =
                when {
                    !verified -> "Requested ${mode.name.lowercase()} compilation mode could not be verified from dumpsys package output."
                    mode == CompilationMode.SPEED_PROFILE && profileSource == StartupProfileSource.UNVERIFIED ->
                        "speed-profile compiler filter is active, but its Profile artifact source is undeclared; A/B comparison is disabled."
                    else -> null
                },
            profileSource = profileSource,
            profileSourceDeclared =
                mode == CompilationMode.SPEED_PROFILE &&
                    verified &&
                    profileSource != StartupProfileSource.UNVERIFIED,
        )
    }

    private suspend fun startTrace(
        runId: String,
        config: StartupExperimentConfig,
    ): TraceStart? {
        if (!config.capturePerfettoTrace) return null
        val directory = traceDirectory ?: return TraceStart(failureReason = "No trace directory is configured.")
        return try {
            Files.createDirectories(directory)
            val remote = "/data/misc/perfetto-traces/aps-startup-$runId.perfetto-trace"
            val output =
                commandRunner.execute(
                    listOf(
                        "perfetto",
                        "--background-wait",
                        "-o",
                        remote,
                        "-t",
                        "${config.timeoutSeconds + TRACE_GRACE_SECONDS}s",
                        "sched",
                        "freq",
                        "idle",
                        "am",
                        "wm",
                        "gfx",
                        "view",
                        "binder_driver",
                        "hal",
                        "dalvik",
                    ),
                    config.timeoutSeconds,
                )
            val pid =
                Regex("\\d+").find(output)?.value?.toIntOrNull()
                    ?: return TraceStart(failureReason = "Perfetto did not return a background process id.")
            TraceStart(remote, directory.resolve("$runId.perfetto-trace"), pid)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            TraceStart(failureReason = exception.message ?: "Unable to start Perfetto.")
        }
    }

    private suspend fun stopTrace(
        trace: TraceStart?,
        timeoutSeconds: Int,
    ): StartupTraceEvidence? {
        if (trace == null) return null
        if (trace.failureReason != null) return StartupTraceEvidence(failureReason = trace.failureReason)
        return try {
            val endedBeforeStop =
                try {
                    commandRunner.execute(listOf("kill", "-TERM", requireNotNull(trace.pid).toString()))
                    false
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    true
                }
            delay(TRACE_FLUSH_DELAY_MILLIS)
            commandRunner.pull(requireNotNull(trace.remote), requireNotNull(trace.local), timeoutSeconds)
            runCatching { commandRunner.execute(listOf("rm", "-f", trace.remote)) }
            val size = Files.size(trace.local)
            if (size == 0L) {
                StartupTraceEvidence(file = trace.local.toString(), failureReason = "Trace file is empty.")
            } else {
                StartupTraceEvidence(file = trace.local.toString(), captured = true, truncated = endedBeforeStop)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            StartupTraceEvidence(file = trace.local?.toString(), failureReason = exception.message ?: "Unable to save Perfetto trace.")
        }
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
        try {
            commandRunner
                .execute(listOf("pidof", target.packageName))
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.toIntOrNull()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

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
        const val RUN_ID_EXTRA = "com.androidperformancestudio.startup.RUN_ID"
        const val EVENT_LOG_LINES = 500
        const val EVENT_LOG_POLL_ATTEMPTS = 4
        const val EVENT_LOG_POLL_DELAY_MILLIS = 500L
        const val AGENT_CONNECT_ATTEMPTS = 5
        const val AGENT_CONNECT_DELAY_MILLIS = 200L
        const val PREPARE_DELAY_MILLIS = 300L
        const val RUN_SETTLE_DELAY_MILLIS = 500L
        const val TRACE_FLUSH_DELAY_MILLIS = 500L
        const val TRACE_GRACE_SECONDS = 5
        const val LOW_BATTERY_PERCENT = 15
        const val THERMAL_STATUS_SEVERE = 3
        val RESET_COMPILER_FILTERS = setOf("verify", "run-from-apk", "run-from-apk-fallback")
    }
}

internal data class CompilationSnapshot(
    val compilerFilter: String?,
    val profileState: String?,
)

internal fun parseCompilationSnapshot(output: String): CompilationSnapshot? {
    val compilerFilter =
        Regex("\\[status=([^]]+)]").find(output)?.groupValues?.get(1)
            ?: Regex("compilerFilter[=:]\\s*([^,\\s]+)", RegexOption.IGNORE_CASE).find(output)?.groupValues?.get(1)
    val profileState = Regex("\\[reason=([^]]+)]").find(output)?.groupValues?.get(1)
    return if (compilerFilter == null && profileState == null) null else CompilationSnapshot(compilerFilter, profileState)
}

internal fun parseThermalStatus(output: String?): Int? =
    output?.let {
        Regex("(?:Thermal Status|mStatus|Status)\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(it)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

private fun String.lineValue(name: String): String? =
    lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()

private fun parseCharging(output: String): Boolean? {
    output.lineValue("status")?.toIntOrNull()?.let { return it == 2 || it == 5 }
    val plugged =
        listOf("AC powered", "USB powered", "Wireless powered", "Dock powered")
            .mapNotNull { output.lineValue(it)?.toBooleanStrictOrNull() }
    return plugged.takeIf(List<Boolean>::isNotEmpty)?.any { it }
}

private data class TraceStart(
    val remote: String? = null,
    val local: Path? = null,
    val pid: Int? = null,
    val failureReason: String? = null,
)

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

    override suspend fun pull(
        remote: String,
        local: Path,
        timeoutSeconds: Int,
    ) {
        val result =
            processRunner.run(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial, "pull", remote, local.toString()),
                    timeout = timeoutSeconds.seconds,
                    maxCapturedCharactersPerStream = MAX_OUTPUT,
                ),
            )
        if (result is ProcessRunResult.Failed) {
            throw StartupCaptureException(
                result.output
                    ?.stderr
                    ?.text
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { result.error.message },
            )
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
