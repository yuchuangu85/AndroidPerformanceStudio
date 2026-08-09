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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class BatteryExportersTest {
    @Test
    @Suppress("LongMethod")
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
                conditions = mapOf("screenState" to "ON"),
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
        BatteryCsvExporter().export(analysis, csv, AttributionScope.SHARED_UID)
        BatteryRawBundleExporter().export(experiment, zip)

        assertContains(Files.readString(json), "\"schemaVersion\": 1")
        assertContains(Files.readString(json), "\"deviceLocalId\"")
        kotlin.test.assertFalse(Files.readString(json).contains("\"deviceSerial\""))
        assertContains(Files.readString(csv), "wakelock")
        assertContains(Files.readString(csv), "SHARED_UID")
        ZipFile(zip.toFile()).use {
            assertNotNull(it.getEntry("run-1/snapshot-0/checkin.txt"))
            assertNotNull(it.getEntry("run-1/snapshot-0/conditions.txt"))
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `imports exported json as a reusable analysis`() {
        val directory = Files.createTempDirectory("battery-import")
        val session =
            BatterySession(
                "session",
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
                "baseline",
                "session",
                0,
                Instant.EPOCH,
                null,
                "boot",
                UidBatteryStats(10123),
                BatteryDeviceState(),
                rawEvidence = BatteryRawEvidence("", "", ""),
            )
        val run = BatteryRun("run", "session", 1, snapshot, emptyList(), snapshot.copy(id = "final", sequence = 1))
        val delta =
            BatteryRunDelta(
                "run",
                "session",
                1,
                2_000,
                listOf(ResourceTimer("lock", 20, 2)),
                emptyList(),
                emptyList(),
                emptyList(),
                NetworkUsage(wifiRxBytes = 42),
                emptyList(),
                emptyList(),
                listOf("run warning"),
            )
        val statistics = BatteryStatistics(1, 0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0)
        val analysis =
            BatteryAnalysisResult(
                listOf(delta),
                statistics,
                statistics,
                statistics,
                statistics,
                statistics,
                statistics,
                listOf("analysis warning"),
            )
        val json = directory.resolve("report.json")
        BatteryJsonExporter().export(BatteryExperimentResult(session, listOf(run)), analysis, json)

        val imported = BatteryJsonImporter().import(json)

        assertEquals(1, imported.runs.size)
        assertEquals("run", imported.runs.single().runId)
        assertEquals(
            20,
            imported.runs
                .single()
                .wakelocks
                .single()
                .durationMs,
        )
        assertEquals(
            42,
            imported.runs
                .single()
                .network.totalBytes,
        )
        assertEquals(listOf("analysis warning"), imported.warnings)
        assertEquals(20.0, imported.wakelockDurationMs.mean)
        assertEquals(42.0, imported.networkBytes.mean)
    }

    @Test
    fun `rejects unsupported json schema`() {
        val json = Files.createTempFile("battery-import-unsupported", ".json")
        Files.writeString(json, """{"schemaVersion":2,"sessionId":"s","runs":[]}""")

        assertFailsWith<IllegalArgumentException> { BatteryJsonImporter().import(json) }
    }
}
