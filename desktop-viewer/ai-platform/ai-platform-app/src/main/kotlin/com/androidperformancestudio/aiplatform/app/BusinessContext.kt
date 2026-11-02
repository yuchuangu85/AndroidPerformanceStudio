package com.androidperformancestudio.aiplatform.app

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.ai.isDirectChat
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

public data class BusinessContext(
    val key: String,
    val revision: String,
    val title: String,
    val request: AnalysisRequest,
) {
    init {
        require(key.matches(Regex("[a-z][a-z-]{0,40}")))
        require(revision.matches(Regex("[a-f0-9]{64}")))
        require(title.length in 1..256)
        require(request.evidence.isNotEmpty() || request.isDirectChat)
    }
}

/** Stable, empty canonical context permits chat history without fabricating performance facts. */
public fun createChatContext(): BusinessContext {
    val request =
        AnalysisRequest(
            AnalysisSessionId("aps-chat-v1"),
            ProfilerKind.CHAT,
            AnalysisScope(AnalysisScopeKind.CONVERSATION, "General conversation"),
            emptyList(),
            emptyList(),
            "aps-chat-v1",
            "aps-minimized-v1",
        )
    return BusinessContext("chat", contextDigest("aps-chat-v1"), "General conversation", request)
}

/** Profiler producers publish facts, not prompts; current navigation selects one exact context. */
public class BusinessContextProviderRegistry {
    private val contexts = ConcurrentHashMap<String, BusinessContext>()

    public fun publish(context: BusinessContext) {
        contexts[context.key] =
            context.copy(
                request =
                    context.request.copy(
                        evidence = context.request.evidence.map { it.copy() },
                        sourceCandidates = context.request.sourceCandidates.map { it.copy(reasons = it.reasons.toList()) },
                    ),
            )
    }

    public fun current(key: String): BusinessContext? = contexts[key]

    public fun remove(key: String) {
        contexts.remove(key)
    }
}

public fun createBusinessContext(
    key: String,
    title: String,
    evidence: List<PerformanceEvidence>,
    currentSelection: Boolean,
): BusinessContext {
    require(evidence.size in 1..100)
    val profiler =
        ProfilerKind.entries.firstOrNull { it.name.lowercase().replace('_', '-') == key }
            ?: if (key == "perfetto") ProfilerKind.TRACE else error("E_CONTEXT_KIND")
    val revision =
        contextDigest(
            evidence.joinToString("\n") { "${it.id}\n${it.kind}\n${it.summary}\n${it.structuredPayload}" } + currentSelection,
        )
    val request =
        AnalysisRequest(
            AnalysisSessionId("$key-${revision.take(32)}"),
            profiler,
            AnalysisScope(if (currentSelection) AnalysisScopeKind.CURRENT_SELECTION else AnalysisScopeKind.REPORT_SUMMARY, title),
            evidence,
            emptyList(),
            "aps-main-v1",
            "aps-minimized-v1",
        )
    return BusinessContext(key, revision, title, request)
}

public fun contextDigest(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
