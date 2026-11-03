package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.application.InspectorStore
import com.androidperformancestudio.fixtures.SampleSnapshots
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.coroutines.EmptyCoroutineContext

@Timeout(10)
class LayoutAiAnalysisLifecycleTest {
    @Test
    fun `hosted request freezes selection without local preflight or source snippets`() = runBlocking {
        val store = newStore()
        val root = requireNotNull(store.state.activeRoot)
        val selected = root.children.first()
        assertTrue(store.selectNode(selected.id))
        val target = requireNotNull(store.aiAnalysisTarget())
        val input = AiAnalysisInputBuilder().build(
            snapshot = requireNotNull(store.state.snapshot),
            activeRoot = root,
            analysis = store.state.analysis,
            selectedNode = selected,
        )
        val client = hostedClient()
        val entered = CompletableDeferred<Unit>()
        val approved = CompletableDeferred<Unit>()
        client.onAnalyze = {
            entered.complete(Unit)
            approved.await()
            REPORT
        }
        val lifecycle = lifecycle(store, client)

        assertTrue(lifecycle.start(input, target))
        val job = requireNotNull(lifecycle.aiAnalysisJob)
        entered.await()
        assertSame(job, lifecycle.aiAnalysisJob)
        assertNull(lifecycle.pendingAnalysis)
        assertNull(store.state.aiAnalysis)
        assertFalse(lifecycle.start(input, target))
        assertTrue(store.selectNode(root.id))
        assertEquals(selected.id, client.inputs.single().selectedNodeId)
        assertEquals(input.json, client.inputs.single().json)
        assertEquals(input.sourceEvidence, client.inputs.single().sourceEvidence)
        assertFalse(client.inputs.single().includeSourceSnippets)

        approved.complete(Unit)
        job.join()
        assertEquals(1, client.analysisCalls)
        assertEquals(REPORT, store.state.aiAnalysis)
        assertNull(lifecycle.pendingAnalysis)
        assertNull(lifecycle.aiAnalysisJob)
        assertEquals(AiAnalysisUiState.Idle, lifecycle.uiState)
    }

    @Test
    fun `standalone defaults to local confirmation and retains scope and source controls`() = runBlocking {
        val store = newStore()
        val target = requireNotNull(store.aiAnalysisTarget())
        val client = RecordingClient()
        val lifecycle = lifecycle(store, client)
        assertTrue(client.requiresLocalConfirmation)
        assertTrue(lifecycle.start(INPUT, target))
        requireNotNull(lifecycle.aiAnalysisJob).join()
        val pending = requireNotNull(lifecycle.pendingAnalysis)
        assertSame(target, pending.target)
        assertTrue(pending.prepared.input.includeSourceSnippets)
        assertEquals(0, client.analysisCalls)
        assertFalse(lifecycle.start(INPUT, target))

        val reportOnly = INPUT.copy(json = "report", selectedNodeId = null, includeSourceSnippets = false)
        assertTrue(lifecycle.reprepare(pending, reportOnly))
        requireNotNull(lifecycle.aiAnalysisJob).join()
        val revised = requireNotNull(lifecycle.pendingAnalysis)
        assertSame(target, revised.target)
        assertEquals(reportOnly, revised.prepared.input)
        assertFalse(lifecycle.confirm(pending))
        assertTrue(lifecycle.confirm(revised))
        requireNotNull(lifecycle.aiAnalysisJob).join()
        assertEquals(1, client.analysisCalls)
        assertEquals(REPORT, store.state.aiAnalysis)
        assertNull(lifecycle.pendingAnalysis)
    }

    @Test
    fun `preparation is tracked cancellable and rejects duplicate clicks`() = runBlocking {
        val store = newStore()
        val client = RecordingClient()
        val entered = CompletableDeferred<Unit>()
        client.onPrepare = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val lifecycle = lifecycle(store, client)
        val target = requireNotNull(store.aiAnalysisTarget())
        assertTrue(lifecycle.start(INPUT, target))
        val job = requireNotNull(lifecycle.aiAnalysisJob)
        entered.await()
        assertEquals(AiAnalysisUiState.Working, lifecycle.uiState)
        assertFalse(lifecycle.start(INPUT, target))
        lifecycle.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, client.inputs.size)
        assertEquals(0, client.analysisCalls)
        assertNull(lifecycle.pendingAnalysis)
        assertNull(lifecycle.aiAnalysisJob)
        assertEquals(AiAnalysisUiState.Idle, lifecycle.uiState)
    }

    @Test
    fun `target changing during preparation never opens stale local or hosted preflight`() = runBlocking {
        for (client in listOf(RecordingClient(), hostedClient())) {
            val store = newStore()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            client.onPrepare = {
                entered.complete(Unit)
                release.await()
            }
            val lifecycle = lifecycle(store, client)
            assertTrue(lifecycle.start(INPUT, requireNotNull(store.aiAnalysisTarget())))
            val job = requireNotNull(lifecycle.aiAnalysisJob)
            entered.await()
            replaceCapture(store)
            // Exercise the completion guard before the page's invalidation effect can run.
            release.complete(Unit)
            job.join()
            assertNull(lifecycle.pendingAnalysis)
            assertEquals(0, client.analysisCalls)
            assertEquals(AiAnalysisUiState.Idle, lifecycle.uiState)
        }
    }

    @Test
    fun `stale confirmation and scope changes cannot execute against a replacement capture`() = runBlocking {
        for (confirm in listOf(true, false)) {
            val store = newStore()
            val client = RecordingClient()
            val lifecycle = lifecycle(store, client)
            lifecycle.start(INPUT, requireNotNull(store.aiAnalysisTarget()))
            requireNotNull(lifecycle.aiAnalysisJob).join()
            val pending = requireNotNull(lifecycle.pendingAnalysis)
            replaceCapture(store)
            assertFalse(if (confirm) lifecycle.confirm(pending) else lifecycle.reprepare(pending, INPUT))
            assertNull(lifecycle.pendingAnalysis)
            assertEquals(1, client.inputs.size)
            assertEquals(0, client.analysisCalls)
            assertNull(store.state.aiAnalysis)
        }
    }

    @Test
    fun `target invalidation cancels either active phase and dismisses pending confirmation`() = runBlocking {
        for (preparing in listOf(true, false)) {
            val store = newStore()
            val client = hostedClient()
            val entered = CompletableDeferred<Unit>()
            if (preparing) {
                client.onPrepare = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } else {
                client.onAnalyze = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val lifecycle = lifecycle(store, client)
            lifecycle.start(INPUT, requireNotNull(store.aiAnalysisTarget()))
            val job = requireNotNull(lifecycle.aiAnalysisJob)
            entered.await()
            replaceCapture(store)
            lifecycle.cancelIfTargetChanged()
            job.join()
            assertTrue(job.isCancelled)
            assertNull(lifecycle.aiAnalysisJob)
            assertNull(lifecycle.pendingAnalysis)
            assertEquals(AiAnalysisUiState.Idle, lifecycle.uiState)
            assertNull(store.state.aiAnalysis)
        }
        val store = newStore()
        val lifecycle = lifecycle(store, RecordingClient())
        lifecycle.start(INPUT, requireNotNull(store.aiAnalysisTarget()))
        requireNotNull(lifecycle.aiAnalysisJob).join()
        assertNotNull(lifecycle.pendingAnalysis)
        replaceCapture(store)
        lifecycle.cancelIfTargetChanged()
        assertNull(lifecycle.pendingAnalysis)
    }

    @Test
    fun `late hosted result is discarded even before invalidation effect`() = runBlocking {
        val store = newStore()
        val client = hostedClient()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        client.onAnalyze = {
            entered.complete(Unit)
            release.await()
            REPORT
        }
        val lifecycle = lifecycle(store, client)
        lifecycle.start(INPUT, requireNotNull(store.aiAnalysisTarget()))
        val job = requireNotNull(lifecycle.aiAnalysisJob)
        entered.await()
        replaceCapture(store)
        release.complete(Unit)
        job.join()
        assertNull(store.state.aiAnalysis)
        assertNull(lifecycle.aiAnalysisJob)
        assertEquals(AiAnalysisUiState.Idle, lifecycle.uiState)
    }

    @Test
    fun `cancelled older job cannot clear replacement job or reopen its dialog`() = runBlocking {
        for (preparing in listOf(true, false)) {
            val store = newStore()
            var localConfirmation = preparing
            val client = object : RecordingClient() {
                override val requiresLocalConfirmation: Boolean get() = localConfirmation
            }
            val entered = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val replacementEntered = CompletableDeferred<Unit>()
            val releaseReplacement = CompletableDeferred<Unit>()
            val holdOld: suspend () -> Nothing = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseOld.await() }
                }
            }
            if (preparing) client.onPrepare = { holdOld() } else client.onAnalyze = { holdOld() }
            val lifecycle = lifecycle(store, client)
            val target = requireNotNull(store.aiAnalysisTarget())
            lifecycle.start(INPUT, target)
            val oldJob = requireNotNull(lifecycle.aiAnalysisJob)
            entered.await()
            lifecycle.cancel()
            localConfirmation = false
            client.onPrepare = {}
            client.onAnalyze = {
                replacementEntered.complete(Unit)
                releaseReplacement.await()
                REPORT
            }
            assertTrue(lifecycle.start(INPUT, target))
            val newJob = requireNotNull(lifecycle.aiAnalysisJob)
            replacementEntered.await()
            releaseOld.complete(Unit)
            oldJob.join()
            assertSame(newJob, lifecycle.aiAnalysisJob)
            assertEquals(AiAnalysisUiState.Working, lifecycle.uiState)
            assertNull(lifecycle.pendingAnalysis)
            assertNull(store.state.aiAnalysis)
            releaseReplacement.complete(Unit)
            newJob.join()
            assertEquals(REPORT, store.state.aiAnalysis)
        }
    }

    @Test
    fun `dismissing old preflight cannot cancel a newer request`() = runBlocking {
        val store = newStore()
        val lifecycle = lifecycle(store, RecordingClient())
        val target = requireNotNull(store.aiAnalysisTarget())
        lifecycle.start(INPUT, target)
        requireNotNull(lifecycle.aiAnalysisJob).join()
        val old = requireNotNull(lifecycle.pendingAnalysis)
        lifecycle.dismiss(old)
        lifecycle.start(INPUT, target)
        requireNotNull(lifecycle.aiAnalysisJob).join()
        val replacement = requireNotNull(lifecycle.pendingAnalysis)
        lifecycle.dismiss(old)
        assertSame(replacement, lifecycle.pendingAnalysis)
        assertFalse(lifecycle.confirm(old))
        lifecycle.dismiss(replacement)
    }

    @Test
    fun `disposal cancels preparation execution and pending consent without restarting`() = runBlocking {
        for (phase in 0..2) {
            val store = newStore()
            val client = if (phase == 2) RecordingClient() else hostedClient()
            val entered = CompletableDeferred<Unit>()
            if (phase == 0) {
                client.onPrepare = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            if (phase == 1) {
                client.onAnalyze = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val lifecycle = lifecycle(store, client)
            val target = requireNotNull(store.aiAnalysisTarget())
            lifecycle.start(INPUT, target)
            val job = requireNotNull(lifecycle.aiAnalysisJob)
            if (phase == 2) job.join() else entered.await()
            lifecycle.dispose()
            job.join()
            assertNull(lifecycle.aiAnalysisJob)
            assertNull(lifecycle.pendingAnalysis)
            assertEquals(AiAnalysisUiState.Idle, lifecycle.uiState)
            assertFalse(lifecycle.start(INPUT, target))
            assertNull(store.state.aiAnalysis)
        }
    }

    private fun CoroutineScope.lifecycle(store: InspectorStore, client: AiAnalysisClient) =
        LayoutAiAnalysisLifecycle(
            scope = this,
            client = client,
            isTargetCurrent = store::isAiAnalysisTargetCurrent,
            loadReport = { report, target -> store.loadAiAnalysis(report, target) },
            workContext = EmptyCoroutineContext,
        )

    private fun newStore() = InspectorStore().apply { load(SampleSnapshots.dashboard) }

    private fun replaceCapture(store: InspectorStore) {
        store.load(SampleSnapshots.dashboard.copy(capturedAtEpochMillis = 1234))
    }

    private fun hostedClient() = object : RecordingClient() {
        override val requiresLocalConfirmation: Boolean get() = false
    }

    private open class RecordingClient : AiAnalysisClient {
        val inputs = mutableListOf<AiAnalysisInput>()
        var analysisCalls = 0
        var onPrepare: suspend () -> Unit = {}
        var onAnalyze: suspend () -> AiAnalysisReport = { REPORT }

        override suspend fun prepare(input: AiAnalysisInput): PreparedAiAnalysis {
            inputs += input
            onPrepare()
            return PreparedAiAnalysis(
                input,
                AiPayloadManifest(
                    scope = "test",
                    model = "test-model",
                    sourceSnapshotIds = emptyList(),
                    buildEvidenceBundleIds = emptyList(),
                    evidence = emptyList(),
                    sources = emptyList(),
                    payloadBytes = input.json.length,
                    performanceDataOnly = !input.includeSourceSnippets,
                ),
            ) { REPORT }
        }

        override suspend fun analyze(prepared: PreparedAiAnalysis): AiAnalysisReport {
            analysisCalls++
            return onAnalyze()
        }
    }

    private companion object {
        val INPUT = AiAnalysisInput("selected-node", selectedNodeId = "node")
        val REPORT = AiAnalysisReport(model = "test-model", summary = "summary")
    }
}
