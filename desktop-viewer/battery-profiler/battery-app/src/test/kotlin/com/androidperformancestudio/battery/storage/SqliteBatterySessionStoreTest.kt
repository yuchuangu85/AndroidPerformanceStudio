@file:Suppress("MaxLineLength")

package com.androidperformancestudio.battery.storage

import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryCapabilities
import com.androidperformancestudio.battery.model.BatteryCapabilityLevel
import com.androidperformancestudio.battery.model.BatteryDeviceState
import com.androidperformancestudio.battery.model.BatteryEnvironment
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryRawEvidence
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.battery.model.BatterySessionStatus
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.UidBatteryStats
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SqliteBatterySessionStoreTest {
    @Test
    fun `persists session run snapshots and delta`() {
        val file = Files.createTempDirectory("battery-store").resolve("battery.db")
        val capabilities = BatteryCapabilities(BatteryCapabilityLevel.RESOURCE_BASIC, true, false, false, false, true)
        val session =
            BatterySession(
                "session",
                "serial",
                "pkg",
                10123,
                AttributionScope.UID,
                BatteryExperimentConfig(),
                capabilities,
                BatteryEnvironment(BatteryDeviceState()),
                Instant.EPOCH,
            )
        val snapshot =
            BatterySnapshot(
                "snapshot",
                "session",
                0,
                Instant.EPOCH,
                "period",
                "boot",
                UidBatteryStats(10123),
                BatteryDeviceState(),
                rawEvidence = BatteryRawEvidence("c", "r", "b"),
            )
        val final = snapshot.copy(id = "final", sequence = 1, capturedAt = Instant.EPOCH.plusSeconds(10))
        val run = BatteryRun("run", "session", 1, snapshot, emptyList(), final)
        val delta =
            BatteryRunDelta(
                "run",
                "session",
                1,
                10_000,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                NetworkUsage(wifiRxBytes = 7),
                emptyList(),
                emptyList(),
                emptyList(),
            )

        SqliteBatterySessionStore.open(file).use { store ->
            store.save(session, listOf(run), listOf(delta))
            assertEquals(1, store.listSessions().single().runCount)
            assertEquals(BatterySessionStatus.COMPLETED, store.listSessions().single().status)
        }
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            val stored =
                connection.createStatement().executeQuery("SELECT device_serial FROM battery_sessions").use {
                    check(it.next())
                    it.getString(1)
                }
            assertNotEquals("serial", stored)
            assertEquals(64, stored.length)
        }
    }

    @Test
    fun `keeps completed runs when a running session is interrupted`() {
        val file = Files.createTempDirectory("battery-incremental-store").resolve("battery.db")
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
        val delta =
            BatteryRunDelta(
                "run-1",
                "session",
                1,
                1,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                NetworkUsage(),
                emptyList(),
                emptyList(),
                emptyList(),
            )

        SqliteBatterySessionStore.open(file).use { store ->
            store.begin(session)
            store.saveRun(session, BatteryRun("run-1", "session", 1, snapshot, emptyList(), snapshot.copy(id = "final")), delta)
            store.saveRun(
                session,
                BatteryRun("run-2", "session", 2, snapshot.copy(id = "baseline-2"), emptyList(), snapshot.copy(id = "final-2")),
                delta.copy(runId = "run-2", iteration = 2),
            )
            store.markStatus("session", BatterySessionStatus.INTERRUPTED)

            assertEquals(2, store.listSessions().single().runCount)
            assertEquals(BatterySessionStatus.INTERRUPTED, store.listSessions().single().status)
        }
    }

    @Test
    fun `migrates existing database without rebuilding tables`() {
        val file = Files.createTempDirectory("battery-old-store").resolve("battery.db")
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE battery_sessions (id TEXT PRIMARY KEY, device_serial TEXT NOT NULL, package_name TEXT NOT NULL, uid INTEGER NOT NULL, attribution_scope TEXT NOT NULL, capture_mode TEXT NOT NULL, capability_level TEXT NOT NULL, created_at TEXT NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE battery_runs (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, iteration INTEGER NOT NULL, started_at TEXT NOT NULL, ended_at TEXT NOT NULL, sample_count INTEGER NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE battery_snapshots (id TEXT PRIMARY KEY, run_id TEXT NOT NULL, sequence INTEGER NOT NULL, captured_at TEXT NOT NULL, stats_period_id TEXT, boot_id TEXT, level INTEGER, temperature INTEGER, powered INTEGER, checkin TEXT NOT NULL, report TEXT NOT NULL, battery TEXT NOT NULL, history TEXT, warnings TEXT NOT NULL)",
                )
            }
        }

        SqliteBatterySessionStore.open(file).close()

        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            assertEquals(true, connection.hasColumnForTest("battery_sessions", "status"))
            assertEquals(true, connection.hasColumnForTest("battery_snapshots", "conditions"))
        }
    }
}

private fun java.sql.Connection.hasColumnForTest(
    table: String,
    column: String,
): Boolean =
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { rows ->
            generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == column }
        }
    }
