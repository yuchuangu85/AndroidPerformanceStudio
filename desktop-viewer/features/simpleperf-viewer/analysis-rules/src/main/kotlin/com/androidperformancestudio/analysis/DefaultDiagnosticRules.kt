package com.androidperformancestudio.analysis

import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.storage.TopFunction
import java.util.Locale

object DefaultDiagnosticRules {
    fun engine(): DiagnosticEngine =
        DiagnosticEngine(
            listOf(
                DataQualityRule,
                CpuHotspotRule,
                ThreadHotspotRule,
                SampleWeightSemanticsRule,
            ),
        )
}

private object DataQualityRule : DiagnosticRule {
    override fun analyze(snapshot: AnalysisSnapshot): List<DiagnosticFinding> {
        val quality = snapshot.quality
        val issueCount =
            quality.lostSampleCount +
                quality.unwindErrorSamples +
                quality.unknownSymbolSamples +
                quality.emptyStackSamples +
                quality.unknownRecords
        val severity =
            when {
                quality.lostRate >= CRITICAL_LOST_RATE ||
                    quality.unknownSymbolSamples >= quality.sampleCount / UNKNOWN_SYMBOL_CRITICAL_DIVISOR ->
                    DiagnosticSeverity.CRITICAL
                issueCount > 0 -> DiagnosticSeverity.WARNING
                else -> DiagnosticSeverity.INFO
            }
        val conclusion =
            if (issueCount == 0L) {
                "No lost samples, unwind failures, unknown symbols, or empty stacks were detected."
            } else {
                "Profile quality issues can reduce confidence in hotspot and call-chain results."
            }
        return listOf(
            DiagnosticFinding(
                ruleId = "data-quality",
                title = "Data quality",
                severity = severity,
                conclusion = conclusion,
                evidence =
                    listOf(
                        DiagnosticEvidence("Lost samples", quality.lostSampleCount.toString()),
                        DiagnosticEvidence("Lost rate", quality.lostRate.asPercent()),
                        DiagnosticEvidence("Unwind failures", quality.unwindErrorSamples.toString()),
                        DiagnosticEvidence("Unknown symbols", quality.unknownSymbolSamples.toString()),
                        DiagnosticEvidence("Empty stacks", quality.emptyStackSamples.toString()),
                    ),
                recommendations =
                    if (issueCount == 0L) {
                        listOf("Keep the raw profile and capture metadata for reproducible analysis.")
                    } else {
                        listOf(
                            "Reduce sampling frequency if loss is high.",
                            "Provide binary_cache, unstripped libraries, and ProGuard mapping for symbols.",
                            "Use DWARF call graphs when frame-pointer unwinding is incomplete.",
                        )
                    },
            ),
        )
    }
}

private object CpuHotspotRule : DiagnosticRule {
    override fun analyze(snapshot: AnalysisSnapshot): List<DiagnosticFinding> {
        val hotspot = snapshot.topFunctions.firstOrNull()
        return if (hotspot == null) {
            emptyList()
        } else {
            val share = hotspot.inclusiveWeight.shareOf(snapshot.overview.totalEventWeight)
            if (share < HOTSPOT_SHARE) emptyList() else listOf(hotspot.toFinding(share))
        }
    }

    private fun TopFunction.toFinding(share: Double): DiagnosticFinding =
        DiagnosticFinding(
            ruleId = "cpu-hotspot",
            title = "CPU hotspot",
            severity = if (share >= CRITICAL_HOTSPOT_SHARE) DiagnosticSeverity.CRITICAL else DiagnosticSeverity.WARNING,
            conclusion = "$symbolName dominates the selected sample/event weight.",
            evidence =
                listOf(
                    DiagnosticEvidence("Function", symbolName),
                    DiagnosticEvidence("Library", filePath),
                    DiagnosticEvidence("Inclusive share", share.asPercent()),
                    DiagnosticEvidence("Exclusive weight", exclusiveWeight.toString()),
                ),
            recommendations =
                listOf(
                    "Open the flame graph and inspect callers and callees around this function.",
                    "Re-capture the same workload after optimization and compare sample weights.",
                ),
            target = DiagnosticTarget.Function(symbolName, filePath),
        )
}

private object ThreadHotspotRule : DiagnosticRule {
    override fun analyze(snapshot: AnalysisSnapshot): List<DiagnosticFinding> {
        val thread = snapshot.threads.firstOrNull()
        return if (thread == null) {
            emptyList()
        } else {
            val totalWeight = snapshot.threads.sumOf(ThreadSummary::totalEventCount)
            val share = thread.totalEventCount.shareOf(totalWeight)
            if (share < THREAD_HOTSPOT_SHARE) emptyList() else listOf(thread.toFinding(share))
        }
    }

    private fun ThreadSummary.toFinding(share: Double): DiagnosticFinding {
        val kind = if (name.equals("main", ignoreCase = true)) "Main thread" else "Thread"
        return DiagnosticFinding(
            ruleId = "thread-hotspot",
            title = "$kind hotspot",
            severity =
                if (share >= CRITICAL_HOTSPOT_SHARE) {
                    DiagnosticSeverity.CRITICAL
                } else {
                    DiagnosticSeverity.WARNING
                },
            conclusion = "$name carries most of the selected profile weight.",
            evidence =
                listOf(
                    DiagnosticEvidence("Thread", "$name ($threadId)"),
                    DiagnosticEvidence("Weight share", share.asPercent()),
                    DiagnosticEvidence("Samples", sampleCount.toString()),
                ),
            recommendations =
                listOf(
                    "Filter the timeline to this thread and inspect its top functions.",
                    "Move avoidable blocking or CPU-heavy work away from latency-sensitive threads.",
                ),
            target = DiagnosticTarget.Thread(threadId, name),
        )
    }
}

private object SampleWeightSemanticsRule : DiagnosticRule {
    override fun analyze(snapshot: AnalysisSnapshot): List<DiagnosticFinding> =
        listOf(
            DiagnosticFinding(
                ruleId = "sample-weight-semantics",
                title = "Sample weight semantics",
                severity = DiagnosticSeverity.INFO,
                conclusion = "Sample/event weights are statistical evidence, not exact wall-clock durations.",
                evidence =
                    listOf(
                        DiagnosticEvidence("Samples", snapshot.overview.sampleCount.toString()),
                        DiagnosticEvidence("Event weight", snapshot.overview.totalEventWeight.toString()),
                    ),
                recommendations =
                    listOf("Use repeated captures and comparable workloads before drawing performance conclusions."),
            ),
        )
}

private fun Long.shareOf(total: Long): Double = if (total <= 0L) 0.0 else toDouble() / total

private fun Double.asPercent(): String = String.format(Locale.ROOT, "%.2f%%", this * PERCENT_MULTIPLIER)

private const val PERCENT_MULTIPLIER = 100
private const val HOTSPOT_SHARE = 0.2
private const val THREAD_HOTSPOT_SHARE = 0.6
private const val CRITICAL_HOTSPOT_SHARE = 0.8
private const val CRITICAL_LOST_RATE = 0.05
private const val UNKNOWN_SYMBOL_CRITICAL_DIVISOR = 20
