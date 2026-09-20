@file:Suppress("TooGenericExceptionCaught", "ReturnCount")

package com.androidperformancestudio.benchmark.cli

import com.androidperformancestudio.benchmark.analysis.RegressionAnalyzer
import com.androidperformancestudio.benchmark.export.BenchmarkReportExporter
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.parser.BenchmarkJsonParser
import java.nio.file.Path
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    if (args.firstOrNull() == "macro-plan" || args.firstOrNull() == "macro-run") {
        runMacrobenchmarkCommand(args.drop(1), execute = args.first() == "macro-run")
        return
    }
    val options = args.toList().chunked(2).associate { pair -> pair.first() to pair.getOrElse(1) { "" } }
    val current = options["--current"] ?: return usage()
    val baseline = options["--baseline"] ?: return usage()
    val threshold = options["--threshold-percent"]?.toDoubleOrNull()
    try {
        val parser = BenchmarkJsonParser()
        val report =
            RegressionAnalyzer().compare(
                parser.parse(Path.of(baseline)),
                parser.parse(Path.of(current)),
                RegressionPolicy(relativeThresholdPercent = threshold),
            )
        val exporter = BenchmarkReportExporter()
        options["--json"]?.let { exporter.writeJson(report, Path.of(it)) }
        options["--junit"]?.let { exporter.writeJunit(report, Path.of(it)) }
        options["--sarif"]?.let { exporter.writeSarif(report, Path.of(it)) }
        report.comparisons.forEach {
            println(
                "${it.classification}\t${it.caseIdentity}\t${it.metricName}\t${it.relativeDeltaPercent ?: "n/a"}%",
            )
        }
        exitProcess(if (report.regressionCount > 0) 1 else 0)
    } catch (failure: Exception) {
        System.err.println(failure.message ?: failure.javaClass.simpleName)
        exitProcess(2)
    }
}

private fun runMacrobenchmarkCommand(args: List<String>, execute: Boolean) {
    val options = args.chunked(2).associate { pair -> pair.first() to pair.getOrElse(1) { "" } }
    val project = options["--project"]?.takeIf(String::isNotBlank)
        ?: return usageMacrobenchmark()
    try {
        val plan =
            MacrobenchmarkExperimentPlan(
                projectRoot = Path.of(project),
                gradleTask = options["--task"]?.ifBlank { null } ?: ":macrobenchmark:connectedCheck",
                benchmarkFilter = options["--filter"],
                compilationMode =
                    options["--compilation-mode"]?.uppercase()?.let(MacrobenchmarkCompilationMode::valueOf)
                        ?: MacrobenchmarkCompilationMode.NONE,
                startupMode =
                    options["--startup-mode"]?.uppercase()?.let(MacrobenchmarkStartupMode::valueOf)
                        ?: MacrobenchmarkStartupMode.COLD,
                warmups = options["--warmups"]?.toIntOrNull() ?: 2,
                iterations = options["--iterations"]?.toIntOrNull() ?: 5,
                traceDirectory = options["--trace-dir"]?.takeIf(String::isNotBlank)?.let(Path::of),
            )
        val command = MacrobenchmarkCommandBuilder.build(plan)
        if (!execute) {
            println(command.commandLine())
            command.environment.forEach { (key, value) -> println("$key=$value") }
            return
        }
        val result = MacrobenchmarkProcessRunner(options["--timeout-seconds"]?.toLongOrNull() ?: 3600L).run(command)
        print(result.output)
        if (result.timedOut) {
            System.err.println("Macrobenchmark timed out")
            exitProcess(124)
        }
        if (result.exitCode == 0) {
            options["--result"]?.takeIf(String::isNotBlank)?.let { resultPath ->
                val current = BenchmarkJsonParser().parse(Path.of(resultPath))
                println("macrobenchmark_cases=${current.cases.size}")
                options["--baseline"]?.takeIf(String::isNotBlank)?.let { baselinePath ->
                    val report =
                        RegressionAnalyzer().compare(
                            BenchmarkJsonParser().parse(Path.of(baselinePath)),
                            current,
                            RegressionPolicy(relativeThresholdPercent = options["--threshold-percent"]?.toDoubleOrNull()),
                        )
                    options["--report-json"]?.let { output -> BenchmarkReportExporter().writeJson(report, Path.of(output)) }
                    println("macrobenchmark_regressions=${report.regressionCount}")
                    if (report.regressionCount > 0) exitProcess(1)
                }
            }
        }
        exitProcess(result.exitCode)
    } catch (failure: Exception) {
        System.err.println(failure.message ?: failure.javaClass.simpleName)
        exitProcess(2)
    }
}

private fun usageMacrobenchmark() {
    System.err.println(
        "Usage: aps-benchmark macro-plan|macro-run --project DIR [--task TASK] " +
            "[--filter CLASS#METHOD] [--compilation-mode NONE|PARTIAL|FULL|SPEED_PROFILE] " +
            "[--startup-mode COLD|WARM|HOT] [--warmups N] [--iterations N] [--trace-dir DIR] " +
            "[--result BENCHMARK_JSON] [--baseline BENCHMARK_JSON] [--report-json REPORT_JSON]",
    )
    exitProcess(2)
}

private fun usage() {
    System.err.println(
        "Usage: aps-benchmark --current FILE --baseline FILE [--threshold-percent N] [--json FILE] [--junit FILE] [--sarif FILE]",
    )
}
