@file:Suppress(
    "MaxLineLength",
    "ktlint:standard:max-line-length",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.analysis.BatteryAnalyzer
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.baseline_captured_perform_the_target_scenario_then_stop_the_experiment
import com.androidperformancestudio.battery.battery_app.generated.resources.battery_experiment_cancelled
import com.androidperformancestudio.battery.battery_app.generated.resources.battery_experiment_completed_run_s
import com.androidperformancestudio.battery.battery_app.generated.resources.battery_experiment_failed
import com.androidperformancestudio.battery.battery_app.generated.resources.bugreport_generation_failed
import com.androidperformancestudio.battery.battery_app.generated.resources.captured_online_sample_values_are_cumulative_deltas_not_instantaneous
import com.androidperformancestudio.battery.battery_app.generated.resources.capturing_baseline_snapshot
import com.androidperformancestudio.battery.battery_app.generated.resources.capturing_final_snapshot_and_history
import com.androidperformancestudio.battery.battery_app.generated.resources.export_failed
import com.androidperformancestudio.battery.battery_app.generated.resources.exported_to
import com.androidperformancestudio.battery.battery_app.generated.resources.found_packages_with_uid_attribution
import com.androidperformancestudio.battery.battery_app.generated.resources.generated_battery_historian_input_bytes
import com.androidperformancestudio.battery.battery_app.generated.resources.generating_privacy_sensitive_bugreport_for_battery_historian
import com.androidperformancestudio.battery.battery_app.generated.resources.global_device_batterystats_were_reset_start_a_new_experiment
import com.androidperformancestudio.battery.battery_app.generated.resources.import_failed
import com.androidperformancestudio.battery.battery_app.generated.resources.imported_analysis
import com.androidperformancestudio.battery.battery_app.generated.resources.online_sample_failed
import com.androidperformancestudio.battery.battery_app.generated.resources.preparing_battery_experiment
import com.androidperformancestudio.battery.battery_app.generated.resources.raw_bundle
import com.androidperformancestudio.battery.battery_app.generated.resources.session_persistence_failed
import com.androidperformancestudio.battery.battery_app.generated.resources.unable_to_start_battery_experiment
import com.androidperformancestudio.battery.battery_app.generated.resources.unable_to_stop_battery_experiment
import com.androidperformancestudio.battery.capture.ActiveBatteryExperiment
import com.androidperformancestudio.battery.capture.BatteryExperimentRunner
import com.androidperformancestudio.battery.export.BatteryCsvExporter
import com.androidperformancestudio.battery.export.BatteryJsonExporter
import com.androidperformancestudio.battery.export.BatteryJsonImporter
import com.androidperformancestudio.battery.export.BatteryRawBundleExporter
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryExperimentResult
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import com.androidperformancestudio.battery.storage.SqliteBatterySessionStore
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal class BatteryProfilerController(
    private val language: UiLanguage = UiLanguage.ENGLISH,
    private val backend: BatteryBackend = DesktopBatteryBackend(),
    private val analyzer: BatteryAnalyzer = BatteryAnalyzer(),
    private val jsonExporter: BatteryJsonExporter = BatteryJsonExporter(),
    private val jsonImporter: BatteryJsonImporter = BatteryJsonImporter(),
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
                        operationMessage =
                            localizedStringResource(
                                Res.string.found_packages_with_uid_attribution,
                                language,
                                result.value.size,
                            ),
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
            snapshot.copy(
                isRunning = true,
                operationMessage = localizedStringResource(Res.string.capturing_baseline_snapshot, language),
                warnings = emptyList(),
                errorMessage = null,
            )
        try {
            val experiment = selectedRunner.start(snapshot.config)
            runner = selectedRunner
            active = experiment
            mutableState.value =
                mutableState.value.copy(
                    isInteractiveActive = true,
                    operationMessage =
                        localizedStringResource(
                            Res.string.baseline_captured_perform_the_target_scenario_then_stop_the_experiment,
                            language,
                        ),
                )
        } catch (exception: Exception) {
            runner = null
            active = null
            mutableState.value =
                mutableState.value.copy(
                    isRunning = false,
                    isInteractiveActive = false,
                    errorMessage =
                        exception.message ?: localizedStringResource(Res.string.unable_to_start_battery_experiment, language),
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
                    operationMessage =
                        localizedStringResource(
                            Res.string.captured_online_sample_values_are_cumulative_deltas_not_instantaneous,
                            language,
                            sample.sequence,
                        ),
                )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            mutableState.value =
                mutableState.value.copy(
                    warnings =
                        (
                            mutableState.value.warnings +
                                localizedStringResource(Res.string.online_sample_failed, language, exception.message.orEmpty())
                        ).distinct(),
                )
        }
    }

    suspend fun stopInteractive() {
        val selectedRunner = runner ?: return
        val experiment = active ?: return
        mutableState.value =
            mutableState.value.copy(operationMessage = localizedStringResource(Res.string.capturing_final_snapshot_and_history, language))
        try {
            complete(selectedRunner.stop(experiment))
        } catch (exception: Exception) {
            mutableState.value =
                mutableState.value.copy(
                    isRunning = false,
                    isInteractiveActive = false,
                    errorMessage =
                        exception.message ?: localizedStringResource(Res.string.unable_to_stop_battery_experiment, language),
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
                operationMessage = localizedStringResource(Res.string.preparing_battery_experiment, language),
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
            mutableState.value =
                mutableState.value.copy(
                    isRunning = false,
                    operationMessage = localizedStringResource(Res.string.battery_experiment_cancelled, language),
                )
            throw exception
        } catch (exception: Exception) {
            mutableState.value =
                mutableState.value.copy(
                    isRunning = false,
                    errorMessage = exception.message ?: localizedStringResource(Res.string.battery_experiment_failed, language),
                )
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

    suspend fun importJson(input: Path): Boolean {
        if (mutableState.value.isRunning) return false
        return runCatching { withContext(Dispatchers.IO) { jsonImporter.import(input) } }
            .fold(
                onSuccess = { analysis ->
                    mutableState.value =
                        mutableState.value.copy(
                            experiment = null,
                            analysis = analysis,
                            baseline = null,
                            selectedRunId = analysis.runs.firstOrNull()?.runId,
                            completedSteps = 0,
                            totalSteps = 0,
                            operationMessage = localizedStringResource(Res.string.imported_analysis, language, input.fileName),
                            warnings = analysis.warnings,
                            errorMessage = null,
                        )
                    true
                },
                onFailure = { exception ->
                    mutableState.value =
                        mutableState.value.copy(
                            errorMessage =
                                exception.message
                                    ?: localizedStringResource(Res.string.import_failed, language),
                        )
                    false
                },
            )
    }

    suspend fun exportJson(output: Path) =
        export(output, "JSON") {
            val experiment = requireNotNull(mutableState.value.experiment)
            val analysis = requireNotNull(mutableState.value.analysis)
            jsonExporter.export(experiment, analysis, output)
        }

    suspend fun exportCsv(output: Path) = export(output, "CSV") { csvExporter.export(requireNotNull(mutableState.value.analysis), output) }

    suspend fun exportRawBundle(output: Path) =
        export(output, localizedStringResource(Res.string.raw_bundle, language)) {
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
            mutableState.value.copy(
                operationMessage =
                    localizedStringResource(
                        Res.string.generating_privacy_sensitive_bugreport_for_battery_historian,
                        language,
                    ),
                errorMessage = null,
            )
        runCatching { withContext(Dispatchers.IO) { adapter.generateBugreport(output) } }
            .onSuccess { artifact ->
                mutableState.value =
                    mutableState.value.copy(
                        operationMessage =
                            localizedStringResource(
                                Res.string.generated_battery_historian_input_bytes,
                                language,
                                artifact.path.fileName,
                                artifact.sizeBytes,
                            ),
                    )
            }.onFailure {
                mutableState.value =
                    mutableState.value.copy(
                        errorMessage = it.message ?: localizedStringResource(Res.string.bugreport_generation_failed, language),
                    )
            }
    }

    suspend fun resetStatistics() {
        val serial = mutableState.value.selectedDeviceSerial ?: return
        when (val result = backend.resetStatistics(serial)) {
            is BatteryBackendResult.Failure -> mutableState.value = mutableState.value.copy(errorMessage = result.message)
            is BatteryBackendResult.Success ->
                mutableState.value =
                    mutableState.value.copy(
                        operationMessage =
                            localizedStringResource(
                                Res.string.global_device_batterystats_were_reset_start_a_new_experiment,
                                language,
                            ),
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
                operationMessage = localizedStringResource(Res.string.battery_experiment_completed_run_s, language, analysis.runs.size),
                warnings = (analysis.warnings + listOfNotNull(persistenceWarning)).distinct(),
            )
    }

    private suspend fun persist(
        experiment: BatteryExperimentResult,
        deltas: List<BatteryRunDelta>,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching { SqliteBatterySessionStore.open(databaseFile).use { it.save(experiment.session, experiment.runs, deltas) } }
                .exceptionOrNull()
                ?.let { localizedStringResource(Res.string.session_persistence_failed, language, it.message.orEmpty()) }
        }

    private suspend fun export(
        output: Path,
        format: String,
        block: () -> Unit,
    ) {
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onSuccess {
                mutableState.value =
                    mutableState.value.copy(
                        operationMessage = localizedStringResource(Res.string.exported_to, language, format, output.fileName),
                        errorMessage = null,
                    )
            }.onFailure {
                mutableState.value =
                    mutableState.value.copy(
                        errorMessage = it.message ?: localizedStringResource(Res.string.export_failed, language, format),
                    )
            }
    }

    private companion object {
        fun defaultDatabaseFile(): Path =
            Path.of(System.getProperty("user.home"), ".android-performance-studio", "battery-profiler", "battery.db")
    }
}

private fun BatterySession.isCompatibleBaselineFor(
    other: BatterySession,
): Boolean =
    deviceSerial == other.deviceSerial &&
        packageName == other.packageName &&
        uid == other.uid &&
        attributionScope == other.attributionScope &&
        environment.initialState.powered == other.environment.initialState.powered
