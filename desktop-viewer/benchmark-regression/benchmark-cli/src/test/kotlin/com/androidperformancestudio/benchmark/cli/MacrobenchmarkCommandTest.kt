package com.androidperformancestudio.benchmark.cli

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MacrobenchmarkCommandTest {
    @Test
    fun `builds reproducible connected benchmark command with compilation and trace properties`() {
        val root = Files.createTempDirectory("macrobenchmark-project")
        root.resolve("gradlew").writeText("#!/bin/sh")
        val plan =
            MacrobenchmarkExperimentPlan(
                projectRoot = root,
                gradleTask = ":macrobenchmark:connectedCheck",
                benchmarkFilter = "com.example.Benchmark#startup",
                compilationMode = MacrobenchmarkCompilationMode.SPEED_PROFILE,
                startupMode = MacrobenchmarkStartupMode.WARM,
                warmups = 3,
                iterations = 7,
                traceDirectory = root.resolve("traces"),
            )

        val command = MacrobenchmarkCommandBuilder.build(plan)

        assertEquals(root, command.workingDirectory)
        assertContains(command.arguments, "-Paps.macrobenchmark.compilationMode=speed_profile")
        assertContains(command.arguments, "-Paps.macrobenchmark.startupMode=warm")
        assertContains(command.arguments, "-Paps.macrobenchmark.warmups=3")
        assertContains(command.arguments, "-Paps.macrobenchmark.iterations=7")
        assertContains(command.commandLine(), "com.example.Benchmark#startup")
        assertEquals("speed_profile", command.environment["APS_MACROBENCHMARK_COMPILATION_MODE"])
    }
}
