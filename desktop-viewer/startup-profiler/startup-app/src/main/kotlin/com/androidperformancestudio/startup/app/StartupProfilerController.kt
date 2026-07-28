@file:Suppress("MaxLineLength", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.androidperformancestudio.startup.app

import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.startup.startup_app.generated.resources.Res
import com.androidperformancestudio.startup.startup_app.generated.resources.*

import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.export.StartupCsvExporter
import com.androidperformancestudio.startup.export.StartupJsonExporter
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.startup.presentation.StartupProfilerState
import com.androidperformancestudio.startup.presentation.withCompilationMode
import com.androidperformancestudio.startup.presentation.withStartupType
import com.androidperformancestudio.startup.storage.SqliteStartupSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal class StartupProfilerController(
    private val chinese: Boolean = false,
    private val backend: StartupBackend = DesktopStartupBackend(),
    private val analyzer: StartupAnalyzer = StartupAnalyzer(),
    private val csvExporter: StartupCsvExporter = StartupCsvExporter(),
    private val jsonExporter: StartupJsonExporter = StartupJsonExporter(),
    private val databaseFile: Path = defaultDatabaseFile(),
) {
    private val mutableState = MutableStateFlow(StartupProfilerState())

    val state: StateFlow<StartupProfilerState> = mutableState.asStateFlow()

    suspend fun refreshDevices() {
        if (mutableState.value.isRunning) return
        mutableState.value = mutableState.value.copy(isRefreshing = true, errorMessage = null)
        when (val result = backend.listDevices()) {
            is StartupBackendResult.Failure ->
                mutableState.value =
                    mutableState.value.copy(isRefreshing = false, errorMessage = result.message)
            is StartupBackendResult.Success -> {
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
                        targets = if (selected == null) emptyList() else mutableState.value.targets,
                        selectedComponentName = if (selected == null) null else mutableState.value.selectedComponentName,
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
                selectedComponentName = null,
                isRefreshing = true,
                errorMessage = null,
            )
        when (val result = backend.listTargets(serial)) {
            is StartupBackendResult.Failure ->
                mutableState.value =
                    mutableState.value.copy(isRefreshing = false, errorMessage = result.message)
            is StartupBackendResult.Success ->
                mutableState.value =
                    mutableState.value.copy(
                        targets = result.value,
                        selectedComponentName = result.value.singleOrNull()?.componentName,
                        isRefreshing = false,
                        operationMessage = localizedStringResource(Res.string.found_launchable_activities, chinese, result.value.size),
                    )
        }
    }

    fun selectTarget(componentName: String) {
        if (!mutableState.value.isRunning && mutableState.value.targets.any { it.componentName == componentName }) {
            mutableState.value = mutableState.value.copy(selectedComponentName = componentName, errorMessage = null)
        }
    }

    fun selectStartupType(type: StartupType) {
        if (!mutableState.value.isRunning) mutableState.value = mutableState.value.withStartupType(type)
    }

    fun selectCompilationMode(mode: CompilationMode) {
        if (!mutableState.value.isRunning) mutableState.value = mutableState.value.withCompilationMode(mode)
    }

    fun updateCounts(
        warmups: Int,
        measured: Int,
    ) {
        if (!mutableState.value.isRunning) {
            mutableState.value =
                mutableState.value.copy(config = mutableState.value.config.copy(warmupRuns = warmups, measuredRuns = measured))
        }
    }

    fun updateTimeout(seconds: Int) {
        if (!mutableState.value.isRunning) {
            mutableState.value = mutableState.value.copy(config = mutableState.value.config.copy(timeoutSeconds = seconds))
        }
    }

    suspend fun runExperiment() {
        val snapshot = mutableState.value
        if (snapshot.isRunning) return
        val serial = snapshot.selectedDeviceSerial ?: return
        val target = snapshot.targets.firstOrNull { it.componentName == snapshot.selectedComponentName } ?: return
        val runner =
            when (val result = backend.openRunner(serial, target)) {
                is StartupBackendResult.Failure -> {
                    mutableState.value = snapshot.copy(errorMessage = result.message)
                    return
                }
                is StartupBackendResult.Success -> result.value
            }
        mutableState.value =
            snapshot.copy(
                isRunning = true,
                completedRuns = 0,
                totalRuns = snapshot.config.warmupRuns + snapshot.config.measuredRuns,
                operationMessage = localizedStringResource(Res.string.preparing_startup_experiment, chinese),
                warnings = emptyList(),
                errorMessage = null,
            )
        try {
            val result =
                runner.run(snapshot.config) { progress ->
                    mutableState.value =
                        mutableState.value.copy(
                            completedRuns = progress.completedRuns,
                            totalRuns = progress.totalRuns,
                            operationMessage = progress.message,
                        )
                }
            val analysis = analyzer.analyze(result.runs)
            val persistenceWarning = persist(result.session, analysis.runs)
            mutableState.value =
                mutableState.value.copy(
                    analysis = analysis,
                    baseline = snapshot.analysis,
                    selectedRunId = analysis.runs.firstOrNull()?.id,
                    isRunning = false,
                    completedRuns = snapshot.config.warmupRuns + snapshot.config.measuredRuns,
                    operationMessage = localizedStringResource(Res.string.startup_experiment_completed_measured_runs, chinese, analysis.runs.size),
                    warnings = (result.warnings + analysis.warnings + listOfNotNull(persistenceWarning)).distinct(),
                )
        } catch (exception: CancellationException) {
            mutableState.value = mutableState.value.copy(isRunning = false, operationMessage = localizedStringResource(Res.string.startup_experiment_cancelled, chinese))
            throw exception
        } catch (exception: Exception) {
            mutableState.value =
                mutableState.value.copy(
                    isRunning = false,
                    errorMessage = exception.message ?: localizedStringResource(Res.string.startup_experiment_failed, chinese),
                )
        }
    }

    fun selectRun(runId: String) {
        if (mutableState.value.analysis
                ?.runs
                ?.any { it.id == runId } == true
        ) {
            mutableState.value = mutableState.value.copy(selectedRunId = runId)
        }
    }

    suspend fun exportCsv(output: Path) {
        val analysis = mutableState.value.analysis ?: return
        export(output, "CSV") { csvExporter.export(analysis, output) }
    }

    suspend fun exportJson(output: Path) {
        val analysis = mutableState.value.analysis ?: return
        export(output, "JSON") { jsonExporter.export(analysis, output) }
    }

    private suspend fun export(
        output: Path,
        format: String,
        block: () -> Unit,
    ) {
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onSuccess {
                mutableState.value =
                    mutableState.value.copy(operationMessage = localizedStringResource(Res.string.exported_report_to, chinese, format, output.fileName), errorMessage = null)
            }.onFailure {
                mutableState.value =
                    mutableState.value.copy(
                        errorMessage = it.message ?: localizedStringResource(Res.string.format_export_failed, chinese, format),
                    )
            }
    }

    private suspend fun persist(
        session: com.androidperformancestudio.startup.model.StartupSession,
        runs: List<com.androidperformancestudio.startup.model.StartupRun>,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching { SqliteStartupSessionStore.open(databaseFile).use { it.save(session, runs) } }
                .exceptionOrNull()
                ?.let { localizedStringResource(Res.string.session_persistence_failed, chinese, it.message) }
        }

    private companion object {
        fun defaultDatabaseFile(): Path =
            Path.of(System.getProperty("user.home"), ".android-performance-studio", "startup-profiler", "startup.db")
    }
}
