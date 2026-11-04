package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.aiplatform.storage.redactAnalysisText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

public data class SkillExecutionResult(
    val skillId: String,
    val evidenceIds: List<String>,
    val candidateIds: List<String>,
    val numericFacts: Map<String, Long>,
    val artifact: ReadOnlyReportArtifact? = null,
)

/** An in-memory preview is not filesystem publication or an implicit write grant. */
public data class ReadOnlyReportArtifact(
    val html: String,
    val markdown: String,
    val evidenceIds: List<String>,
    val candidateIds: List<String>,
)

/** Real deterministic host implementations. Imported prompt text never selects executable code. */
internal class BuiltinSkillExecutor {
    suspend fun execute(
        skillId: String,
        request: AnalysisRequest,
        result: AnalysisResult? = null,
    ): SkillExecutionResult {
        currentCoroutineContext().ensureActive()
        return when (skillId) {
            "aps.evidence-summary" -> {
                require(
                    request.evidence.isNotEmpty() &&
                        request.evidence
                            .map { it.id }
                            .distinct()
                            .size == request.evidence.size,
                )
                SkillExecutionResult(
                    skillId,
                    request.evidence.map { it.id },
                    emptyList(),
                    mapOf(
                        "evidenceCount" to request.evidence.size.toLong(),
                        "evidencePayloadBytes" to
                            request.evidence.sumOf {
                                it.structuredPayload
                                    .toByteArray()
                                    .size
                                    .toLong()
                            },
                    ),
                )
            }
            "aps.source-candidates" -> {
                request.sourceCandidates.forEach { candidate ->
                    require(candidate.id.isNotBlank())
                    require(candidate.startLine == null || candidate.startLine!! > 0)
                    require(candidate.endLine == null || candidate.startLine != null && candidate.endLine!! >= candidate.startLine!!)
                    require(candidate.contentHash == null || candidate.contentHash!!.matches(Regex("[a-f0-9]{64}")))
                    require(!candidate.relativePath.startsWith('/') && '\\' !in candidate.relativePath && ':' !in candidate.relativePath)
                    require(candidate.relativePath.split('/').all { it.isNotBlank() && it != "." && it != ".." })
                }
                SkillExecutionResult(
                    skillId,
                    emptyList(),
                    request.sourceCandidates.map { it.id },
                    mapOf(
                        "candidateCount" to request.sourceCandidates.size.toLong(),
                        "boundedLineRangeCount" to request.sourceCandidates.count { it.startLine != null && it.endLine != null }.toLong(),
                        "unverifiedBuildCount" to request.sourceCandidates.count { it.resolutionConfidence != "EXACT" }.toLong(),
                    ),
                )
            }
            "aps.report-artifact" -> {
                val accepted = checkNotNull(result)
                val evidence = request.evidence.map { it.id }.toSet()
                val candidates = request.sourceCandidates.map { it.id }.toSet()
                require(
                    accepted.findings.all {
                        it.performanceEvidenceIds.all(evidence::contains) &&
                            it.sourceCandidateIds.all(candidates::contains)
                    },
                )
                val source =
                    request.sourceCandidates
                        .mapNotNull { it.sourceSnippet }
                        .flatMap {
                            listOf(it) +
                                it.lines().filter { line -> line.trim().length >= 8 }
                        }.filter { it.isNotEmpty() }
                        .distinct()
                        .sortedByDescending { it.length }

                fun clean(value: String): String =
                    redactAnalysisText(source.fold(value) { text, snippet -> text.replace(snippet, "[source omitted]") })
                val summary = clean(accepted.summary)
                val reportText =
                    buildString {
                        append("# Performance analysis\n\n").append(summary).append("\n\n")
                        accepted.findings.forEach { finding ->
                            append("## ").append(clean(finding.title)).append("\n\n")
                            append(clean(finding.explanation)).append("\n\n").append(clean(finding.recommendation)).append("\n\n")
                            append("Evidence: ").append(finding.performanceEvidenceIds.joinToString(", ")).append("\n\n")
                        }
                    }
                val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(reportText).maxOfOrNull { it.value.length } ?: 0) + 1))
                val markdown = "$fence\n$reportText\n$fence\n"
                val html =
                    "<!doctype html><html><head><meta charset=\"utf-8\">" +
                        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">" +
                        "<title>Performance analysis</title></head><body><pre>" +
                        markdown.escapeHtml() +
                        "</pre></body></html>"
                SkillExecutionResult(
                    skillId,
                    evidence.toList(),
                    candidates.toList(),
                    mapOf(
                        "findingCount" to accepted.findings.size.toLong(),
                    ),
                    ReadOnlyReportArtifact(html, markdown, evidence.toList(), candidates.toList()),
                )
            }
            else -> throw AgentPolicyException("E_SKILL_UNSUPPORTED")
        }
    }

    companion object {
        val BUILTIN_SKILLS = setOf("aps.evidence-summary", "aps.source-candidates", "aps.report-artifact")
    }
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
