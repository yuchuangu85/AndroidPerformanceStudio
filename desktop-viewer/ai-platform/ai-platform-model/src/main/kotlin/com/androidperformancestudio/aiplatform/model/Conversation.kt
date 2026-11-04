package com.androidperformancestudio.aiplatform.model

import kotlinx.serialization.Serializable

@Serializable
public enum class RunStatus {
    QUEUED,
    PREPARING,
    AWAITING_APPROVAL,
    RUNNING,
    SUCCEEDED,
    CANCELLED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    INTERRUPTED,
    ;

    public val terminal: Boolean
        get() = this in setOf(SUCCEEDED, CANCELLED, RETRYABLE_FAILURE, PERMANENT_FAILURE, INTERRUPTED)
}

@Serializable
public data class ApprovalRequest(
    val id: String,
    val runId: String,
    val sessionId: String,
    val generation: Long,
    val payloadHash: String,
    val permissions: Set<Permission>,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(id.length in 1..128 && runId.length in 1..128 && sessionId.length in 1..128)
        require(generation >= 0 && expiresAtEpochMillis >= 0)
        require(payloadHash.matches(Regex("[a-f0-9]{64}")))
        require(permissions.isNotEmpty())
    }
}

/** Issued only by a host approval service after an explicit user decision; data alone grants nothing. */
@Serializable
public data class ApprovalGrant(
    val request: ApprovalRequest,
    val grantId: String,
) {
    init {
        require(grantId.length in 1..128)
    }
}

@Serializable
public data class ConversationTurn(
    val runId: String,
    val generation: Long,
    val status: RunStatus,
    val evidenceIds: List<String>,
    val candidateIds: List<String>,
    val findingIds: List<String> = emptyList(),
    val errorCode: String? = null,
) {
    init {
        require(runId.length in 1..128 && generation >= 0)
        require(evidenceIds.size <= 100 && candidateIds.size <= 100 && findingIds.size <= 12)
        require((evidenceIds + candidateIds + findingIds).all { it.length in 1..256 })
        require(errorCode == null || errorCode.matches(Regex("E_[A-Z0-9_]{1,80}")))
    }
}

/** Reference-only persistence: no provider responses, prompts, source text or credential material. */
@Serializable
public data class ConversationRecord(
    val schemaVersion: Int = PLATFORM_SCHEMA_VERSION,
    val id: String,
    val analysisSessionId: String,
    val contextHash: String,
    val parentConversationId: String? = null,
    val turns: List<ConversationTurn> = emptyList(),
) {
    init {
        require(schemaVersion == PLATFORM_SCHEMA_VERSION)
        require(id.length in 1..128 && analysisSessionId.length in 1..128)
        require(parentConversationId == null || parentConversationId.length in 1..128)
        require(contextHash.matches(Regex("[a-f0-9]{64}")))
        require(turns.size <= 1000 && turns.map { it.runId }.distinct().size == turns.size)
    }
}

@Serializable
public enum class AuditAction {
    INSTALLED,
    SELECTED,
    DELETED,
    ENABLED,
    DISABLED,
    TRUSTED,
    REVOKED,
    RUN_STARTED,
    RUN_CANCELLED,
    RUN_FINISHED,
}

@Serializable
public data class AuditRecord(
    val schemaVersion: Int = PLATFORM_SCHEMA_VERSION,
    val action: AuditAction,
    val subjectId: String,
    val timestampEpochMillis: Long,
    val contentHash: String? = null,
) {
    init {
        require(schemaVersion == PLATFORM_SCHEMA_VERSION)
        requireStableId(subjectId)
        require(timestampEpochMillis >= 0)
        require(contentHash == null || contentHash.matches(Regex("[a-f0-9]{64}")))
    }
}
