package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.app.BusinessContext
import com.androidperformancestudio.aiplatform.app.BusinessContextProviderRegistry
import com.androidperformancestudio.aiplatform.app.contextDigest
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.RunStatus
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun AppDestination.aiContextKey(): String? = when (this) {
    AppDestination.LAYOUT_INSPECTOR -> "layout-inspector"
    AppDestination.SIMPLEPERF -> "simpleperf"
    AppDestination.PERFETTO -> "trace"
    AppDestination.MEMORY_PROFILER -> "memory"
    AppDestination.FRAME_PROFILER -> "frame"
    AppDestination.STARTUP_PROFILER -> "startup"
    AppDestination.BATTERY_PROFILER -> "battery"
    AppDestination.NETWORK_PROFILER -> "network"
    AppDestination.GPU_INSPECTOR -> "gpu"
    AppDestination.BENCHMARK_REGRESSION -> "benchmark"
    AppDestination.SOURCE_WORKSPACES -> "source-workspace"
    else -> null
}

/** One global host owns approval and execution; legacy page actions only publish a frozen context. */
internal class DesktopAiCoordinator(
    val contexts: BusinessContextProviderRegistry = BusinessContextProviderRegistry(),
    private val loadSettings: () -> AiPlatformSettings = { AiPlatformSettingsStore().load() },
) {
    @Volatile var runtime: AiPlatformRuntime? = null
    var onOpen: (BusinessContext?) -> Unit = {}

    suspend fun analyze(request: AnalysisRequest): AnalysisResult {
        val platform = runtime ?: error("E_AI_UNAVAILABLE")
        val settings = loadSettings()
        val frozen = request.copy(
            evidence = request.evidence.map { it.copy() },
            sourceCandidates = request.sourceCandidates.map { it.copy(reasons = it.reasons.toList()) },
        )
        val key = frozen.originProfiler.name.lowercase().replace('_', '-')
        val context = BusinessContext(key, contextDigest(frozen.toString()), frozen.scope.description.take(256), frozen)
        contexts.publish(context)
        val declared = platform.registry.resolve(ExtensionKind.AGENT, settings.agentId).manifest.permissions
        val permissions = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK) +
            (if (frozen.sourceCandidates.isNotEmpty()) setOf(Permission.READ_SOURCE_CANDIDATES) else emptySet()) +
            (if (frozen.sourceCandidates.any { it.sourceSnippet != null }) setOf(Permission.SEND_SOURCE_SNIPPETS) else emptySet()) +
            (if (settings.allowArtifacts && Permission.WRITE_ARTIFACT in declared) setOf(Permission.WRITE_ARTIFACT) else emptySet()) +
            (if (settings.allowScripts && Permission.EXECUTE_SCRIPT in declared) setOf(Permission.EXECUTE_SCRIPT) else emptySet())
        val conversationId = UUID.randomUUID().toString()
        val prepared = platform.service.prepare(conversationId, frozen, settings.agentId, settings.providerId, settings.model.ifBlank { sourceModel() }, permissions)
        try {
            onOpen(context)
            val terminal = platform.service.awaitRun(prepared.approval.runId)
            return terminal.result?.takeIf { terminal.status == RunStatus.SUCCEEDED } ?: error(terminal.errorCode ?: "E_AI_CANCELLED")
        } catch (failure: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                try {
                    platform.service.cancel(prepared.approval.runId)
                } catch (_: Exception) {
                    // Preserve caller cancellation even when persistence is unavailable.
                }
            }
            throw failure
        }
    }

    var sourceModel: () -> String = { "" }
}
