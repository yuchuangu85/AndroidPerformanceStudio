package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.aiplatform.model.RunStatus

public data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAtEpochMillis: Long,
    val pinned: Boolean,
    val turnCount: Int,
    val status: RunStatus?,
    val archived: Boolean = false,
)

public data class ChatTurn(
    val runId: String,
    val generation: Long,
    val status: RunStatus,
    val question: String?,
    val result: AnalysisResult?,
    val timestampEpochMillis: Long,
    val artifactHashes: List<String>,
)
