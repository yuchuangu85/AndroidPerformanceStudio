@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.battery.model

import java.time.Instant

public enum class BatteryCaptureMode { INTERACTIVE, TIMED, REPEATED, ONLINE }

public enum class BatterySessionStatus { RUNNING, COMPLETED, INTERRUPTED }

public enum class BatteryCapabilityLevel { RESOURCE_FULL, RESOURCE_BASIC, ENERGY_ENHANCED, HISTORIAN_EXPORT, UNAVAILABLE }

public enum class AttributionScope { PACKAGE, UID, SHARED_UID, DEVICE }

public enum class EvidenceConfidence { EXACT, MODELED, INFERRED, UNAVAILABLE }

public enum class EnergyEvidenceKind { HARDWARE_COUNTER, SYSTEM_MODEL, UID_COUNTER, HISTORY_CORRELATION, DEVICE_STATE, UNAVAILABLE }

public enum class BatteryHistoryEventKind { WAKELOCK, ALARM, JOB, SENSOR, NETWORK, APP_STATE, SCREEN, CHARGING, THERMAL, UNKNOWN }

public data class BatteryDevice(
    val serial: String,
    val name: String,
    val online: Boolean = true,
)

public data class BatteryTarget(
    val packageName: String,
    val uid: Int,
    val versionName: String? = null,
    val sharedUid: Boolean = false,
    val launcherComponent: String? = null,
)

public data class BatteryExperimentConfig(
    val mode: BatteryCaptureMode = BatteryCaptureMode.INTERACTIVE,
    val durationSeconds: Int = 60,
    val pollingIntervalSeconds: Int = 10,
    val measuredRuns: Int = 1,
    val launchApp: Boolean = false,
    val cooldownSeconds: Int = 30,
) {
    init {
        require(durationSeconds in 5..3600) { "durationSeconds must be between 5 and 3600" }
        require(pollingIntervalSeconds in 5..60) { "pollingIntervalSeconds must be between 5 and 60" }
        require(measuredRuns in 1..50) { "measuredRuns must be between 1 and 50" }
        require(cooldownSeconds in 0..300) { "cooldownSeconds must be between 0 and 300" }
    }
}

public data class BatteryCapabilities(
    val level: BatteryCapabilityLevel,
    val checkin: Boolean,
    val history: Boolean,
    val reset: Boolean,
    val energy: Boolean,
    val bugreport: Boolean,
    val missingReasons: List<String> = emptyList(),
)

public data class BatteryDeviceState(
    val levelPercent: Int? = null,
    val temperatureTenthsCelsius: Int? = null,
    val voltageMillivolts: Int? = null,
    val powered: Boolean? = null,
    val status: String? = null,
    val rawValues: Map<String, String> = emptyMap(),
)

public data class BatteryEnvironment(
    val initialState: BatteryDeviceState,
    val apiLevel: Int? = null,
    val bootId: String? = null,
    val statsPeriodId: String? = null,
)

public data class ResourceTimer(
    val name: String,
    val durationMs: Long,
    val count: Long,
    val confidence: EvidenceConfidence = EvidenceConfidence.EXACT,
)

public data class NetworkUsage(
    val mobileRxBytes: Long = 0,
    val mobileTxBytes: Long = 0,
    val wifiRxBytes: Long = 0,
    val wifiTxBytes: Long = 0,
    val bluetoothRxBytes: Long = 0,
    val bluetoothTxBytes: Long = 0,
    val mobileRxPackets: Long = 0,
    val mobileTxPackets: Long = 0,
    val wifiRxPackets: Long = 0,
    val wifiTxPackets: Long = 0,
    val mobileRadioActiveMs: Long = 0,
) {
    public val totalBytes: Long
        get() = mobileRxBytes + mobileTxBytes + wifiRxBytes + wifiTxBytes + bluetoothRxBytes + bluetoothTxBytes
}

public data class EnergyEstimate(
    val component: String,
    val energyMah: Double? = null,
    val energyUws: Long? = null,
    val source: EnergyEvidenceKind,
    val attributionScope: AttributionScope,
    val confidence: EvidenceConfidence,
)

public data class UidBatteryStats(
    val uid: Int,
    val wakelocks: Map<String, ResourceTimer> = emptyMap(),
    val alarms: Map<String, ResourceTimer> = emptyMap(),
    val jobs: Map<String, ResourceTimer> = emptyMap(),
    val sensors: Map<String, ResourceTimer> = emptyMap(),
    val network: NetworkUsage = NetworkUsage(),
    val energy: Map<String, EnergyEstimate> = emptyMap(),
)

public data class BatteryHistoryEvent(
    val elapsedMs: Long?,
    val kind: BatteryHistoryEventKind,
    val active: Boolean?,
    val name: String?,
    val uid: Int?,
    val raw: String,
    val confidence: EvidenceConfidence,
)

public data class BatteryRawEvidence(
    val checkin: String,
    val report: String,
    val battery: String,
    val history: String? = null,
    val commandDurationsMs: Map<String, Long> = emptyMap(),
)

public data class BatterySnapshot(
    val id: String,
    val sessionId: String,
    val sequence: Int,
    val capturedAt: Instant,
    val statsPeriodId: String?,
    val bootId: String?,
    val uidStats: UidBatteryStats,
    val deviceState: BatteryDeviceState,
    val history: List<BatteryHistoryEvent> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawEvidence: BatteryRawEvidence,
    val conditions: Map<String, String> = emptyMap(),
)

public data class BatterySession(
    val id: String,
    val deviceSerial: String,
    val packageName: String,
    val uid: Int,
    val attributionScope: AttributionScope,
    val config: BatteryExperimentConfig,
    val capabilities: BatteryCapabilities,
    val environment: BatteryEnvironment,
    val createdAt: Instant,
)

public data class BatteryRun(
    val id: String,
    val sessionId: String,
    val iteration: Int,
    val baseline: BatterySnapshot,
    val samples: List<BatterySnapshot>,
    val finalSnapshot: BatterySnapshot,
)

public data class BatteryRunDelta(
    val runId: String,
    val sessionId: String,
    val iteration: Int,
    val durationMs: Long,
    val wakelocks: List<ResourceTimer>,
    val alarms: List<ResourceTimer>,
    val jobs: List<ResourceTimer>,
    val sensors: List<ResourceTimer>,
    val network: NetworkUsage,
    val energy: List<EnergyEstimate>,
    val history: List<BatteryHistoryEvent>,
    val warnings: List<String>,
)

public data class BatteryStatistics(
    val count: Int,
    val missingCount: Int,
    val minimum: Double?,
    val maximum: Double?,
    val median: Double?,
    val mean: Double?,
    val p90: Double?,
    val p95: Double?,
    val standardDeviation: Double?,
    val medianAbsoluteDeviation: Double?,
)

public data class BatteryExperimentResult(
    val session: BatterySession,
    val runs: List<BatteryRun>,
)
