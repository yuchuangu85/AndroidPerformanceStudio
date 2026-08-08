@file:Suppress("CyclomaticComplexMethod", "MagicNumber", "MaxLineLength", "ReturnCount", "TooManyFunctions")

package com.androidperformancestudio.battery.parser

import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryDeviceState
import com.androidperformancestudio.battery.model.BatteryHistoryEvent
import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import com.androidperformancestudio.battery.model.EnergyEstimate
import com.androidperformancestudio.battery.model.EnergyEvidenceKind
import com.androidperformancestudio.battery.model.EvidenceConfidence
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.battery.model.UidBatteryStats

public data class ParsedBatteryStats(
    val statsPeriodId: String?,
    val uidStats: UidBatteryStats,
    val history: List<BatteryHistoryEvent>,
    val warnings: List<String>,
)

public class BatteryStatsParser {
    public fun parse(
        checkin: String,
        report: String,
        battery: String,
        targetUid: Int,
    ): ParsedBatteryStats {
        val warnings = mutableListOf<String>()
        if (battery.isBlank()) warnings += "Battery device state output was empty."
        val records = checkin.lineSequence().mapIndexedNotNull { index, line -> parseRecord(index + 1, line, warnings) }.toList()
        val uidRecords = records.filter { it.uid == targetUid && (it.aggregation == "l" || it.aggregation.isEmpty()) }
        val wakelocks = timers(uidRecords, setOf("wl", "kwl", "wfl"), "wakelock", warnings)
        val alarms = timers(uidRecords, setOf("apk", "wua", "wa"), "alarm", warnings)
        val jobs = timers(uidRecords, setOf("jb", "job"), "job", warnings)
        val sensors = timers(uidRecords, setOf("sr", "sensor"), "sensor", warnings)
        val network = uidRecords.firstOrNull { it.type == "nt" }?.let(::parseNetwork) ?: NetworkUsage()
        val energy = parseEnergy(report, targetUid, warnings)
        if ((wakelocks.keys + alarms.keys + jobs.keys).any { it.startsWith("*") }) {
            warnings += "Framework-mediated resource names were observed; UID attribution does not establish component ownership."
        }
        val history = records.filter { it.type == "h" }.mapNotNull { parseHistoryLine(it.raw) }
        val period =
            records
                .firstOrNull { it.type in PERIOD_TYPES }
                ?.fields
                ?.joinToString(":")
                ?.takeIf(String::isNotBlank)
        if (uidRecords.isEmpty()) warnings += "No checkin records were found for UID $targetUid."
        return ParsedBatteryStats(
            statsPeriodId = period,
            uidStats = UidBatteryStats(targetUid, wakelocks, alarms, jobs, sensors, network, energy),
            history = history,
            warnings = warnings.distinct(),
        )
    }

    public fun parseDeviceState(output: String): BatteryDeviceState {
        val values =
            output
                .lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
                }.toMap()
        val powered =
            listOf("ac powered", "usb powered", "wireless powered", "dock powered")
                .mapNotNull { values[it]?.toBooleanStrictOrNull() }
                .takeIf(List<Boolean>::isNotEmpty)
                ?.any { it }
        return BatteryDeviceState(
            levelPercent = values["level"]?.toIntOrNull(),
            temperatureTenthsCelsius = values["temperature"]?.toIntOrNull(),
            voltageMillivolts = values["voltage"]?.toIntOrNull(),
            powered = powered,
            status = values["status"],
            rawValues = values,
        )
    }

    public fun parseHistory(output: String): List<BatteryHistoryEvent> =
        output.lineSequence().mapNotNull { line -> parseHistoryLine(line.trim()) }.toList()

    private fun timers(
        records: List<CheckinRecord>,
        types: Set<String>,
        fallbackName: String,
        warnings: MutableList<String>,
    ): Map<String, ResourceTimer> {
        val result = linkedMapOf<String, ResourceTimer>()
        records.filter { it.type in types }.forEach { record ->
            val nameIndex = record.fields.indexOfFirst { it.isNotBlank() && it.toLongOrNull() == null && it !in TIMER_MARKERS }
            val name = record.fields.getOrNull(nameIndex)?.takeIf(String::isNotBlank) ?: "$fallbackName@${record.lineNumber}"
            val values = record.fields.drop((nameIndex + 1).coerceAtLeast(0)).mapNotNull(String::toLongOrNull)
            if (values.isEmpty()) {
                warnings += "${record.type} record at line ${record.lineNumber} did not contain timer values."
                return@forEach
            }
            val durationUs = values.filterIndexed { index, _ -> index % TIMER_SECTION_WIDTH == 0 }.sum()
            val count =
                values.filterIndexed { index, _ -> index % TIMER_SECTION_WIDTH == 1 }.sum().takeIf { it > 0 } ?: values.getOrElse(1) { 0 }
            val current = ResourceTimer(name, durationUs / MICROSECONDS_PER_MILLISECOND, count)
            result[name] =
                result[name]?.let { previous ->
                    previous.copy(
                        durationMs = previous.durationMs + current.durationMs,
                        count =
                            previous.count + current.count,
                    )
                }
                    ?: current
        }
        return result
    }

    private fun parseNetwork(record: CheckinRecord): NetworkUsage {
        val value = record.fields.map { it.toLongOrNull() ?: 0L }
        return NetworkUsage(
            mobileRxBytes = value.getOrElse(0) { 0 },
            mobileTxBytes = value.getOrElse(1) { 0 },
            wifiRxBytes = value.getOrElse(2) { 0 },
            wifiTxBytes = value.getOrElse(3) { 0 },
            bluetoothRxBytes = value.getOrElse(4) { 0 },
            bluetoothTxBytes = value.getOrElse(5) { 0 },
            mobileRxPackets = value.getOrElse(6) { 0 },
            mobileTxPackets = value.getOrElse(7) { 0 },
            wifiRxPackets = value.getOrElse(8) { 0 },
            wifiTxPackets = value.getOrElse(9) { 0 },
            mobileRadioActiveMs = value.getOrElse(10) { 0 } / MICROSECONDS_PER_MILLISECOND,
        )
    }

    private fun parseEnergy(
        report: String,
        targetUid: Int,
        warnings: MutableList<String>,
    ): Map<String, EnergyEstimate> {
        val result = linkedMapOf<String, EnergyEstimate>()
        report.lineSequence().forEach { line ->
            val uidMatch = UID_ENERGY.find(line) ?: return@forEach
            if (uidMatch.androidUid() != targetUid) return@forEach
            val total = uidMatch.groupValues[5].toDoubleOrNull()
            if (total != null) result["total"] = modeledEnergy("total", total)
            COMPONENT_ENERGY.findAll(line).forEach { match ->
                val value = match.groupValues[2].toDoubleOrNull() ?: return@forEach
                result[match.groupValues[1]] = modeledEnergy(match.groupValues[1], value)
            }
        }
        if (report.contains("Estimated power", ignoreCase = true) && result.isEmpty()) {
            warnings += "Estimated power was present, but no unambiguous value could be attributed to UID $targetUid."
        }
        return result
    }

    private fun modeledEnergy(
        component: String,
        value: Double,
    ): EnergyEstimate =
        EnergyEstimate(
            component,
            energyMah = value,
            source = EnergyEvidenceKind.SYSTEM_MODEL,
            attributionScope = AttributionScope.UID,
            confidence = EvidenceConfidence.MODELED,
        )

    private fun MatchResult.androidUid(): Int? {
        groupValues[4].toIntOrNull()?.let { return it }
        val userId = groupValues[2].toIntOrNull() ?: 0
        val appId = groupValues[3].toIntOrNull() ?: return null
        return userId * PER_USER_UID_RANGE + FIRST_APPLICATION_UID + appId
    }

    private fun parseRecord(
        lineNumber: Int,
        line: String,
        warnings: MutableList<String>,
    ): CheckinRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val columns = splitCsv(trimmed)
        if (columns.size < 4) {
            if (trimmed.contains(',')) warnings += "Ignored malformed checkin line $lineNumber."
            return null
        }
        val uid = columns[1].toIntOrNull()
        val type = columns[3].trim().lowercase()
        if (uid == null && type != "h") return null
        return CheckinRecord(lineNumber, uid, columns[2].trim().lowercase(), type, columns.drop(4), trimmed)
    }

    private fun parseHistoryLine(line: String): BatteryHistoryEvent? {
        if (!line.contains(",h,") && !line.startsWith("h,")) return null
        val elapsed =
            HISTORY_TIME
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        val marker = HISTORY_MARKER.find(line)
        val token =
            marker
                ?.groupValues
                ?.get(2)
                ?.lowercase()
                .orEmpty()
        val kind =
            when {
                "wake" in token -> BatteryHistoryEventKind.WAKELOCK
                "alarm" in token -> BatteryHistoryEventKind.ALARM
                "job" in token -> BatteryHistoryEventKind.JOB
                "sensor" in token -> BatteryHistoryEventKind.SENSOR
                "wifi" in token || "network" in token || "mobile" in token -> BatteryHistoryEventKind.NETWORK
                "screen" in token -> BatteryHistoryEventKind.SCREEN
                "charge" in token || "plug" in token -> BatteryHistoryEventKind.CHARGING
                "temp" in token || "thermal" in token -> BatteryHistoryEventKind.THERMAL
                "top" in token || "proc" in token -> BatteryHistoryEventKind.APP_STATE
                else -> BatteryHistoryEventKind.UNKNOWN
            }
        val uid =
            HISTORY_UID
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        return BatteryHistoryEvent(
            elapsedMs = elapsed,
            kind = kind,
            active = marker?.groupValues?.get(1)?.let { it == "+" },
            name = marker?.groupValues?.get(2),
            uid = uid,
            raw = line,
            confidence = if (marker == null) EvidenceConfidence.INFERRED else EvidenceConfidence.EXACT,
        )
    }

    private fun splitCsv(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
            index++
        }
        values += current.toString()
        return values
    }

    private data class CheckinRecord(
        val lineNumber: Int,
        val uid: Int?,
        val aggregation: String,
        val type: String,
        val fields: List<String>,
        val raw: String,
    )

    private companion object {
        const val MICROSECONDS_PER_MILLISECOND = 1_000
        const val TIMER_SECTION_WIDTH = 5
        const val PER_USER_UID_RANGE = 100_000
        const val FIRST_APPLICATION_UID = 10_000
        val TIMER_MARKERS = setOf("f", "p", "w", "b")
        val PERIOD_TYPES = setOf("start")
        val UID_ENERGY = Regex("(?i)\\b(?:Uid|UID)\\s+((?:u(\\d+)?a(\\d+))|(\\d+))\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
        val COMPONENT_ENERGY = Regex("([A-Za-z][A-Za-z0-9_-]*)=([0-9]+(?:\\.[0-9]+)?)")
        val HISTORY_TIME = Regex("(?:^|,)\\s*(\\d+)[:;,]")
        val HISTORY_MARKER = Regex("([+-])([A-Za-z][A-Za-z0-9_.:/-]*(?:=[^, ]+)?)")
        val HISTORY_UID = Regex("(?:uid=|u)(\\d+)")
    }
}
