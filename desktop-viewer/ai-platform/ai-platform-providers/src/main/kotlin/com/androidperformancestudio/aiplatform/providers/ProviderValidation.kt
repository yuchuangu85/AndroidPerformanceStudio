package com.androidperformancestudio.aiplatform.providers

import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.isDirectChat
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ProviderValidationFailure(
    val code: String,
) : IllegalArgumentException(code)

internal fun checkProvider(
    value: Boolean,
    code: String,
) {
    if (!value) throw ProviderValidationFailure(code)
}

/** Copy collection boundaries before checking them so the sent object is the object checked. */
internal fun ProviderRunRequest.snapshot(): ProviderRunRequest =
    copy(
        analysis =
            analysis.copy(
                evidence = analysis.evidence.toList(),
                sourceCandidates = analysis.sourceCandidates.map { it.copy(reasons = it.reasons.toList()) },
            ),
        grantedPermissions = grantedPermissions.toSet(),
        approval = approval?.let { grant -> grant.copy(request = grant.request.copy(permissions = grant.request.permissions.toSet())) },
    )

internal fun validateRequest(
    request: ProviderRunRequest,
    descriptor: ProviderDescriptor,
) {
    checkProvider(request.providerId == descriptor.id, "E_PROVIDER_MISMATCH")
    checkProvider(Permission.READ_EVIDENCE in request.grantedPermissions, "E_EVIDENCE_PERMISSION")
    val analysis = request.analysis
    checkProvider(analysis.sessionId.value.length in 1..128, "E_SESSION_ID")
    checkProvider(analysis.promptVersion.length in 1..128 && analysis.payloadPolicyVersion.length in 1..128, "E_POLICY_VERSION")
    checkProvider(analysis.isDirectChat || analysis.evidence.size in 1..100, "E_EVIDENCE_LIMIT")
    if (analysis.isDirectChat) {
        checkProvider(!analysis.userQuestion.isNullOrBlank(), "E_QUESTION_LIMIT")
        checkProvider(
            request.grantedPermissions.all { it == Permission.READ_EVIDENCE || it == Permission.PROVIDER_NETWORK },
            "E_PERMISSION_DENIED",
        )
    }
    checkProvider(analysis.evidence.all { it.id.length in 1..256 }, "E_EVIDENCE_ID")
    checkProvider(
        analysis.evidence
            .map { it.id }
            .distinct()
            .size == analysis.evidence.size,
        "E_EVIDENCE_ID",
    )
    checkProvider(analysis.sourceCandidates.size <= 100, "E_CANDIDATE_LIMIT")
    checkProvider(analysis.sourceCandidates.all { it.id.length in 1..256 }, "E_CANDIDATE_ID")
    checkProvider(
        analysis.sourceCandidates
            .map { it.id }
            .distinct()
            .size == analysis.sourceCandidates.size,
        "E_CANDIDATE_ID",
    )
    checkProvider(
        analysis.sourceCandidates.isEmpty() || Permission.READ_SOURCE_CANDIDATES in request.grantedPermissions,
        "E_SOURCE_PERMISSION",
    )
    checkProvider(
        analysis.sourceCandidates.none { it.sourceSnippet != null } || Permission.SEND_SOURCE_SNIPPETS in request.grantedPermissions,
        "E_SOURCE_SNIPPET_PERMISSION",
    )
    checkProvider(
        ProviderCapability.LOCAL_ONLY in descriptor.capabilities || Permission.PROVIDER_NETWORK in request.grantedPermissions,
        "E_NETWORK_PERMISSION",
    )
    checkProvider(request.credential?.resolvable != false, "E_PROVIDER_CREDENTIAL")
    // Reject oversized text before JSON parsing/escaping creates additional copies.
    val inputFields =
        sequenceOf(
            analysis.sessionId.value,
            analysis.scope.description,
            analysis.promptVersion,
            analysis.payloadPolicyVersion,
            analysis.userQuestion.orEmpty(),
        ) +
            analysis.evidence.asSequence().flatMap { sequenceOf(it.id, it.kind, it.summary, it.structuredPayload) } +
            analysis.sourceCandidates.asSequence().flatMap {
                sequenceOf(it.id, it.relativePath, it.symbol.orEmpty(), it.resolutionConfidence, it.sourceSnippet.orEmpty()) +
                    it.reasons.asSequence()
            }
    var rawInputBytes = 0L
    inputFields.forEach { field ->
        rawInputBytes += field.conservativeUtf8Size()
        checkProvider(rawInputBytes <= request.budget.maxInputBytes, "E_INPUT_LIMIT")
    }
    val input =
        try {
            analysis.payloadText()
        } catch (_: IllegalArgumentException) {
            throw ProviderValidationFailure("E_INPUT_PAYLOAD")
        }
    checkProvider(input.toByteArray(Charsets.UTF_8).size <= request.budget.maxInputBytes, "E_INPUT_LIMIT")
}

internal fun validateResult(
    request: ProviderRunRequest,
    result: AnalysisResult,
): AnalysisResult {
    val snapshot =
        result.copy(
            findings =
                result.findings.map {
                    it.copy(
                        performanceEvidenceIds = it.performanceEvidenceIds.toList(),
                        sourceCandidateIds = it.sourceCandidateIds.toList(),
                    )
                },
        )
    checkProvider(snapshot.sessionId == request.analysis.sessionId, "E_RESULT_SESSION")
    checkProvider(snapshot.model == request.model, "E_RESULT_MODEL")
    checkProvider(snapshot.summary.length <= 16_384 && snapshot.findings.size <= 12, "E_RESULT_LIMIT")
    checkProvider(
        snapshot.findings
            .map { it.id }
            .distinct()
            .size == snapshot.findings.size,
        "E_FINDING_ID",
    )
    val evidenceIds =
        request.analysis.evidence
            .map { it.id }
            .toSet()
    val candidateIds =
        request.analysis.sourceCandidates
            .map { it.id }
            .toSet()
    snapshot.findings.forEach { finding ->
        checkProvider(finding.id.value.length in 1..256, "E_FINDING_ID")
        checkProvider(
            finding.title.length <= 512 && finding.explanation.length <= 8192 && finding.recommendation.length <= 8192,
            "E_RESULT_LIMIT",
        )
        checkProvider(finding.analysisConfidence in 0f..1f, "E_FINDING_CONFIDENCE")
        checkProvider(finding.performanceEvidenceIds.size in 1..100 && finding.sourceCandidateIds.size <= 100, "E_RESULT_REFERENCES")
        checkProvider(
            finding.performanceEvidenceIds.all(evidenceIds::contains) &&
                finding.performanceEvidenceIds.distinct().size == finding.performanceEvidenceIds.size,
            "E_RESULT_EVIDENCE",
        )
        checkProvider(
            finding.sourceCandidateIds.all(candidateIds::contains) &&
                finding.sourceCandidateIds.distinct().size == finding.sourceCandidateIds.size,
            "E_RESULT_CANDIDATE",
        )
    }
    checkProvider(snapshot.encodedSize() <= request.budget.maxOutputBytes, "E_OUTPUT_LIMIT")
    return snapshot
}

/** Account for UTF-8 and JSON escaping, not only JVM UTF-16 character counts. */
internal fun AnalysisResult.encodedSize(): Int =
    buildJsonObject {
        put("sessionId", sessionId.value)
        put("model", model)
        put("summary", summary)
        put(
            "findings",
            buildJsonArray {
                findings.forEach { finding ->
                    add(
                        buildJsonObject {
                            put("id", finding.id.value)
                            put("severity", finding.severity.name)
                            put("title", finding.title)
                            put("explanation", finding.explanation)
                            put("recommendation", finding.recommendation)
                            put("analysisConfidence", finding.analysisConfidence)
                            put("performanceEvidenceIds", JsonArray(finding.performanceEvidenceIds.map(::JsonPrimitive)))
                            put("sourceCandidateIds", JsonArray(finding.sourceCandidateIds.map(::JsonPrimitive)))
                        },
                    )
                }
            },
        )
    }.toString().toByteArray(Charsets.UTF_8).size

/** A conservative count avoids allocating a byte array for an untrusted oversized string. */
private fun String.conservativeUtf8Size(): Long =
    fold(0L) { count, char ->
        count +
            when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                else -> 3
            }
    }
