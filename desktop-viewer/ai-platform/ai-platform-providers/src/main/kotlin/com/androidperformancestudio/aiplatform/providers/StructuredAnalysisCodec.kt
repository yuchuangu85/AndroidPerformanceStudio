package com.androidperformancestudio.aiplatform.providers

import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive

/** Shared CLI adapter codec. It accepts exactly the evidence-bound ai-core finding contract. */
public object StructuredAnalysisCodec {
    public val schema: String =
        """
        {"type":"object","additionalProperties":false,"required":["summary","findings"],"properties":{
          "summary":{"type":"string","maxLength":16384},
          "findings":{"type":"array","maxItems":12,"items":{"type":"object","additionalProperties":false,
            "required":["id","severity","title","explanation","recommendation","analysisConfidence","performanceEvidenceIds","sourceCandidateIds"],
            "properties":{
              "id":{"type":"string","maxLength":256},"severity":{"type":"string","enum":["INFO","WARNING","ERROR"]},
              "title":{"type":"string","maxLength":512},"explanation":{"type":"string","maxLength":8192},
              "recommendation":{"type":"string","maxLength":8192},"analysisConfidence":{"type":"number","minimum":0,"maximum":1},
              "performanceEvidenceIds":{"type":"array","minItems":1,"maxItems":100,"items":{"type":"string","maxLength":256}},
              "sourceCandidateIds":{"type":"array","maxItems":100,"items":{"type":"string","maxLength":256}}
            }}}
        }}
        """.trimIndent()

    public fun validateInput(
        request: ProviderRunRequest,
        descriptor: com.androidperformancestudio.aiplatform.model.ProviderDescriptor,
    ) {
        validateRequest(request, descriptor)
    }

    public fun decode(
        request: ProviderRunRequest,
        text: String,
    ): AnalysisResult {
        try {
            checkProvider(text.toByteArray(Charsets.UTF_8).size <= request.budget.maxOutputBytes, "E_OUTPUT_LIMIT")
            val root = Json.parseToJsonElement(text) as? JsonObject ?: throw ProviderValidationFailure("E_PROVIDER_PROTOCOL")
            checkProvider(root.keys == setOf("summary", "findings"), "E_PROVIDER_PROTOCOL")
            val findings = root.getValue("findings") as? JsonArray ?: throw ProviderValidationFailure("E_PROVIDER_PROTOCOL")
            checkProvider(findings.size <= 12, "E_RESULT_LIMIT")
            val result =
                AnalysisResult(
                    request.analysis.sessionId,
                    request.model,
                    root.string("summary"),
                    findings.map { element ->
                        val finding = element as? JsonObject ?: throw ProviderValidationFailure("E_PROVIDER_PROTOCOL")
                        checkProvider(finding.keys == FINDING_KEYS, "E_PROVIDER_PROTOCOL")
                        AnalysisFinding(
                            AnalysisFindingId(finding.string("id")),
                            AnalysisSeverity.valueOf(finding.string("severity")),
                            finding.string("title"),
                            finding.string("explanation"),
                            finding.string("recommendation"),
                            finding.getValue("analysisConfidence").jsonPrimitive.float,
                            finding.strings("performanceEvidenceIds"),
                            finding.strings("sourceCandidateIds"),
                        )
                    },
                )
            return validateResult(request, result)
        } catch (failure: ProviderValidationFailure) {
            throw failure
        } catch (_: Exception) {
            throw IllegalArgumentException("E_PROVIDER_PROTOCOL")
        }
    }

    public fun errorCode(failure: Throwable): String =
        when (failure) {
            is ProviderProcessException -> failure.code
            is ProviderValidationFailure -> failure.code
            is IllegalStateException ->
                if (failure.message ==
                    "E_PROVIDER_CONNECTION_CHANGED"
                ) {
                    "E_PROVIDER_CONNECTION_CHANGED"
                } else {
                    "E_PROVIDER_PROTOCOL"
                }
            else -> "E_PROVIDER_PROTOCOL"
        }

    private fun JsonObject.string(key: String): String {
        val value = getValue(key).jsonPrimitive
        checkProvider(value.isString, "E_PROVIDER_PROTOCOL")
        return value.content
    }

    private fun JsonObject.strings(key: String): List<String> =
        (getValue(key) as? JsonArray ?: throw ProviderValidationFailure("E_PROVIDER_PROTOCOL")).map {
            checkProvider(it.jsonPrimitive.isString, "E_PROVIDER_PROTOCOL")
            it.jsonPrimitive.content
        }

    private val FINDING_KEYS =
        setOf(
            "id",
            "severity",
            "title",
            "explanation",
            "recommendation",
            "analysisConfidence",
            "performanceEvidenceIds",
            "sourceCandidateIds",
        )
}
