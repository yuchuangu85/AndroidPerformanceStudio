@file:Suppress("SpreadOperator")

package com.androidperformancestudio.benchmark.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

public enum class MacrobenchmarkCompilationMode {
    NONE,
    PARTIAL,
    FULL,
    SPEED_PROFILE,
}

public enum class MacrobenchmarkStartupMode {
    COLD,
    WARM,
    HOT,
}

public data class MacrobenchmarkExperimentPlan(
    val projectRoot: Path,
    val gradleTask: String = ":macrobenchmark:connectedCheck",
    val benchmarkFilter: String? = null,
    val compilationMode: MacrobenchmarkCompilationMode = MacrobenchmarkCompilationMode.NONE,
    val startupMode: MacrobenchmarkStartupMode = MacrobenchmarkStartupMode.COLD,
    val warmups: Int = 2,
    val iterations: Int = 5,
    val traceDirectory: Path? = null,
) {
    init {
        require(Files.isDirectory(projectRoot)) { "Macrobenchmark project root is not a directory: $projectRoot" }
        require(gradleTask.startsWith(":")) { "gradleTask must be a Gradle task path" }
        require(warmups in 0..100) { "warmups must be between 0 and 100" }
        require(iterations in 1..100) { "iterations must be between 1 and 100" }
    }
}

public data class MacrobenchmarkCommand(
    val workingDirectory: Path,
    val executable: Path,
    val arguments: List<String>,
    val environment: Map<String, String>,
) {
    public fun commandLine(): String =
        listOf(executable.toString(), *arguments.toTypedArray())
            .joinToString(" ") { value ->
                if (value.any(Char::isWhitespace)) "\"${value.replace("\"", "\\\"")}\"" else value
            }
}

public object MacrobenchmarkCommandBuilder {
    public fun build(plan: MacrobenchmarkExperimentPlan): MacrobenchmarkCommand {
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val wrapper = plan.projectRoot.resolve(if (windows) "gradlew.bat" else "gradlew")
        require(wrapper.exists()) { "Gradle wrapper not found: $wrapper" }
        val traceDirectory = plan.traceDirectory?.toAbsolutePath()?.normalize()
        val environment =
            buildMap {
                put("APS_MACROBENCHMARK_COMPILATION_MODE", plan.compilationMode.name.lowercase())
                put("APS_MACROBENCHMARK_STARTUP_MODE", plan.startupMode.name.lowercase())
                put("APS_MACROBENCHMARK_WARMUPS", plan.warmups.toString())
                put("APS_MACROBENCHMARK_ITERATIONS", plan.iterations.toString())
                traceDirectory?.let { put("APS_MACROBENCHMARK_TRACE_DIR", it.toString()) }
            }
        val arguments =
            buildList {
                add(plan.gradleTask)
                add("--no-daemon")
                add("-Pandroidx.benchmark.output.enable=true")
                add("-Paps.macrobenchmark.compilationMode=${plan.compilationMode.name.lowercase()}")
                add("-Paps.macrobenchmark.startupMode=${plan.startupMode.name.lowercase()}")
                add("-Paps.macrobenchmark.warmups=${plan.warmups}")
                add("-Paps.macrobenchmark.iterations=${plan.iterations}")
                plan.benchmarkFilter?.let { add("-Pandroid.testInstrumentationRunnerArguments.class=$it") }
                traceDirectory?.let { add("-Paps.macrobenchmark.traceDirectory=$it") }
            }
        return MacrobenchmarkCommand(plan.projectRoot, wrapper, arguments, environment)
    }
}
