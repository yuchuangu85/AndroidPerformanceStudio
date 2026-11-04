package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ReportLanguage
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.BusinessContext
import com.androidperformancestudio.aiplatform.app.contextDigest
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.RunStatus
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DesktopAiCoordinatorTest {
    @Test
    fun `every page analysis entry reaches saved Provider Agent and fresh approval without sending on click`() = runBlocking {
        val contexts = listOf(
            "method-recording" to "method-recording-summary",
            "memory" to "memory-summary",
            "trace" to "trace-selection",
            "frame" to "frame-summary",
            "startup" to "startup-summary",
            "battery" to "battery-summary",
            "network" to "network-summary",
            "gpu" to "gpu-summary",
            "benchmark" to "benchmark-summary",
            "source-workspace" to "source-workspace-selection",
        )
        for ((key, kind) in contexts) {
            withFixture { fixture ->
                val context = com.androidperformancestudio.ui.UiAiContext(
                    key, "$key evidence", false,
                    listOf(com.androidperformancestudio.ui.UiAiEvidence("$key:ref", kind, "Verified facts", "{\"count\":1}")),
                )
                val request = profilerAnalysisRequest(context)
                val before = fixture.requests.size
                val analysis = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(request) }
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("test-model", prepared.model)
                assertEquals("openai", prepared.providerId)
                assertEquals(0, prepared.candidateCount)
                assertEquals(RunStatus.AWAITING_APPROVAL, fixture.runtime.service.state.value.status)
                assertEquals(before, fixture.requests.size)
                fixture.runtime.service.approveAndRun(prepared.approval).join()
                assertEquals(result(request, "test-model"), analysis.await())
                assertEquals(before + prepared.plannedProviderCallCount, fixture.requests.size)
                assertTrue(fixture.requests.last().sourceCandidates.isEmpty())
            }
        }
    }

    @Test
    fun `legacy analysis publishes frozen context and waits without executing a provider`() = runBlocking {
        withFixture { fixture ->
            val evidence = request().evidence.toMutableList()
            val reasons = mutableListOf("Matched selected frame")
            val candidate = AiSourceCandidate(
                "candidate-1", "Frame.kt", "render", "PROBABLE", reasons, "fun render() = Unit",
                1, 1, "a".repeat(64),
            )
            val candidates = mutableListOf(candidate)
            val original = request().copy(
                scope = AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Selected frame ".repeat(24)),
                evidence = evidence,
                sourceCandidates = candidates,
            )
            val expected = original.copy(
                evidence = evidence.toList(),
                sourceCandidates = listOf(candidate.copy(reasons = reasons.toList())),
            )
            var opened: BusinessContext? = null
            fixture.coordinator.onOpen = { opened = it }

            val analysis = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(original) }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
            evidence.clear()
            reasons.clear()
            candidates.clear()

            val context = checkNotNull(opened)
            assertNotSame(original, context.request)
            assertEquals(expected, context.request)
            assertEquals(context, fixture.coordinator.contexts.current("frame"))
            assertEquals(contextDigest(expected.toString()), context.revision)
            assertEquals(expected.scope.description.take(256), context.title)
            assertEquals("openai", prepared.providerId)
            assertEquals("test-model", prepared.model)
            assertEquals(expected.sessionId.value, prepared.approval.sessionId)
            assertEquals(1, prepared.evidenceCount)
            assertEquals(1, prepared.candidateCount)
            assertEquals(
                setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK, Permission.READ_SOURCE_CANDIDATES, Permission.SEND_SOURCE_SNIPPETS),
                prepared.approval.permissions,
            )
            assertEquals(RunStatus.AWAITING_APPROVAL, fixture.runtime.service.state.value.status)
            assertFalse(analysis.isCompleted)
            assertTrue(fixture.models.isEmpty())
            assertTrue(fixture.requests.isEmpty())
            analysis.cancelAndJoin()
        }
    }

    @Test
    fun `explicit global approval completes legacy caller using fallback model and artifact settings`() = runBlocking {
        withFixture(
            settings =
                AiPlatformSettings(
                    model = "",
                    allowArtifacts = true,
                    reportFormat = ArtifactFormat.MARKDOWN,
                    reportLanguage = ReportLanguage.SIMPLIFIED_CHINESE,
                ),
        ) { fixture ->
            var fallbackCalls = 0
            fixture.coordinator.sourceModel = { fallbackCalls++; "fallback-model" }
            val input = request()
            val analysis = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(input) }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)

            assertEquals(1, fallbackCalls)
            assertEquals("fallback-model", prepared.model)
            assertEquals(ArtifactFormat.MARKDOWN, prepared.reportFormat)
            assertEquals(ReportLanguage.SIMPLIFIED_CHINESE, prepared.analysisPreview?.outputLanguage)
            assertEquals(
                setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK, Permission.WRITE_ARTIFACT),
                prepared.approval.permissions,
            )
            assertTrue(fixture.requests.isEmpty())
            assertTrue(fixture.models.isEmpty())
            assertFalse(analysis.isCompleted)

            fixture.runtime.service.approveAndRun(prepared.approval).join()

            assertEquals(result(input, "fallback-model"), analysis.await())
            assertRootRequest(input.copy(outputLanguage = ReportLanguage.SIMPLIFIED_CHINESE), fixture.requests.toList())
            assertEquals(prepared.plannedProviderCallCount, fixture.requests.size)
            assertEquals(List(prepared.plannedProviderCallCount) { "fallback-model" }, fixture.models.toList())
            assertEquals(RunStatus.SUCCEEDED, fixture.runtime.service.state.value.status)
            val hash = checkNotNull(fixture.runtime.service.state.value.publishedArtifactHash)
            assertEquals(ArtifactFormat.MARKDOWN, fixture.runtime.artifacts.metadata(hash).format)
            assertEquals(
                RunStatus.SUCCEEDED,
                fixture.runtime.history.load(prepared.conversationId)?.turns?.single()?.status,
            )
        }
    }

    @Test
    fun `explicit settings snapshot controls Layout preflight and result without rereading current settings`() = runBlocking {
        withFixture { fixture ->
            val captured = AiPlatformSettings(
                providerId = "openai",
                model = "layout-model",
                agentId = "aps.report",
                allowArtifacts = true,
                reportFormat = ArtifactFormat.MARKDOWN,
                reportLanguage = ReportLanguage.ENGLISH,
            )
            val input = request("layout-snapshot").copy(
                originProfiler = ProfilerKind.LAYOUT_INSPECTOR,
                scope = AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Layout node selected"),
                evidence = listOf(PerformanceEvidence("layout:selected", "layout-snapshot", "Selected view bounds", "{\"nodeCount\":1}")),
            )
            val analysis = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(input, captured) }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
            assertEquals("layout-model", prepared.model)
            assertEquals(ArtifactFormat.MARKDOWN, prepared.reportFormat)
            assertEquals(ReportLanguage.ENGLISH, prepared.analysisPreview?.outputLanguage)
            assertEquals(1, prepared.plannedProviderCallCount)
            assertTrue(Permission.WRITE_ARTIFACT in prepared.approval.permissions)
            assertTrue(fixture.requests.isEmpty())
            assertFalse(analysis.isCompleted)

            fixture.runtime.service.approveAndRun(prepared.approval).join()

            assertEquals(result(input, "layout-model"), analysis.await())
            assertEquals(listOf("layout-model"), fixture.models.toList())
            val hash = checkNotNull(fixture.runtime.service.state.value.publishedArtifactHash)
            assertEquals(ArtifactFormat.MARKDOWN, fixture.runtime.artifacts.metadata(hash).format)
            assertRootRequest(input.copy(outputLanguage = ReportLanguage.ENGLISH), fixture.requests.toList())
        }
    }

    @Test
    fun `global cancellation cancels the Layout caller without a provider request`() = runBlocking {
        withFixture { fixture ->
            val analysis = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.analyze(request().copy(originProfiler = ProfilerKind.LAYOUT_INSPECTOR))
            }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)

            fixture.runtime.service.cancel(prepared.approval.runId)

            assertThrows<kotlinx.coroutines.CancellationException> { analysis.await() }
            assertTrue(analysis.isCancelled)
            assertTrue(fixture.requests.isEmpty())
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.state.value.status)
        }
    }

    @Test
    fun `already cancelled callers cannot replace a newer global preflight`() = runBlocking {
        withFixture { fixture ->
            var opened = 0
            fixture.coordinator.onOpen = { opened++ }
            val newer = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(request("newer")) }
            try {
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                val context = fixture.coordinator.contexts.current("frame")
                for (explicitSettings in listOf(false, true)) {
                    val cancelled = Job().apply { cancel() }
                    val old = CoroutineScope(cancelled + Dispatchers.Unconfined).async(start = CoroutineStart.UNDISPATCHED) {
                        if (explicitSettings) {
                            fixture.coordinator.analyze(request("old"), AiPlatformSettings(model = "stale-model"))
                        } else {
                            fixture.coordinator.analyze(request("old"))
                        }
                    }
                    assertThrows<kotlinx.coroutines.CancellationException> { old.await() }
                    assertSame(prepared, fixture.runtime.service.state.value.prepared)
                    assertEquals(context, fixture.coordinator.contexts.current("frame"))
                    assertEquals(RunStatus.AWAITING_APPROVAL, fixture.runtime.service.state.value.status)
                    assertEquals(1, opened)
                    assertTrue(fixture.requests.isEmpty())
                }
            } finally {
                newer.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancellation during global prepare cleans its exact run without opening the window`() = runBlocking {
        var owner: Job? = null
        withFixture(connectionRevision = { owner?.cancel(); "" }) { fixture ->
            var opened = 0
            fixture.coordinator.onOpen = { opened++ }
            val analysis = async(start = CoroutineStart.UNDISPATCHED) {
                owner = currentCoroutineContext()[Job]
                fixture.coordinator.analyze(request())
            }

            assertThrows<kotlinx.coroutines.CancellationException> { analysis.await() }

            assertEquals(0, opened)
            assertTrue(fixture.requests.isEmpty())
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.state.value.status)
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.history().single { it.parentConversationId == null }.turns.last().status)
        }
    }

    @Test
    fun `caller cancellation before approval cancels its prepared run without provider execution`() = runBlocking {
        withFixture { fixture ->
            val analysis = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(request()) }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)

            analysis.cancelAndJoin()

            assertTrue(analysis.isCancelled)
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.awaitRun(prepared.approval.runId).status)
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.state.value.status)
            assertEquals(
                RunStatus.CANCELLED,
                fixture.runtime.history.load(prepared.conversationId)?.turns?.single()?.status,
            )
            assertTrue(fixture.requests.isEmpty())
            assertTrue(fixture.models.isEmpty())
        }
    }

    @Test
    fun `caller cancellation during execution cancels its provider and preserves cancellation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        withFixture(analyze = { _, _ ->
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }) { fixture ->
            val analysis = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(request()) }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
            val provider = fixture.runtime.service.approveAndRun(prepared.approval)
            started.await()

            analysis.cancelAndJoin()
            provider.join()
            stopped.await()

            assertTrue(analysis.isCancelled)
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.awaitRun(prepared.approval.runId).status)
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.state.value.status)
            assertNull(fixture.runtime.service.state.value.result)
            assertEquals(1, fixture.requests.size)
            assertEquals(
                RunStatus.CANCELLED,
                fixture.runtime.history.load(prepared.conversationId)?.turns?.single()?.status,
            )
        }
    }

    @Test
    fun `old caller cancellation cannot cancel a newer run awaiting global approval`() = runBlocking {
        withFixture { fixture ->
            val old = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(request("old")) }
            val oldPrepared = checkNotNull(fixture.runtime.service.state.value.prepared)
            val input = request("new")
            val newer = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(input) }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)

            // Both preparations finish inline; cancel the old caller before its terminal continuation resumes.
            old.cancelAndJoin()

            assertTrue(old.isCancelled)
            assertNotEquals(oldPrepared.approval.runId, prepared.approval.runId)
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.awaitRun(oldPrepared.approval.runId).status)
            assertEquals(prepared, fixture.runtime.service.state.value.prepared)
            assertEquals(RunStatus.AWAITING_APPROVAL, fixture.runtime.service.state.value.status)
            assertFalse(newer.isCompleted)
            assertTrue(fixture.requests.isEmpty())

            fixture.runtime.service.approveAndRun(prepared.approval).join()

            assertEquals(result(input, "test-model"), newer.await())
            assertRootRequest(input, fixture.requests.toList())
            assertEquals(prepared.plannedProviderCallCount, fixture.requests.size)
        }
    }

    @Test
    fun `old caller cancellation cannot cancel a newer globally approved provider run`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        withFixture(analyze = { input, model ->
            started.complete(Unit)
            finish.await()
            result(input, model)
        }) { fixture ->
            val old = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(request("old")) }
            val oldPrepared = checkNotNull(fixture.runtime.service.state.value.prepared)
            val input = request("new")
            val newer = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(input) }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)

            old.cancel()
            val provider = fixture.runtime.service.approveAndRun(prepared.approval)
            old.join()
            started.await()

            assertTrue(old.isCancelled)
            assertEquals(RunStatus.CANCELLED, fixture.runtime.service.awaitRun(oldPrepared.approval.runId).status)
            assertEquals(prepared, fixture.runtime.service.state.value.prepared)
            assertEquals(RunStatus.RUNNING, fixture.runtime.service.state.value.status)
            assertFalse(provider.isCancelled)
            assertFalse(newer.isCompleted)
            assertEquals(1, fixture.requests.size)
            assertEquals(input.evidence, fixture.requests.single().evidence)

            finish.complete(Unit)
            provider.join()

            assertEquals(result(input, "test-model"), newer.await())
            assertEquals(RunStatus.SUCCEEDED, fixture.runtime.service.state.value.status)
            assertRootRequest(input, fixture.requests.toList())
            assertEquals(prepared.plannedProviderCallCount, fixture.requests.size)
        }
    }

    @Test
    fun `selected Agent does not inherit an undeclared script permission from host policy`() = runBlocking {
        withFixture(settings = AiPlatformSettings(model = "test-model", agentId = "aps.report", allowScripts = true)) { fixture ->
            val analysis = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.analyze(request()) }
            val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
            assertFalse(Permission.EXECUTE_SCRIPT in prepared.approval.permissions)
            assertEquals(0, prepared.plannedScriptCount)
            assertEquals(1, prepared.plannedProviderCallCount)
            assertTrue(fixture.requests.isEmpty())
            analysis.cancelAndJoin()
        }
    }

    @Test
    fun `unavailable runtime fails before settings context publication or opening global window`() {
        val coordinator = DesktopAiCoordinator(loadSettings = { error("settings must not be read") })
        coordinator.onOpen = { error("window must not open") }
        coordinator.sourceModel = { error("fallback must not be read") }

        val failure = assertThrows<IllegalStateException> { runBlocking { coordinator.analyze(request()) } }

        assertEquals("E_AI_UNAVAILABLE", failure.message)
        assertNull(coordinator.contexts.current("frame"))
    }

    private suspend fun withFixture(
        settings: AiPlatformSettings = AiPlatformSettings(model = "test-model"),
        analyze: suspend (AnalysisRequest, String) -> AnalysisResult = { input, model -> result(input, model) },
        connectionRevision: (String) -> String = { "" },
        block: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("aps-desktop-ai-coordinator-").toRealPath()
        val requests = ConcurrentLinkedQueue<AnalysisRequest>()
        val models = ConcurrentLinkedQueue<String>()
        try {
            AiPlatformRuntime.open(
                root.resolve("profile"),
                openAiAvailable = { true },
                openAiGateway = { model ->
                    models.add(model)
                    AiAnalysisGateway { input ->
                        requests.add(input)
                        analyze(input, model)
                    }
                },
                sourceAuthorization = { true },
                connectionRevision = connectionRevision,
            ).use { runtime ->
                val coordinator = DesktopAiCoordinator(loadSettings = { settings })
                coordinator.runtime = runtime
                coordinator.sourceModel = { error("configured model must not use the fallback") }
                withTimeout(10_000) { block(Fixture(coordinator, runtime, requests, models)) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private data class Fixture(
        val coordinator: DesktopAiCoordinator,
        val runtime: AiPlatformRuntime,
        val requests: ConcurrentLinkedQueue<AnalysisRequest>,
        val models: ConcurrentLinkedQueue<String>,
    )

    private fun request(id: String = "frame-analysis") = AnalysisRequest(
        AnalysisSessionId(id),
        ProfilerKind.FRAME,
        AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Selected frame"),
        listOf(PerformanceEvidence("frame-1", "frame-selection", "Known frame", "{\"durationNs\":123}")),
        emptyList(),
        "v1",
        "v1",
    )

    private fun result(input: AnalysisRequest, model: String) =
        AnalysisResult(input.sessionId, model, "Evidence-bound result", emptyList())

    private fun assertRootRequest(expected: AnalysisRequest, requests: List<AnalysisRequest>) {
        val root = requests.single { it.sessionId == expected.sessionId }
        assertEquals(expected.originProfiler, root.originProfiler)
        assertEquals(expected.scope, root.scope)
        assertEquals(expected.evidence, root.evidence)
        assertEquals(expected.sourceCandidates, root.sourceCandidates)
        assertEquals(expected.promptVersion, root.promptVersion)
        assertEquals(expected.payloadPolicyVersion, root.payloadPolicyVersion)
        expected.userQuestion?.let { assertTrue(root.userQuestion?.contains(it) == true) }
    }
}
