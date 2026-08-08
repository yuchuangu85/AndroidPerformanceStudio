@file:Suppress("MaxLineLength")

package com.androidperformancestudio.startup.presentation

import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
import com.androidperformancestudio.startup.analysis.StartupComparison
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.StartupDevice
import com.androidperformancestudio.startup.model.StartupExperimentConfig
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.startup.model.StartupType

public data class StartupProfilerState(
    val devices: List<StartupDevice> = emptyList(),
    val selectedDeviceSerial: String? = null,
    val targets: List<StartupTarget> = emptyList(),
    val selectedComponentName: String? = null,
    val config: StartupExperimentConfig = StartupExperimentConfig(),
    val analysis: StartupAnalysisResult? = null,
    val baseline: StartupAnalysisResult? = null,
    val comparison: StartupComparison? = null,
    val selectedRunId: String? = null,
    val isRefreshing: Boolean = false,
    val isRunning: Boolean = false,
    val completedRuns: Int = 0,
    val totalRuns: Int = 0,
    val operationMessage: String? = null,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
    val compilationConfirmationRequired: Boolean = false,
)

public data class StartupProfilerActions(
    val onSelectRun: (String) -> Unit = {},
)

public fun StartupProfilerState.withStartupType(type: StartupType): StartupProfilerState = copy(config = config.copy(requestedType = type))

public fun StartupProfilerState.withCompilationMode(mode: CompilationMode): StartupProfilerState =
    copy(
        config =
            config.copy(
                compilationMode = mode,
                warmupRuns = if (mode == CompilationMode.SPEED_PROFILE) config.warmupRuns else 0,
                profileSource = if (mode == CompilationMode.SPEED_PROFILE) config.profileSource else StartupProfileSource.UNVERIFIED,
            ),
    )
