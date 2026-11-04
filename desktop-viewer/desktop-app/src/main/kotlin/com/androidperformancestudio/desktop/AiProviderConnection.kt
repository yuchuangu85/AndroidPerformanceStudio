package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.LocalAiConfigSource

internal enum class AiProviderConnection(
    val providerId: String,
    val localSource: LocalAiConfigSource?,
) {
    CODEX("codex", LocalAiConfigSource.CODEX),
    CLAUDE("claude", LocalAiConfigSource.CLAUDE),
    CUSTOM("openai", null),
    ;

    companion object {
        fun fromProviderId(providerId: String): AiProviderConnection =
            entries.firstOrNull { it.providerId == providerId } ?: CODEX
    }
}
