@file:Suppress(
    "MaxLineLength",
    "ktlint:standard:max-line-length",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.analysis.BatteryAnalyzer
import com.androidperformancestudio.battery.capture.ActiveBatteryExperiment
import com.androidperformancestudio.battery.capture.BatteryExperimentRunner
import com.androidperformancestudio.battery.export.BatteryCsvExporter
import com.androidperformancestudio.battery.export.BatteryJsonExporter
import com.androidperformancestudio.battery.export.BatteryRawBundleExporter
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryExperimentResult
import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import com.androidperformancestudio.battery.storage.SqliteBatterySessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal class BatteryProfilerController(
    private val backend: BatteryBackend = DesktopBatteryBackend(),
    private val analyzer: BatteryAnalyzer = BatteryAnalyzer(),
    private val jsonExporter: BatteryJsonExporter = BatteryJsonExporter(),
    private val csvExporter: BatteryCsvExporter = BatteryCsvExporter(),
    private val rawExporter: BatteryRawBundleExporter = BatteryRawBundleExporter(),
    private val databaseFile: Path = defaultDatabaseFile(),
) {
    private val mutableState = MutableStateFlow(BatteryProfilerState())
    private var runner: BatteryExperimentRunner? = null
    private var active: ActiveBatteryExperiment? = null

    val state: StateFlow<BatteryProfilerState> = mutableState.asStateFlow()

    suspend fun refreshDevices() {
        if (mutableState.value.isRunning) return
        mutableState.value = mutableState.value.copy(isRefreshing = true, errorMessage = null)
        when (val result = backend.listDevices()) {
            is BatteryBackendResult.Failure ->
                mutableState.value =
                    mutableState.value.copy(isRefreshing = false, errorMessage = result.message)
            is BatteryBackendResult.Success -> {
                val selected =
                    mutableState.value.selectedDeviceSerial?.takeIf { serial ->
                        result.value.any {
                            it.serial == serial &&
                                it.online
                        }
                    }
                mutableState.value =
                    mutableState.value.copy(
                        devices = result.value,
                        selectedDeviceSerial = selected,
                        targets =
                            if (selected ==
                                null
                            ) {
                                emptyList()
                            } else {
                                mutableState.value.targets
                            },
                        selectedPackageName =
                            if (selected ==
                                null
                            ) {
                                null
                            } else {
                                mutableState.value.selectedPackageName
                            },
                        isRefreshing = false,
                    )
            }
        }
    }

    suspend fun selectDevice(serial: String) {
        if (mutableState.value.isRunning) return
        mutableState.value =
            mutableState.value.copy(
                selectedDeviceSerial = serial,
                targets = emptyList(),
                selectedPackageName = null,
                isRefreshing = true,
                errorMessage = null,
            )
        when (val result = backend.listTargets(serial)) {
            is BatteryBackendResult.Failure ->
                mutableState.value =
                    mutableState.value.copy(isRefreshing = false, errorMessage = result.message)
            is BatteryBackendResult.Success ->
                mutableState.value =
                    mutableState.value.copy(
                        targets = result.value,
                        selectedPackageName = result.value.singleOrNull()?.packageName,
                        isRefreshing = false,
                        operationMessage = "Found ${result.value.size} packages with UID attribution.",
                    )
        }
    }

    fun selectTarget(packageName: String) {
        if (!mutableState.value.isRunning &&
            mutableState.value.targets.any { it.packageName == packageName }
        ) {
            mutableState.value =
                mutableState.value.copy(selectedPackageName = packageName, errorMessage = null)
        }
    }

    fun updateConfig(transform: (BatteryExperimentConfig) -> BatteryExperimentConfig) {
        if (!mutableState.value.isRunning) mutableState.value = mutableState.value.copy(config = transform(mutableState.value.config))
    }

    suspend fun startInteractive() {
        val snapshot = mutableState.value
        if (snapshot.isRunning) return
        val selectedRunner = openRunner(snapshot) ?: return
        mutableState.value =
            snapshot.copy(isRunning = true, operationMessage = "Capturing baseline snapshot…", warnings = emptyList(), errorMessage = null)
        try {
            val experiment = selectedRunner.start(snapshot.config)
            runner = selectedRunner
            active = experiment
            mutableState.value =
                mutableState.value.copy(
                    isInteractiveActive = true,
                    operationMessage = "Baseline captured. Perform the target scenario, then stop the experiment.",
                )
        } catch (exception: Exception) {
            runner = null
            active = null
            mutableState.value =
                mutableState.value.copy(
                    isRunning = false,
                    isInteractiveActive = false,
                    errorMessage =
                        exception.message ?: "Unable to start battery experiment.",
                )
        }
    }

    suspend fun pollInteractive() {
        val selectedRunner = runner ?: return
        val experiment = active ?: return
        try {
            val sample = selectedRunner.poll(experiment)
            mutableState.value =
                mutableState.value.copy(
                    operationMessage = "Captured online sample #${sample.sequence}; values are cumulative deltas, not instantaneous power.",
                )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            mutableState.value =
                mutableState.value.copy(warnings = (mutableState.value.warnings + "Online sample failed: ${exception.message}").distinct())
        }
    }

    suspend fun stopInteractive() {
        val selectedRunner = runner ?: return
        val experiment = active ?: return
        mutableState.value = mutableState.value.copy(operationMessage = "Capturing final snapshot and history…")
        try {
            complete(selectedRunner.stop(experiment))
        } catch (exception: Exception) {
            mutableState.value =
                mutableState.value.copy(
                    isRunning = false,
                    isInteractiveActive = false,
                    errorMessage =
                        exception.message ?: "Unable to stop battery experiment.",
                )
        } finally {
            runner = null
            active = null
        }
    }

    suspend fun runAutomatic() {
        val snapshot = mutableState.value
        if (snapshot.isRunning) return
        val selectedRunner = openRunner(snapshot) ?: return
        mutableState.value =
            snapshot.copy(
                isRunning = true,
                completedSteps = 0,
                totalSteps = snapshot.config.measuredRuns * 2,
                operationMessage = "Preparing battery experiment…",
                warnings = emptyList(),
                errorMessage = null,
            )
        try {
            val result =
                selectedRunner.run(snapshot.config) { progress ->
                    mutableState.value =
                        mutableState.value.copy(
                            completedSteps = progress.completedSteps,
                            totalSteps = progress.totalSteps,
                            operationMessage = progress.message,
                        )
                }
            complete(result)
        } catch (exception: CancellationException) {
            mutableState.value = mutableState.value.copy(isRunning = false, operationMessage = "Battery experiment cancelled.")
            throw exception
        } catch (exception: Exception) {
            mutableState.value =
                mutableState.value.copy(isRunning = false, errorMessage = exception.message ?: "Battery experiment failed.")
        }
    }

    fun selectRun(runId: String) {
        if (mutableState.value.analysis
                ?.runs
                ?.any { it.runId == runId } ==
            true
        ) {
            mutableState.value = mutableState.value.copy(selectedRunId = runId)
        }
    }

    suspend fun exportJson(output: Path) =
        export(output, "JSON") {
            val experiment = requireNotNull(mutableState.value.experiment)
            val analysis = requireNotNull(mutableState.value.analysis)
            jsonExporter.export(experiment, analysis, output)
        }

    suspend fun exportCsv(output: Path) = export(output, "CSV") { csvExporter.export(requireNotNull(mutableState.value.analysis), output) }

    suspend fun exportRawBundle(output: Path) =
        export(output, "raw bundle") {
            rawExporter.export(requireNotNull(mutableState.value.experiment), output)
        }

    suspend fun generateBugreport(output: Path) {
        val serial = mutableState.value.selectedDeviceSerial ?: return
        val adapter =
            when (val result = backend.openHistorian(serial)) {
                is BatteryBackendResult.Failure -> {
                    mutableState.value = mutableState.value.copy(errorMessage = result.message)
                    return
                }
                is BatteryBackendResult.Success -> result.value
            }
        mutableState.value =
            mutableState.value.copy(operationMessage = "Generating privacy-sensitive bugreport for Battery Historian…", errorMessage = null)
        runCatching { withContext(Dispatchers.IO) { adapter.generateBugreport(output) } }
            .onSuccess { artifact ->
                mutableState.value =
                    mutableState.value.copy(
                        operationMessage = "Generated Battery Historian input: ${artifact.path.fileName} (${artifact.sizeBytes} bytes).",
                    )
            }.onFailure {
                mutableState.value = mutableState.value.copy(errorMessage = it.message ?: "Bugreport generation failed.")
            }
    }

    suspend fun resetStatistics() {
        val serial = mutableState.value.selectedDeviceSerial ?: return
        when (val result = backend.resetStatistics(serial)) {
            is BatteryBackendResult.Failure -> mutableState.value = mutableState.value.copy(errorMessage = result.message)
            is BatteryBackendResult.Success ->
                mutableState.value =
                    mutableState.value.copy(
                        operationMessage = "Global device batterystats were reset. Start a new experiment.",
                        analysis = null,
                        experiment = null,
                        baseline = null,
                    )
        }
    }

    private fun openRunner(snapshot: BatteryProfilerState): BatteryExperimentRunner? {
        val serial = snapshot.selectedDeviceSerial ?: return null
        val target = snapshot.targets.firstOrNull { it.packageName == snapshot.selectedPackageName } ?: return null
        return when (val result = backend.openRunner(serial, target)) {
            is BatteryBackendResult.Failure -> {
                mutableState.value = snapshot.copy(errorMessage = result.message)
                null
            }
            is BatteryBackendResult.Success -> result.value
        }
    }

    private suspend fun complete(experiment: BatteryExperimentResult) {
        val analysis = analyzer.analyze(experiment.runs)
        val previousExperiment = mutableState.value.experiment
        val previous =
            mutableState.value.analysis.takeIf {
                previousExperiment != null && previousExperiment.session.isCompatibleBaselineFor(experiment.session)
            }
        val persistenceWarning = persist(experiment, analysis.runs)
        mutableState.value =
            mutableState.value.copy(
                experiment = experiment,
                analysis = analysis,
                baseline = previous,
                selectedRunId = analysis.runs.firstOrNull()?.runId,
                isRunning = false,
                isInteractiveActive = false,
                completedSteps = mutableState.value.totalSteps,
                operationMessage = "Battery experiment completed: ${analysis.runs.size} run(s).",
                warnings = (analysis.warnings + listOfNotNull(persistenceWarning)).distinct(),
            )
    }

    private suspend fun persist(
        experiment: BatteryExperimentResult,
        deltas: List<com.androidperformancestudio.battery.model.BatteryRunDelta>,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching { SqliteBatterySessionStore.open(databaseFile).use { it.save(experiment.session, experiment.runs, deltas) } }
                .exceptionOrNull()
                ?.let { "Analysis succeeded, but session persistence failed: ${it.message}" }
        }

    private suspend fun export(
        output: Path,
        format: String,
        block: () -> Unit,
    ) {
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onSuccess {
                mutableState.value =
                    mutableState.value.copy(operationMessage = "Exported $format to ${output.fileName}.", errorMessage = null)
            }.onFailure { mutableState.value = mutableState.value.copy(errorMessage = it.message ?: "$format export failed.") }
    }

    private companion object {
        fun defaultDatabaseFile(): Path =
            Path.of(System.getProperty("user.home"), ".android-performance-studio", "battery-profiler", "battery.db")
    }
}

private fun com.androidperformancestudio.battery.model.BatterySession.isCompatibleBaselineFor(
    other: com.androidperformancestudio.battery.model.BatterySession,
): Boolean =
    deviceSerial == other.deviceSerial &&
        packageName == other.packageName &&
        uid == other.uid &&
        attributionScope == other.attributionScope &&
        environment.initialState.powered == other.environment.initialState.powered
