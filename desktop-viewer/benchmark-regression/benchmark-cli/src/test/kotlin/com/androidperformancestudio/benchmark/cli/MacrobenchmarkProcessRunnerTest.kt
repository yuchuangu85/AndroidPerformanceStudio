package com.androidperformancestudio.benchmark.cli

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class MacrobenchmarkProcessRunnerTest {
    @Test
    fun `enforces timeout while process is still running`() {
        val root = Files.createTempDirectory("macrobenchmark-runner")
        val wrapper = root.resolve("gradlew")
        wrapper.writeText("#!/bin/sh\necho started\nsleep 3\n")
        wrapper.toFile().setExecutable(true)
        val command = MacrobenchmarkCommandBuilder.build(MacrobenchmarkExperimentPlan(root))

        val result = MacrobenchmarkProcessRunner(timeoutSeconds = 1).run(command)

        assertTrue(result.timedOut)
        assertTrue(result.output.contains("started"))
    }
}
