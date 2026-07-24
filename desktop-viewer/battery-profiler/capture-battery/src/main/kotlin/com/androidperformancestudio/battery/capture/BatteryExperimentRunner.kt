@file:Suppress(
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:max-line-length",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.androidperformancestudio.battery.capture

import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryCapabilities
import com.androidperformancestudio.battery.model.BatteryCapabilityLevel
import com.androidperformancestudio.battery.model.BatteryEnvironment
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryExperimentResult
import com.androidperformancestudio.battery.model.BatteryRawEvidence
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.BatteryTarget
import com.androidperformancestudio.battery.parser.BatteryStatsParser
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

public fun interface BatteryCommandRunner {
    public suspend fun execute(arguments: List<String>): String
}

public data class BatteryCaptureProgress(
    val completedSteps: Int,
    val totalSteps: Int,
    val message: String,
)

public class ActiveBatteryExperiment internal constructor(
    public val session: BatterySession,
    internal val runId: String,
    internal val iteration: Int,
    internal val baseline: BatterySnapshot,
    internal val samples: MutableList<BatterySnapshot>,
    internal var nextSequence: Int,
)

public class BatteryExperimentRunner(
    adbExecutable: Path,
    private val serial: String,
    private val target: BatteryTarget,
    private val commandRunner: BatteryCommandRunner = JvmBatteryCommandRunner(adbExecutable, serial),
    private val parser: BatteryStatsParser = BatteryStatsParser(),
) {
    public suspend fun start(config: BatteryExperimentConfig): ActiveBatteryExperiment {
        val session = prepareSession(config)
        return beginRun(session, 1)
    }

    public suspend fun poll(active: ActiveBatteryExperiment): BatterySnapshot {
        val snapshot = captureSnapshot(active.session, active.nextSequence++, includeHistory = false)
        active.samples += snapshot
        return snapshot
    }

    public suspend fun stop(active: ActiveBatteryExperiment): BatteryExperimentResult {
        val final = captureSnapshot(active.session, active.nextSequence++, includeHistory = true)
        return BatteryExperimentResult(
            active.session,
            listOf(BatteryRun(active.runId, active.session.id, active.iteration, active.baseline, active.samples.toList(), final)),
        )
    }

    public suspend fun run(
        config: BatteryExperimentConfig,
        onProgress: (BatteryCaptureProgress) -> Unit = {},
    ): BatteryExperimentResult {
        val session = prepareSession(config)
        val totalSteps = config.measuredRuns * 2
        val runs = mutableListOf<BatteryRun>()
        repeat(config.measuredRuns) { index ->
            onProgress(BatteryCaptureProgress(index * 2, totalSteps, "Capturing baseline for run ${index + 1}…"))
            val active = beginRun(session, index + 1)
            if (config.launchApp) launchTarget()
            val deadline = System.nanoTime() + config.durationSeconds * NANOS_PER_SECOND
            while (System.nanoTime() < deadline) {
                val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND
                if (remainingMs <= 0) break
                val delayMs = minOf(config.pollingIntervalSeconds * MILLIS_PER_SECOND, remainingMs)
                delay(delayMs)
                if (config.mode.name == "ONLINE" && System.nanoTime() < deadline) poll(active)
            }
            onProgress(BatteryCaptureProgress(index * 2 + 1, totalSteps, "Capturing final snapshot for run ${index + 1}…"))
            val final = captureSnapshot(session, active.nextSequence++, includeHistory = true)
            runs += BatteryRun(active.runId, session.id, index + 1, active.baseline, active.samples.toList(), final)
            onProgress(BatteryCaptureProgress(index * 2 + 2, totalSteps, "Completed run ${index + 1}."))
        }
        return BatteryExperimentResult(session, runs)
    }

    private suspend fun prepareSession(config: BatteryExperimentConfig): BatterySession {
        val help = optionalCommand(listOf("dumpsys", "batterystats", "--help"))
        val bootId = optionalCommand(listOf("cat", "/proc/sys/kernel/random/boot_id"))?.trim()?.takeIf(String::isNotEmpty)
        val batteryText = commandRunner.execute(listOf("dumpsys", "battery"))
        val report = optionalCommand(listOf("dumpsys", "batterystats")).orEmpty()
        val capabilities = detectCapabilities(help.orEmpty(), report)
        val state = parser.parseDeviceState(batteryText)
        val scope = if (target.sharedUid) AttributionScope.SHARED_UID else AttributionScope.UID
        return BatterySession(
            id = UUID.randomUUID().toString(),
            deviceSerial = serial,
            packageName = target.packageName,
            uid = target.uid,
            attributionScope = scope,
            config = config,
            capabilities = capabilities,
            environment = BatteryEnvironment(state, bootId = bootId),
            createdAt = Instant.now(),
        )
    }

    private suspend fun beginRun(
        session: BatterySession,
        iteration: Int,
    ): ActiveBatteryExperiment {
        val baseline = captureSnapshot(session, 0, includeHistory = false)
        return ActiveBatteryExperiment(session, UUID.randomUUID().toString(), iteration, baseline, mutableListOf(), 1)
    }

    private suspend fun captureSnapshot(
        session: BatterySession,
        sequence: Int,
        includeHistory: Boolean,
    ): BatterySnapshot {
        val durations = linkedMapOf<String, Long>()

        suspend fun timed(
            name: String,
            arguments: List<String>,
        ): String {
            val started = System.nanoTime()
            return try {
                commandRunner.execute(arguments)
            } finally {
                durations[name] = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
            }
        }
        val checkin = timed("checkin", listOf("dumpsys", "batterystats", "--checkin"))
        val report = optionalTimed("report", listOf("dumpsys", "batterystats"), durations).orEmpty()
        val battery = timed("battery", listOf("dumpsys", "battery"))
        val history =
            if (includeHistory && session.capabilities.history) {
                optionalTimed("history", listOf("dumpsys", "batterystats", "--history"), durations)
            } else {
                null
            }
        val parsed = parser.parse(checkin, report, battery, target.uid)
        return BatterySnapshot(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            sequence = sequence,
            capturedAt = Instant.now(),
            statsPeriodId = parsed.statsPeriodId,
            bootId = session.environment.bootId,
            uidStats = parsed.uidStats,
            deviceState = parser.parseDeviceState(battery),
            history = history?.let(parser::parseHistory) ?: parsed.history,
            warnings = parsed.warnings,
            rawEvidence = BatteryRawEvidence(checkin, report, battery, history, durations),
        )
    }

    private suspend fun launchTarget() {
        val component = target.launcherComponent ?: return
        commandRunner.execute(
            listOf("am", "start", "-n", component, "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"),
        )
    }

    private suspend fun optionalTimed(
        name: String,
        arguments: List<String>,
        durations: MutableMap<String, Long>,
    ): String? {
        val started = System.nanoTime()
        return try {
            optionalCommand(arguments)
        } finally {
            durations[name] = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
        }
    }

    private suspend fun optionalCommand(arguments: List<String>): String? =
        try {
            commandRunner.execute(arguments)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

    private fun detectCapabilities(
        help: String,
        report: String,
    ): BatteryCapabilities {
        val checkin = "--checkin" in help || report.isNotBlank()
        val history = "--history" in help || "Battery History" in report
        val reset = "--reset" in help
        val energy = report.contains("Estimated power", ignoreCase = true) || report.contains("Energy Consumer", ignoreCase = true)
        val level =
            when {
                energy && checkin -> BatteryCapabilityLevel.ENERGY_ENHANCED
                checkin && history -> BatteryCapabilityLevel.RESOURCE_FULL
                checkin -> BatteryCapabilityLevel.RESOURCE_BASIC
                else -> BatteryCapabilityLevel.UNAVAILABLE
            }
        return BatteryCapabilities(
            level = level,
            checkin = checkin,
            history = history,
            reset = reset,
            energy = energy,
            bugreport = true,
            missingReasons =
                buildList {
                    if (!checkin) add("batterystats checkin is unavailable")
                    if (!history) add("batterystats history is unavailable")
                    if (!energy) add("device energy estimates are unavailable")
                },
        )
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

private class JvmBatteryCommandRunner(
    private val adbExecutable: Path,
    private val serial: String,
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
) : BatteryCommandRunner {
    override suspend fun execute(arguments: List<String>): String {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial, "shell") + arguments,
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
                throw BatteryCaptureException(detail)
            }
        }
    }

    private companion object {
        val COMMAND_TIMEOUT = 60.seconds
        const val MAX_OUTPUT = 128 * 1024 * 1024
    }
}

public class BatteryCaptureException(
    message: String,
) : IllegalStateException(message)
