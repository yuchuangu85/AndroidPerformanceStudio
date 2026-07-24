@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:max-line-length",
    "TooManyFunctions",
)

package com.androidperformancestudio.battery.analysis

import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatteryStatistics
import com.androidperformancestudio.battery.model.EnergyEstimate
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

public data class BatteryAnalysisResult(
    val runs: List<BatteryRunDelta>,
    val wakelockDurationMs: BatteryStatistics,
    val wakeupAlarmCount: BatteryStatistics,
    val jobDurationMs: BatteryStatistics,
    val sensorDurationMs: BatteryStatistics,
    val networkBytes: BatteryStatistics,
    val energyMah: BatteryStatistics,
    val warnings: List<String>,
)

public class BatteryAnalyzer {
    public fun analyze(runs: List<BatteryRun>): BatteryAnalysisResult {
        require(runs.isNotEmpty()) { "At least one battery run is required" }
        val deltas = runs.map(::diff)
        return BatteryAnalysisResult(
            runs = deltas,
            wakelockDurationMs = statistics(deltas.map { run -> run.wakelocks.sumOf(ResourceTimer::durationMs).toDouble() }),
            wakeupAlarmCount = statistics(deltas.map { run -> run.alarms.sumOf(ResourceTimer::count).toDouble() }),
            jobDurationMs = statistics(deltas.map { run -> run.jobs.sumOf(ResourceTimer::durationMs).toDouble() }),
            sensorDurationMs = statistics(deltas.map { run -> run.sensors.sumOf(ResourceTimer::durationMs).toDouble() }),
            networkBytes = statistics(deltas.map { run -> run.network.totalBytes.toDouble() }),
            energyMah = statistics(deltas.map { run -> run.energy.sumOf { it.energyMah ?: 0.0 }.takeIf { it > 0 } }),
            warnings = deltas.flatMap(BatteryRunDelta::warnings).distinct(),
        )
    }

    public fun diff(run: BatteryRun): BatteryRunDelta {
        val before = run.baseline
        val after = run.finalSnapshot
        val warnings = mutableListOf<String>()
        if (before.bootId != null && after.bootId != null && before.bootId != after.bootId) {
            warnings += "Device boot ID changed during the experiment; cumulative deltas may be invalid."
        }
        if (before.statsPeriodId != null && after.statsPeriodId != null && before.statsPeriodId != after.statsPeriodId) {
            warnings += "Battery statistics period changed during the experiment."
        }
        if (before.uidStats.uid != after.uidStats.uid) warnings += "Target UID changed during the experiment."
        val durationMs =
            java.time.Duration
                .between(before.capturedAt, after.capturedAt)
                .toMillis()
                .coerceAtLeast(0)
        val stateBefore = before.deviceState
        val stateAfter = after.deviceState
        if (stateBefore.powered != null && stateAfter.powered != null && stateBefore.powered != stateAfter.powered) {
            warnings += "Charging state changed during the experiment."
        }
        val beforeTemperature = stateBefore.temperatureTenthsCelsius
        val afterTemperature = stateAfter.temperatureTenthsCelsius
        val temperatureDelta =
            if (beforeTemperature != null && afterTemperature != null) {
                abs(afterTemperature - beforeTemperature)
            } else {
                0
            }
        if (temperatureDelta >= TEMPERATURE_WARNING_TENTHS) warnings += "Battery temperature changed by at least 3°C."
        val wakelocks = diffTimers(before.uidStats.wakelocks, after.uidStats.wakelocks, "wakelock", warnings)
        return BatteryRunDelta(
            runId = run.id,
            sessionId = run.sessionId,
            iteration = run.iteration,
            durationMs = durationMs,
            wakelocks = wakelocks,
            alarms = diffTimers(before.uidStats.alarms, after.uidStats.alarms, "alarm", warnings),
            jobs = diffTimers(before.uidStats.jobs, after.uidStats.jobs, "job", warnings),
            sensors = diffTimers(before.uidStats.sensors, after.uidStats.sensors, "sensor", warnings),
            network = diffNetwork(before.uidStats.network, after.uidStats.network, warnings),
            energy = diffEnergy(before.uidStats.energy, after.uidStats.energy, warnings),
            history = after.history.filter { event -> event.uid == null || event.uid == after.uidStats.uid },
            warnings = (before.warnings + after.warnings + warnings + diagnose(wakelocks, durationMs)).distinct(),
        )
    }

    public fun statistics(values: List<Double?>): BatteryStatistics {
        val present = values.filterNotNull().sorted()
        if (present.isEmpty()) return BatteryStatistics(0, values.size, null, null, null, null, null, null, null, null)
        val mean = present.average()
        val median = percentile(present, 0.5)
        val deviations = present.map { abs(it - median) }.sorted()
        return BatteryStatistics(
            count = present.size,
            missingCount = values.size - present.size,
            minimum = present.first(),
            maximum = present.last(),
            median = median,
            mean = mean,
            p90 = percentile(present, 0.9),
            p95 = percentile(present, 0.95),
            standardDeviation = sqrt(present.sumOf { (it - mean).pow(2) } / present.size),
            medianAbsoluteDeviation = percentile(deviations, 0.5),
        )
    }

    private fun diffTimers(
        before: Map<String, ResourceTimer>,
        after: Map<String, ResourceTimer>,
        kind: String,
        warnings: MutableList<String>,
    ): List<ResourceTimer> =
        (before.keys + after.keys)
            .mapNotNull { name ->
                val old = before[name]
                val current = after[name] ?: return@mapNotNull null
                val duration = current.durationMs - (old?.durationMs ?: 0)
                val count = current.count - (old?.count ?: 0)
                if (duration < 0 || count < 0) {
                    warnings += "The $kind counter '$name' reset during the experiment."
                    null
                } else if (duration == 0L && count == 0L) {
                    null
                } else {
                    current.copy(durationMs = duration, count = count)
                }
            }.sortedByDescending(ResourceTimer::durationMs)

    private fun diffNetwork(
        before: NetworkUsage,
        after: NetworkUsage,
        warnings: MutableList<String>,
    ): NetworkUsage {
        fun delta(
            name: String,
            old: Long,
            current: Long,
        ): Long {
            if (current >= old) return current - old
            warnings += "The network counter '$name' reset during the experiment."
            return 0
        }
        return NetworkUsage(
            mobileRxBytes = delta("mobileRxBytes", before.mobileRxBytes, after.mobileRxBytes),
            mobileTxBytes = delta("mobileTxBytes", before.mobileTxBytes, after.mobileTxBytes),
            wifiRxBytes = delta("wifiRxBytes", before.wifiRxBytes, after.wifiRxBytes),
            wifiTxBytes = delta("wifiTxBytes", before.wifiTxBytes, after.wifiTxBytes),
            bluetoothRxBytes = delta("bluetoothRxBytes", before.bluetoothRxBytes, after.bluetoothRxBytes),
            bluetoothTxBytes = delta("bluetoothTxBytes", before.bluetoothTxBytes, after.bluetoothTxBytes),
            mobileRxPackets = delta("mobileRxPackets", before.mobileRxPackets, after.mobileRxPackets),
            mobileTxPackets = delta("mobileTxPackets", before.mobileTxPackets, after.mobileTxPackets),
            wifiRxPackets = delta("wifiRxPackets", before.wifiRxPackets, after.wifiRxPackets),
            wifiTxPackets = delta("wifiTxPackets", before.wifiTxPackets, after.wifiTxPackets),
            mobileRadioActiveMs = delta("mobileRadioActiveMs", before.mobileRadioActiveMs, after.mobileRadioActiveMs),
        )
    }

    private fun diffEnergy(
        before: Map<String, EnergyEstimate>,
        after: Map<String, EnergyEstimate>,
        warnings: MutableList<String>,
    ): List<EnergyEstimate> =
        after
            .mapNotNull { (component, current) ->
                val old = before[component]
                if (old == null) {
                    warnings += "Energy component '$component' was absent from the baseline and cannot be diffed."
                    return@mapNotNull null
                }
                val currentMah = current.energyMah
                val oldMah = old.energyMah
                val currentUws = current.energyUws
                val oldUws = old.energyUws
                val mah = if (currentMah != null && oldMah != null) currentMah - oldMah else null
                val uws = if (currentUws != null && oldUws != null) currentUws - oldUws else null
                if ((currentMah != null && oldMah == null) || (currentUws != null && oldUws == null)) {
                    warnings += "Energy component '$component' changed units or was incomplete at baseline."
                }
                if ((mah != null && mah < 0) || (uws != null && uws < 0)) {
                    warnings += "The energy counter '$component' reset during the experiment."
                    null
                } else if (mah == null && uws == null) {
                    null
                } else if (mah == 0.0 && (uws == null || uws == 0L)) {
                    null
                } else {
                    current.copy(energyMah = mah, energyUws = uws)
                }
            }.sortedByDescending { it.energyMah ?: 0.0 }

    private fun diagnose(
        wakelocks: List<ResourceTimer>,
        durationMs: Long,
    ): List<String> =
        buildList {
            val totalWakelock = wakelocks.sumOf(ResourceTimer::durationMs)
            if (durationMs > 0 &&
                totalWakelock >= durationMs
            ) {
                add("Wakelock time is close to the experiment duration; inspect overlapping or unreleased locks.")
            }
        }

    private fun percentile(
        sorted: List<Double>,
        percentile: Double,
    ): Double = sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)]

    private companion object {
        const val TEMPERATURE_WARNING_TENTHS = 30
    }
}
