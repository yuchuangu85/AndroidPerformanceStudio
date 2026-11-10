package com.androidperformancestudio.battery.presentation

import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryDevice
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryExperimentResult
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryTarget
import com.androidperformancestudio.battery.storage.StoredBatterySession
import com.androidperformancestudio.contracts.CaptureArtifact

public data class BatteryProfilerState(
    val sessions: List<StoredBatterySession> = emptyList(),
    val viewedAttributionScope: AttributionScope? = null,
    val viewedStoredSessionId: String? = null,
    val isLoadingSession: Boolean = false,
    val sessionError: String? = null,
    val devices: List<BatteryDevice> = emptyList(),
    val selectedDeviceSerial: String? = null,
    val targets: List<BatteryTarget> = emptyList(),
    val selectedPackageName: String? = null,
    val config: BatteryExperimentConfig = BatteryExperimentConfig(),
    val experiment: BatteryExperimentResult? = null,
    val analysis: BatteryAnalysisResult? = null,
    val baseline: BatteryAnalysisResult? = null,
    val selectedRunId: String? = null,
    val storedRuns: List<BatteryRun> = emptyList(),
    val isRefreshing: Boolean = false,
    val isRunning: Boolean = false,
    val isInteractiveActive: Boolean = false,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val operationMessage: String? = null,
    val completedDurationSeconds: Double? = null,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
    val artifact: CaptureArtifact? = null,
)

public data class BatteryProfilerActions(
    val onSelectRun: (String) -> Unit = {},
)
