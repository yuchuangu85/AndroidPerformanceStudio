package com.androidperformancestudio.aiplatform.model

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
public enum class ProviderCapability { STRUCTURED_ANALYSIS, STREAMING, TOOL_CALL, APPROVAL, RESUME, MODEL_DISCOVERY, LOCAL_ONLY }

@Serializable
public data class ProviderDescriptor(
    val id: String,
    val capabilities: Set<ProviderCapability>,
) {
    init {
        requireStableId(id)
    }
}

/** Never a credential value, arbitrary file location or environment dump. */
@Serializable
public data class CredentialReference(
    val source: String,
    val key: String,
    val resolvable: Boolean,
) {
    init {
        requireStableId(source)
        requireStableId(key)
    }
}

@Serializable
public enum class ProviderUnavailableCode {
    MISSING_RUNTIME,
    INCOMPATIBLE_VERSION,
    MALFORMED_CONFIG,
    MISSING_CREDENTIAL,
    PERMISSION_DENIED,
    UNSUPPORTED_CAPABILITY,
}

public sealed interface ProviderAvailability {
    public data class Available(
        val runtimeVersion: String,
    ) : ProviderAvailability

    public data class Unavailable(
        val code: ProviderUnavailableCode,
    ) : ProviderAvailability
}

public data class ProviderRunRequest(
    val runId: String,
    val generation: Long,
    val agentId: String,
    val providerId: String,
    val model: String,
    val analysis: AnalysisRequest,
    val grantedPermissions: Set<Permission>,
    val budget: ResourceBudget = ResourceBudget(),
    val credential: CredentialReference? = null,
    val approval: ApprovalGrant? = null,
    /** Opaque host connection identity; never part of the analysis payload or persisted audit. */
    val connectionRevision: String? = null,
) {
    init {
        require(runId.length in 1..128 && generation >= 0)
        requireStableId(agentId)
        requireStableId(providerId)
        require(model.isNotBlank() && model.length <= 128)
        require(connectionRevision == null || connectionRevision.length in 1..128 && connectionRevision.none(Char::isISOControl))
    }
}

/** Cancellation is structured coroutine cancellation, never a model-controlled token. */
public interface AiProviderDriver {
    public val descriptor: ProviderDescriptor

    public suspend fun discover(): ProviderAvailability

    public fun run(request: ProviderRunRequest): Flow<ProviderEvent>
}

public sealed interface ProviderEvent {
    public data object Started : ProviderEvent

    public data class TextDelta(
        val text: String,
    ) : ProviderEvent

    public data class StructuredResult(
        val result: AnalysisResult,
    ) : ProviderEvent

    public data class ToolCall(
        val id: String,
        val toolId: String,
    ) : ProviderEvent

    public data class ApprovalRequired(
        val request: ApprovalRequest,
    ) : ProviderEvent

    public data class Usage(
        val inputTokens: Long,
        val outputTokens: Long,
    ) : ProviderEvent

    public data class Failure(
        val code: String,
        val retryable: Boolean,
    ) : ProviderEvent

    public data object Cancelled : ProviderEvent

    public data object Completed : ProviderEvent
}
