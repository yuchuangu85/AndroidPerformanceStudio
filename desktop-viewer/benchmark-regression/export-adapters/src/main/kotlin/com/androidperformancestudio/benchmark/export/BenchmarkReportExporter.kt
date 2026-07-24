@file:Suppress("LongMethod", "MaxLineLength")

package com.androidperformancestudio.benchmark.export

import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.model.RegressionReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

public class BenchmarkReportExporter {
    private val json = Json { prettyPrint = true }

    public fun writeJson(
        report: RegressionReport,
        output: Path,
    ) = write(
        output,
        json.encodeToString(
            buildJsonObject {
                put("schemaVersion", 1)
                put("baselineRunId", report.baselineRunId)
                put("currentRunId", report.currentRunId)
                put("createdAt", report.createdAt.toString())
                put("regressionCount", report.regressionCount)
                put(
                    "comparisons",
                    buildJsonArray {
                        report.comparisons.forEach { comparison ->
                            add(
                                buildJsonObject {
                                    put("case", comparison.caseIdentity)
                                    put("metric", comparison.metricName)
                                    put("unit", comparison.unit)
                                    comparison.baselineValue?.let { put("baseline", it) }
                                    comparison.currentValue?.let { put("current", it) }
                                    comparison.absoluteDelta?.let { put("absoluteDelta", it) }
                                    comparison.relativeDeltaPercent?.let { put("relativeDeltaPercent", it) }
                                    put("classification", comparison.classification.name)
                                    put("confidence", comparison.confidence.name)
                                },
                            )
                        }
                    },
                )
            },
        ),
    )

    public fun writeCsv(
        report: RegressionReport,
        output: Path,
    ) = write(
        output,
        buildString {
            appendLine("case,metric,unit,baseline,current,absolute_delta,relative_delta_percent,classification,confidence")
            report.comparisons.forEach { c ->
                appendLine(
                    listOf(
                        c.caseIdentity,
                        c.metricName,
                        c.unit,
                        c.baselineValue,
                        c.currentValue,
                        c.absoluteDelta,
                        c.relativeDeltaPercent,
                        c.classification,
                        c.confidence,
                    ).joinToString(",") {
                        csv(it?.toString().orEmpty())
                    },
                )
            }
        },
    )

    public fun writeMarkdown(
        report: RegressionReport,
        output: Path,
    ) = write(
        output,
        buildString {
            appendLine("# Benchmark Regression Report")
            appendLine()
            appendLine("Regressions: **${report.regressionCount}**")
            appendLine()
            appendLine("| Case | Metric | Baseline | Current | Delta | Result |")
            appendLine("| --- | --- | ---: | ---: | ---: | --- |")
            report.comparisons.forEach { c ->
                appendLine(
                    "| ${c.caseIdentity} | ${c.metricName} (${c.unit}) | ${c.baselineValue ?: "—"} | ${c.currentValue ?: "—"} | ${c.relativeDeltaPercent?.let {
                        "%.2f%%"
                            .format(
                                it,
                            )
                    } ?: "—"} | ${c.classification} |",
                )
            }
        },
    )

    public fun writeJunit(
        report: RegressionReport,
        output: Path,
    ) = write(
        output,
        buildString {
            append(
                "<testsuite name=\"AndroidPerformanceStudio Benchmark\" tests=\"${report.comparisons.size}\" failures=\"${report.regressionCount}\">",
            )
            report.comparisons.forEach { c ->
                append("<testcase classname=\"${xml(c.caseIdentity)}\" name=\"${xml(c.metricName)}\">")
                if (c.classification ==
                    RegressionClassification.REGRESSED
                ) {
                    append("<failure message=\"Regression\">${xml(c.toString())}</failure>")
                }
                append("</testcase>")
            }
            append("</testsuite>")
        },
    )

    public fun writeSarif(
        report: RegressionReport,
        output: Path,
    ) = write(
        output,
        json.encodeToString(
            buildJsonObject {
                put("version", "2.1.0")
                put(
                    "runs",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "tool",
                                    buildJsonObject {
                                        put(
                                            "driver",
                                            buildJsonObject { put("name", "AndroidPerformanceStudio Benchmark Regression") },
                                        )
                                    },
                                )
                                put(
                                    "results",
                                    buildJsonArray {
                                        report.comparisons.filter { it.classification == RegressionClassification.REGRESSED }.forEach { c ->
                                            add(
                                                buildJsonObject {
                                                    put("ruleId", "benchmark-regression")
                                                    put("level", "error")
                                                    put(
                                                        "message",
                                                        buildJsonObject {
                                                            put(
                                                                "text",
                                                                "${c.caseIdentity} ${c.metricName} regressed by ${c.relativeDeltaPercent}%",
                                                            )
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    },
                )
            },
        ),
    )

    private fun write(
        path: Path,
        content: String,
    ) {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(path, content)
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
}
