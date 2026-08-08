@file:Suppress("MaxLineLength")

package com.androidperformancestudio.battery.capture

import com.androidperformancestudio.battery.model.BatteryCaptureMode
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryTarget
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatteryExperimentRunnerTest {
    @Test
    fun `online polling captures only lightweight battery state between full endpoints`() =
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
            val active = runner.start(BatteryExperimentConfig(mode = BatteryCaptureMode.ONLINE))
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
            assertEquals(2, commands.count { it == listOf("dumpsys", "batterystats", "--checkin") })
            assertTrue(
                result.runs
                    .single()
                    .samples
                    .single()
                    .rawEvidence.checkin
                    .isEmpty(),
            )
        }

    @Test
    fun `parses portable experiment conditions`() {
        assertEquals("ON", parseScreenState("Display Power State:\n mScreenState=ON"))
        assertEquals(
            "WIFI",
            parseDefaultNetworkTransport(
                "Active default network: 101\nNetworkAgentInfo{Network{101} Transports: WIFI Capabilities: INTERNET}",
            ),
        )
    }

    @Test
    fun `repeated experiments cool down only between runs`() =
        runTest {
            val progress = mutableListOf<String>()
            var completedRuns = 0
            val runner =
                BatteryExperimentRunner(
                    Path.of("adb"),
                    "serial",
                    BatteryTarget("example.app", 10123),
                    BatteryCommandRunner { arguments ->
                        when (arguments) {
                            listOf("dumpsys", "batterystats", "--help") -> "--checkin --history"
                            listOf("cat", "/proc/sys/kernel/random/boot_id") -> "boot"
                            listOf("dumpsys", "battery") -> "level: 80\ntemperature: 300\nUSB powered: false"
                            listOf("dumpsys", "batterystats") -> "Battery History"
                            listOf("dumpsys", "batterystats", "--history") -> ""
                            listOf("dumpsys", "batterystats", "--checkin") -> "9,10123,l,nt,0,0,0,0,0,0,0,0,0,0,0"
                            else -> error("unsupported")
                        }
                    },
                )

            val result =
                runner.run(
                    BatteryExperimentConfig(
                        mode = BatteryCaptureMode.REPEATED,
                        durationSeconds = 5,
                        pollingIntervalSeconds = 5,
                        measuredRuns = 2,
                        cooldownSeconds = 30,
                    ),
                    onProgress = { progress += it.message },
                    onRunCompleted = { _, _ -> completedRuns++ },
                )

            assertEquals(2, result.runs.size)
            assertEquals(2, completedRuns)
            assertEquals(1, progress.count { "Cooling down" in it })
        }
}
