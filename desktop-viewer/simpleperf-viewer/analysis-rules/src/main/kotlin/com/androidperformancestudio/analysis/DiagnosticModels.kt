package com.androidperformancestudio.analysis

import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.storage.TopFunction

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

data class DiagnosticEvidence(
    val label: String,
    val value: String,
)

sealed interface DiagnosticTarget {
    data class Function(
        val symbolName: String,
        val filePath: String,
    ) : DiagnosticTarget

    data class Thread(
        val threadId: Int,
        val threadName: String,
    ) : DiagnosticTarget
}

data class DiagnosticFinding(
    val ruleId: String,
    val title: String,
    val severity: DiagnosticSeverity,
    val conclusion: String,
    val evidence: List<DiagnosticEvidence>,
    val recommendations: List<String>,
    val target: DiagnosticTarget? = null,
)

data class AnalysisSnapshot(
    val overview: ProfileOverview,
    val quality: DataQualitySummary,
    val threads: List<ThreadSummary>,
    val topFunctions: List<TopFunction>,
)

fun interface DiagnosticRule {
    fun analyze(snapshot: AnalysisSnapshot): List<DiagnosticFinding>
}

class DiagnosticEngine(
    private val rules: List<DiagnosticRule>,
) {
    fun analyze(snapshot: AnalysisSnapshot): List<DiagnosticFinding> = rules.flatMap { it.analyze(snapshot) }
}
