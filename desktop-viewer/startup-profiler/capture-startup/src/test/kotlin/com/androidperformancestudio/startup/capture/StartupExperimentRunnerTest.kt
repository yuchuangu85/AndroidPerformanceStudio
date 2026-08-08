@file:Suppress("MaxLineLength")

package com.androidperformancestudio.startup.capture

import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.StartupExperimentConfig
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.startup.model.StartupType
import java.nio.file.Files
import java.nio.file.Path
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
            assertEquals(
                false,
                result.runs
                    .first()
                    .environmentEvidence
                    ?.emulator,
            )
        }

    @Test
    fun `separates speed profile compilation warmup and records environment`() =
        kotlinx.coroutines.test.runTest {
            val commands = mutableListOf<List<String>>()
            val runner =
                StartupExperimentRunner(
                    serial = "device",
                    target = StartupTarget("dev.sample", "dev.sample/.MainActivity", false),
                    commandRunner =
                        object : StartupCommandRunner {
                            override suspend fun execute(arguments: List<String>): String {
                                commands += arguments
                                return when {
                                    arguments.take(2) == listOf("getprop", "ro.product.model") -> "Pixel Test"
                                    arguments.take(2) == listOf("getprop", "ro.build.version.sdk") -> "35"
                                    arguments.take(2) == listOf("getprop", "ro.kernel.qemu") -> "0"
                                    arguments.take(2) == listOf("dumpsys", "battery") -> "level: 80\nstatus: 2"
                                    arguments.take(2) == listOf("dumpsys", "thermalservice") -> "Thermal Status: 1"
                                    arguments.take(2) == listOf("dumpsys", "package") -> "[status=speed-profile] [reason=bg-dexopt]"
                                    arguments.first() == "pidof" -> "123"
                                    arguments.first() == "logcat" -> ""
                                    arguments.take(2) == listOf("am", "start") -> "Status: ok\nLaunchState: HOT\nTotalTime: 100"
                                    else -> "Success"
                                }
                            }
                        },
                    agentFactory = null,
                )

            val result =
                runner.run(
                    StartupExperimentConfig(
                        requestedType = StartupType.HOT,
                        compilationMode = CompilationMode.SPEED_PROFILE,
                        profileSource = StartupProfileSource.MACROBENCHMARK,
                        warmupRuns = 1,
                        measuredRuns = 1,
                    ),
                )

            assertEquals(2, commands.count { it.take(2) == listOf("am", "start") })
            assertTrue(commands.any { it.take(4) == listOf("cmd", "package", "compile", "--reset") })
            assertTrue(commands.any { "speed-profile" in it })
            assertEquals(
                "speed-profile",
                result.runs
                    .single()
                    .compilationEvidence
                    ?.compilerFilterAfter,
            )
            assertEquals(
                true,
                result.runs
                    .single()
                    .compilationEvidence
                    ?.profileSourceDeclared,
            )
            assertEquals(
                "Pixel Test",
                result.runs
                    .single()
                    .environmentEvidence
                    ?.deviceModel,
            )
        }

    @Test
    fun `parses compiler and thermal status evidence`() {
        assertEquals(CompilationSnapshot("speed-profile", "install"), parseCompilationSnapshot("[status=speed-profile] [reason=install]"))
        assertEquals(3, parseThermalStatus("mStatus=3"))
    }

    @Test
    fun `optionally saves one Perfetto trace per measured run`() =
        kotlinx.coroutines.test.runTest {
            val directory = Files.createTempDirectory("startup-trace")
            val runner =
                StartupExperimentRunner(
                    serial = "device",
                    target = StartupTarget("dev.sample", "dev.sample/.MainActivity", false),
                    commandRunner =
                        object : StartupCommandRunner {
                            override suspend fun execute(arguments: List<String>): String =
                                when {
                                    arguments.first() == "perfetto" -> "4321"
                                    arguments.take(2) == listOf("getprop", "ro.product.model") -> "Pixel"
                                    arguments.take(2) == listOf("getprop", "ro.build.version.sdk") -> "35"
                                    arguments.take(2) == listOf("getprop", "ro.kernel.qemu") -> "0"
                                    arguments.take(2) == listOf("dumpsys", "battery") -> "level: 80\nstatus: 2"
                                    arguments.take(2) == listOf("dumpsys", "thermalservice") -> "Status: 0"
                                    arguments.take(2) == listOf("dumpsys", "package") -> "[status=speed] [reason=install]"
                                    arguments.first() == "pidof" -> "123"
                                    arguments.first() == "logcat" -> ""
                                    arguments.take(2) == listOf("am", "start") -> "Status: ok\nLaunchState: COLD\nTotalTime: 100"
                                    else -> ""
                                }

                            override suspend fun pull(
                                remote: String,
                                local: Path,
                                timeoutSeconds: Int,
                            ) {
                                Files.write(local, byteArrayOf(1, 2, 3))
                            }
                        },
                    agentFactory = null,
                    traceDirectory = directory,
                )

            val trace =
                runner
                    .run(StartupExperimentConfig(measuredRuns = 1, capturePerfettoTrace = true))
                    .runs
                    .single()
                    .traceEvidence

            assertEquals(true, trace?.captured)
            assertTrue(Files.exists(Path.of(requireNotNull(trace?.file))))
        }
}
