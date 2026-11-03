package com.androidperformancestudio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.application.AiAnalysisTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class PendingLayoutAiAnalysis(
    val prepared: PreparedAiAnalysis,
    val target: AiAnalysisTarget,
)

/** Owns only this inspector's request, never the hosted conversation or its drafts. */
internal class LayoutAiAnalysisLifecycle(
    private val scope: CoroutineScope,
    private val client: AiAnalysisClient,
    private val isTargetCurrent: (AiAnalysisTarget) -> Boolean,
    private val loadReport: (AiAnalysisReport, AiAnalysisTarget) -> Boolean,
    private val workContext: CoroutineContext = Dispatchers.IO,
) {
    var uiState by mutableStateOf<AiAnalysisUiState>(AiAnalysisUiState.Idle)
        private set
    var pendingAnalysis by mutableStateOf<PendingLayoutAiAnalysis?>(null)
        private set
    var aiAnalysisJob by mutableStateOf<Job?>(null)
        private set

    private var activeTarget: AiAnalysisTarget? = null
    private var disposed = false

    val canStart: Boolean get() = !disposed && aiAnalysisJob == null && pendingAnalysis == null

    fun start(input: AiAnalysisInput, target: AiAnalysisTarget): Boolean {
        if (!canStart || !isTargetCurrent(target)) return false
        val localConfirmation = client.requiresLocalConfirmation
        prepare(
            input = if (localConfirmation) input else input.copy(includeSourceSnippets = false),
            target = target,
            localConfirmation = localConfirmation,
        )
        return true
    }

    fun reprepare(pending: PendingLayoutAiAnalysis, input: AiAnalysisInput): Boolean {
        if (!accepts(pending)) return false
        prepare(input, pending.target, localConfirmation = true)
        return true
    }

    fun confirm(pending: PendingLayoutAiAnalysis): Boolean {
        if (!accepts(pending) || !pending.prepared.manifest.canAnalyze) return false
        pendingAnalysis = null
        launchOwned(pending.target) { analyze(pending.prepared, pending.target) }
        return true
    }

    fun dismiss(pending: PendingLayoutAiAnalysis) {
        if (pendingAnalysis === pending) cancel()
    }

    fun cancelIfTargetChanged() {
        activeTarget?.let { if (!isTargetCurrent(it)) cancel() }
    }

    fun cancel() {
        val job = aiAnalysisJob
        aiAnalysisJob = null
        pendingAnalysis = null
        activeTarget = null
        uiState = AiAnalysisUiState.Idle
        job?.cancel()
    }

    fun dispose() {
        disposed = true
        cancel()
    }

    private fun accepts(pending: PendingLayoutAiAnalysis): Boolean {
        if (disposed || pendingAnalysis !== pending || aiAnalysisJob != null) return false
        if (!isTargetCurrent(pending.target)) {
            cancel()
            return false
        }
        return true
    }

    private fun prepare(input: AiAnalysisInput, target: AiAnalysisTarget, localConfirmation: Boolean) {
        launchOwned(target) {
            val prepared = withContext(workContext) { client.prepare(input) }
            if (!ownsCurrentTarget(target)) return@launchOwned
            if (localConfirmation) {
                pendingAnalysis = PendingLayoutAiAnalysis(prepared, target)
            } else {
                // The client hands off to hosted approval; no local consent or transport here.
                analyze(prepared, target)
            }
        }
    }

    private suspend fun analyze(prepared: PreparedAiAnalysis, target: AiAnalysisTarget) {
        if (!ownsCurrentTarget(target)) return
        val report = withContext(workContext) { client.analyze(prepared) }
        if (ownsCurrentTarget(target)) loadReport(report, target)
    }

    private fun launchOwned(target: AiAnalysisTarget, action: suspend () -> Unit) {
        activeTarget = target
        uiState = AiAnalysisUiState.Working
        // Register before starting, including when the scope executes undispatched.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            try {
                if (ownsCurrentTarget(target)) action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (ownsCurrentTarget(target)) {
                    uiState = AiAnalysisUiState.Failure(error.message ?: error.javaClass.simpleName)
                }
            } finally {
                if (aiAnalysisJob === owner) {
                    aiAnalysisJob = null
                    if (uiState is AiAnalysisUiState.Working) uiState = AiAnalysisUiState.Idle
                }
            }
        }
        aiAnalysisJob = job
        job.start()
    }

    private suspend fun ownsCurrentTarget(target: AiAnalysisTarget): Boolean {
        currentCoroutineContext().ensureActive()
        if (disposed || aiAnalysisJob !== currentCoroutineContext()[Job]) return false
        if (!isTargetCurrent(target)) {
            cancel()
            return false
        }
        return true
    }
}
