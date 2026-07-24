package com.androidperformancestudio.battery.export

import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryCapabilities
import com.androidperformancestudio.battery.model.BatteryCapabilityLevel
import com.androidperformancestudio.battery.model.BatteryDeviceState
import com.androidperformancestudio.battery.model.BatteryEnvironment
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryExperimentResult
import com.androidperformancestudio.battery.model.BatteryRawEvidence
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.BatteryStatistics
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.battery.model.UidBatteryStats
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class BatteryExportersTest {
    @Test
    fun `exports json csv and raw evidence bundle`() {
        val directory = Files.createTempDirectory("battery-export")
        val session =
            BatterySession(
                "s",
                "serial",
                "pkg",
                10123,
                AttributionScope.UID,
                BatteryExperimentConfig(),
                BatteryCapabilities(BatteryCapabilityLevel.RESOURCE_BASIC, true, false, false, false, true),
                BatteryEnvironment(BatteryDeviceState()),
                Instant.EPOCH,
            )
        val snapshot =
            BatterySnapshot(
                "b",
                "s",
                0,
                Instant.EPOCH,
                null,
                "boot",
                UidBatteryStats(10123),
                BatteryDeviceState(),
                rawEvidence = BatteryRawEvidence("checkin", "report", "battery"),
            )
        val run = BatteryRun("r", "s", 1, snapshot, emptyList(), snapshot.copy(id = "f", sequence = 1))
        val delta =
            BatteryRunDelta(
                "r",
                "s",
                1,
                1000,
                listOf(ResourceTimer("lock", 10, 1)),
                emptyList(),
                emptyList(),
                emptyList(),
                NetworkUsage(wifiRxBytes = 12),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        val stats = BatteryStatistics(1, 0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0)
        val analysis = BatteryAnalysisResult(listOf(delta), stats, stats, stats, stats, stats, stats, emptyList())
        val experiment = BatteryExperimentResult(session, listOf(run))

        val json = directory.resolve("report.json")
        val csv = directory.resolve("report.csv")
        val zip = directory.resolve("raw.zip")
        BatteryJsonExporter().export(experiment, analysis, json)
        BatteryCsvExporter().export(analysis, csv)
        BatteryRawBundleExporter().export(experiment, zip)

        assertContains(Files.readString(json), "\"schemaVersion\": 1")
        assertContains(Files.readString(csv), "wakelock")
        ZipFile(zip.toFile()).use { assertNotNull(it.getEntry("run-1/snapshot-0/checkin.txt")) }
    }
}
