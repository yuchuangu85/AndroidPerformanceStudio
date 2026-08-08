package com.androidperformancestudio.battery.analysis

import com.androidperformancestudio.battery.model.BatteryDeviceState
import com.androidperformancestudio.battery.model.BatteryRawEvidence
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.battery.model.UidBatteryStats
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatteryAnalyzerTest {
    private val analyzer = BatteryAnalyzer()

    @Test
    fun `diffs cumulative counters and reports resets`() {
        val before = snapshot(0, ResourceTimer("lock", 100, 2), NetworkUsage(wifiRxBytes = 100))
        val after = snapshot(10, ResourceTimer("lock", 350, 5), NetworkUsage(wifiRxBytes = 90))
        val result = analyzer.diff(BatteryRun("run", "session", 1, before, emptyList(), after))

        assertEquals(250, result.wakelocks.single().durationMs)
        assertEquals(3, result.wakelocks.single().count)
        assertEquals(0, result.network.wifiRxBytes)
        assertTrue(result.warnings.any { "wifiRxBytes" in it })
    }

    @Test
    fun `computes distribution`() {
        assertEquals(3.0, analyzer.statistics(listOf(1.0, 3.0, 9.0)).median)
    }

    @Test
    fun `warns when experiment conditions change`() {
        val before = snapshot(0, ResourceTimer("lock", 0, 0), NetworkUsage()).copy(conditions = mapOf("screenState" to "ON"))
        val after = snapshot(10, ResourceTimer("lock", 0, 0), NetworkUsage()).copy(conditions = mapOf("screenState" to "OFF"))

        val result = analyzer.diff(BatteryRun("run", "session", 1, before, emptyList(), after))

        assertTrue(result.warnings.any { "screenState" in it })
    }

    @Test
    fun `warns when repeated run baseline temperature drifts`() {
        val first =
            snapshot(
                0,
                ResourceTimer("lock", 0, 0),
                NetworkUsage(),
            ).copy(deviceState = BatteryDeviceState(temperatureTenthsCelsius = 300))
        val second =
            snapshot(
                20,
                ResourceTimer("lock", 0, 0),
                NetworkUsage(),
            ).copy(deviceState = BatteryDeviceState(temperatureTenthsCelsius = 331))

        val result =
            analyzer.analyze(
                listOf(
                    BatteryRun(
                        "run-1",
                        "session",
                        1,
                        first,
                        emptyList(),
                        first.copy(id = "final-1", capturedAt = first.capturedAt.plusSeconds(10)),
                    ),
                    BatteryRun(
                        "run-2",
                        "session",
                        2,
                        second,
                        emptyList(),
                        second.copy(id = "final-2", capturedAt = second.capturedAt.plusSeconds(10)),
                    ),
                ),
            )

        assertTrue(result.warnings.any { "baseline temperature" in it })
    }

    private fun snapshot(
        seconds: Long,
        timer: ResourceTimer,
        network: NetworkUsage,
    ): BatterySnapshot =
        BatterySnapshot(
            id = "s$seconds",
            sessionId = "session",
            sequence = seconds.toInt(),
            capturedAt = Instant.EPOCH.plusSeconds(seconds),
            statsPeriodId = "period",
            bootId = "boot",
            uidStats = UidBatteryStats(10123, wakelocks = mapOf(timer.name to timer), network = network),
            deviceState = BatteryDeviceState(powered = false),
            rawEvidence = BatteryRawEvidence("", "", ""),
        )
}
