@file:Suppress("MaxLineLength")

package com.androidperformancestudio.battery.capture

import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryTarget
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatteryExperimentRunnerTest {
    @Test
    fun `interactive experiment captures baseline poll and final history`() =
        runTest {
            val commands = mutableListOf<List<String>>()
            var checkin = 0
            val runner =
                BatteryExperimentRunner(
                    Path.of("adb"),
                    "serial",
                    BatteryTarget("example.app", 10123),
                    BatteryCommandRunner { arguments ->
                        commands += arguments
                        when {
                            arguments == listOf("dumpsys", "batterystats", "--help") -> "--checkin --history --reset"
                            arguments == listOf("cat", "/proc/sys/kernel/random/boot_id") -> "boot"
                            arguments == listOf("dumpsys", "battery") -> "level: 80\ntemperature: 300\nUSB powered: false"
                            arguments == listOf("dumpsys", "batterystats") -> "Battery History"
                            arguments == listOf("dumpsys", "batterystats", "--history") -> "9,0,l,h,1,+job=sync,uid=10123"
                            arguments == listOf("dumpsys", "batterystats", "--checkin") -> {
                                checkin++
                                "9,10123,l,nt,${checkin * 10},0,0,0,0,0,0,0,0,0,0"
                            }
                            else -> error("Unexpected $arguments")
                        }
                    },
                )
            val active = runner.start(BatteryExperimentConfig())
            runner.poll(active)
            val result = runner.stop(active)

            assertEquals(1, result.runs.size)
            assertEquals(
                1,
                result.runs
                    .single()
                    .samples.size,
            )
            assertTrue(
                result.runs
                    .single()
                    .finalSnapshot.history
                    .isNotEmpty(),
            )
            assertTrue(commands.any { it.last() == "--history" })
        }
}
