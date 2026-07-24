@file:Suppress("TooGenericExceptionCaught")

package com.androidperformancestudio.benchmark.cli

import com.androidperformancestudio.benchmark.analysis.RegressionAnalyzer
import com.androidperformancestudio.benchmark.export.BenchmarkReportExporter
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.parser.BenchmarkJsonParser
import java.nio.file.Path
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
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

private fun usage() {
    System.err.println(
        "Usage: aps-benchmark --current FILE --baseline FILE [--threshold-percent N] [--json FILE] [--junit FILE] [--sarif FILE]",
    )
}
