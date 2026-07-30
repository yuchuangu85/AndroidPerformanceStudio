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
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.UidBatteryStats
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

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
        }
    }
}
