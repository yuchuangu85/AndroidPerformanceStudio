@file:Suppress("MaxLineLength")

package com.androidperformancestudio.startup.capture

import com.androidperformancestudio.startup.model.StartupExperimentConfig
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.startup.model.StartupType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupExperimentRunnerTest {
    @Test
    fun `runs repeatable cold startup without agent`() =
        kotlinx.coroutines.test.runTest {
            val commands = mutableListOf<List<String>>()
            val startupTimeouts = mutableListOf<Int>()
            val runner =
                StartupExperimentRunner(
                    serial = "device",
                    target = StartupTarget("dev.sample", "dev.sample/.MainActivity", false),
                    commandRunner =
                        object : StartupCommandRunner {
                            override suspend fun execute(arguments: List<String>): String {
                                commands += arguments
                                return when (arguments.first()) {
                                    "pidof" -> "123"
                                    "logcat" -> ""
                                    "am" ->
                                        if (arguments.getOrNull(1) ==
                                            "start"
                                        ) {
                                            "Status: ok\nLaunchState: COLD\nTotalTime: 200\nWaitTime: 210"
                                        } else {
                                            ""
                                        }
                                    else -> ""
                                }
                            }

                            override suspend fun execute(
                                arguments: List<String>,
                                timeoutSeconds: Int,
                            ): String {
                                startupTimeouts += timeoutSeconds
                                return execute(arguments)
                            }
                        },
                    agentFactory = null,
                )

            val result =
                runner.run(
                    StartupExperimentConfig(requestedType = StartupType.COLD, measuredRuns = 2, timeoutSeconds = 17),
                )

            assertEquals(2, result.runs.size)
            assertTrue(commands.count { it.take(2) == listOf("am", "force-stop") } == 2)
            assertEquals(listOf(17, 17), startupTimeouts)
            assertEquals(StartupType.COLD, result.runs.first().observedType)
        }
}
