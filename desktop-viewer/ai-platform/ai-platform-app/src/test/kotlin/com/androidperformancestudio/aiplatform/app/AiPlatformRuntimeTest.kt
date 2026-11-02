package com.androidperformancestudio.aiplatform.app

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiHttpRequest
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.OpenAiAnalysisGateway
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiPlatformRuntimeTest {
    @Test
    fun `revision aware factories reject discovery races for both Responses providers and manual retry works`() =
        runBlocking {
            val permissions = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK)
            for (provider in listOf("openai", "codex")) {
                val root = Files.createTempDirectory("aps-ai-connection-race-test-").toRealPath()
                var revision = "snapshot-A"
                val captures = mutableListOf<Triple<String, String, String?>>()
                val http = mutableListOf<AiHttpRequest>()
                val available = {
                    revision = "snapshot-B"
                    true
                }
                try {
                    AiPlatformRuntime
                        .open(
                            root,
                            openAiGateway = { error("E_FIXTURE_LEGACY_GATEWAY") },
                            openAiAvailable = available,
                            codexGateway = { error("E_FIXTURE_LEGACY_GATEWAY") },
                            codexAvailable = available,
                            connectionRevision = { revision },
                            gatewayForRun = { selected, model, expected ->
                                captures += Triple(selected, model, expected)
                                check(expected == revision) { "E_PROVIDER_CONNECTION_CHANGED" }
                                OpenAiAnalysisGateway(
                                    OpenAiResponsesClient(
                                        "fixture",
                                        model,
                                        transport =
                                            AiHttpTransport {
                                                http += it
                                                AiHttpResponse(
                                                    200,
                                                    """{"output_text":"{\"summary\":\"Snapshot result\",\"findings\":[]}"}""",
                                                )
                                            },
                                    ),
                                )
                            },
                        ).use { runtime ->
                            val first =
                                runtime.service.prepare(
                                    "conversation",
                                    createChatContext().request,
                                    "aps.chat",
                                    provider,
                                    "test-model",
                                    permissions,
                                    "Hello",
                                )
                            runtime.service.approveAndRun(first.approval).join()
                            assertEquals(RunStatus.PERMANENT_FAILURE, runtime.service.state.value.status)
                            assertEquals("E_PROVIDER_CONNECTION_CHANGED", runtime.service.state.value.errorCode)
                            assertEquals(listOf(Triple<String, String, String?>(provider, "test-model", "snapshot-A")), captures)
                            assertTrue(http.isEmpty())
                            val retry =
                                runtime.service.prepare(
                                    "conversation",
                                    createChatContext().request,
                                    "aps.chat",
                                    provider,
                                    "test-model",
                                    permissions,
                                    "Hello",
                                )
                            assertTrue(retry.approval.id != first.approval.id)
                            assertTrue(http.isEmpty())
                            runtime.service.approveAndRun(retry.approval).join()
                            assertEquals(RunStatus.SUCCEEDED, runtime.service.state.value.status)
                            assertEquals(Triple(provider, "test-model", "snapshot-B"), captures.last())
                            assertEquals(1, http.size)
                            assertFalse(http.single().body.contains("snapshot-B"))
                        }
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }

    @Test
    fun `Codex and custom OpenAI route direct chat models to independent gateways`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-ai-provider-routing-test-").toRealPath()
            val openAiModels = mutableListOf<String>()
            val codexModels = mutableListOf<String>()
            try {
                AiPlatformRuntime
                    .open(
                        root,
                        openAiGateway = { model ->
                            openAiModels += model
                            AiAnalysisGateway { request -> AnalysisResult(request.sessionId, model, "Custom result", emptyList()) }
                        },
                        openAiAvailable = { true },
                        codexGateway = { model ->
                            codexModels += model
                            AiAnalysisGateway { request -> AnalysisResult(request.sessionId, model, "Codex result", emptyList()) }
                        },
                        codexAvailable = { true },
                    ).use { runtime ->
                        assertTrue(openAiModels.isEmpty())
                        assertTrue(codexModels.isEmpty())
                        val broker = ProviderBroker(runtime.providers)
                        for ((provider, model, summary) in listOf(
                            Triple("codex", "free/gpt-6.1-sol", "Codex result"),
                            Triple("openai", "custom/chat-model", "Custom result"),
                        )) {
                            val events = broker.run(providerRequest(provider, model), setOf("openai", "codex")).toList()
                            assertEquals(ProviderEvent.Completed, events.last())
                            val result = events.filterIsInstance<ProviderEvent.StructuredResult>().single().result
                            assertEquals(model, result.model)
                            assertEquals(summary, result.summary)
                            assertEquals(
                                setOf(ProviderCapability.STRUCTURED_ANALYSIS),
                                runtime.providers.descriptor(provider)?.capabilities,
                            )
                        }
                        assertEquals(listOf("custom/chat-model"), openAiModels)
                        assertEquals(listOf("free/gpt-6.1-sol"), codexModels)
                    }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `Codex and custom OpenAI availability is independent without fallback`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-ai-provider-availability-test-").toRealPath()
            var codexAvailable = true
            try {
                AiPlatformRuntime
                    .open(
                        root,
                        openAiGateway = { error("E_FIXTURE_NO_REQUEST") },
                        openAiAvailable = { !codexAvailable },
                        codexGateway = { error("E_FIXTURE_NO_REQUEST") },
                        codexAvailable = { codexAvailable },
                    ).use { runtime ->
                        val broker = ProviderBroker(runtime.providers)
                        for (available in listOf(true, false)) {
                            codexAvailable = available
                            val availableProvider = if (available) "codex" else "openai"
                            val unavailableProvider = if (available) "openai" else "codex"
                            assertTrue(runtime.providers.discover(availableProvider) is ProviderAvailability.Available)
                            assertEquals(
                                ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_CREDENTIAL),
                                runtime.providers.discover(unavailableProvider),
                            )
                            assertEquals(
                                listOf(ProviderEvent.Failure("E_PROVIDER_MISSING_CREDENTIAL", false)),
                                broker.run(providerRequest(unavailableProvider, "gpt-6.1-sol"), setOf("openai", "codex")).toList(),
                            )
                        }
                    }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `omitted Codex hooks retain the legacy OpenAI gateway and availability defaults`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-ai-provider-defaults-test-").toRealPath()
            val models = mutableListOf<String>()
            var available = false
            try {
                AiPlatformRuntime
                    .open(
                        root,
                        openAiGateway = { model ->
                            models += model
                            AiAnalysisGateway { request -> AnalysisResult(request.sessionId, model, "Legacy result", emptyList()) }
                        },
                        openAiAvailable = { available },
                    ).use { runtime ->
                        assertEquals(
                            ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_CREDENTIAL),
                            runtime.providers.discover("codex"),
                        )
                        available = true
                        assertTrue(runtime.providers.discover("codex") is ProviderAvailability.Available)
                        val events =
                            ProviderBroker(runtime.providers)
                                .run(providerRequest("codex", "gpt-6.1-sol"), setOf("codex"))
                                .toList()
                        assertEquals(ProviderEvent.Completed, events.last())
                        assertEquals(listOf("gpt-6.1-sol"), models)
                    }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `opening runtime and discovering other providers never reads saved Claude credentials`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-ai-claude-callback-test-").toRealPath()
            val calls = AtomicInteger()
            try {
                AiPlatformRuntime
                    .open(
                        root,
                        openAiGateway = { error("E_FIXTURE_NO_REQUEST") },
                        openAiAvailable = { true },
                        claudeEnvironment = {
                            calls.incrementAndGet()
                            error("E_FIXTURE_CLAUDE_NOT_SELECTED")
                        },
                    ).use { runtime ->
                        assertEquals(0, calls.get())
                        assertTrue(runtime.providers.discover("openai") is ProviderAvailability.Available)
                        assertTrue(runtime.providers.discover("codex") is ProviderAvailability.Available)
                        assertEquals(0, calls.get())
                    }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `isolated bootstrap preflight cancel and reopen never make implicit provider requests`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-ai-bootstrap-test-").toRealPath()
            val calls = AtomicInteger()

            fun open() =
                AiPlatformRuntime.open(root.resolve("profile"), openAiAvailable = { true }, openAiGateway = { model ->
                    AiAnalysisGateway { request ->
                        calls.incrementAndGet()
                        AnalysisResult(request.sessionId, model, "Fake result", emptyList())
                    }
                })
            try {
                open().use { runtime ->
                    assertEquals(0, calls.get())
                    assertEquals(
                        8,
                        runtime.registry
                            .scan()
                            .entries.size,
                    )
                    assertFailsWith<IllegalStateException> { open() }
                    val context =
                        createBusinessContext(
                            "frame",
                            "frame evidence",
                            listOf(PerformanceEvidence("frame-1", "frame-selection", "Known frame", "{\"durationNs\":123}")),
                            true,
                        )
                    val prepared =
                        runtime.service.prepare(
                            "isolated-conversation",
                            context.request,
                            "aps.main",
                            "openai",
                            "fake-model",
                            setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
                        )
                    assertEquals(RunStatus.AWAITING_APPROVAL, runtime.service.state.value.status)
                    assertEquals(0, calls.get())
                    assertEquals("openai", prepared.providerId)
                    assertEquals("fake-model", prepared.model)
                    val preview = checkNotNull(prepared.analysisPreview)
                    assertEquals(context.request.sessionId, preview.sessionId)
                    assertEquals(
                        "{}",
                        preview.evidence
                            .single()
                            .structuredPayload,
                    )
                    runtime.service.cancel()
                    assertEquals(RunStatus.CANCELLED, runtime.service.state.value.status)
                    assertEquals(0, calls.get())
                }
                open().use { reopened ->
                    assertEquals(0, calls.get())
                    assertTrue(reopened.history.list(100).all { it.turns.all { turn -> turn.status.terminal } })
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `close retains profile lock and history until cancelled provider cleanup completes`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-ai-shutdown-test-").toRealPath()
            val started = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val cleanupFinished = CompletableDeferred<Unit>()
            val runtime =
                AiPlatformRuntime.open(root.resolve("profile"), openAiAvailable = { true }, openAiGateway = {
                    AiAnalysisGateway {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                releaseCleanup.await()
                                cleanupFinished.complete(Unit)
                            }
                        }
                    }
                })
            try {
                val context =
                    createBusinessContext(
                        "frame",
                        "frame evidence",
                        listOf(
                            PerformanceEvidence("frame-1", "frame-selection", "Known frame", "{\"durationNs\":123}"),
                        ),
                        true,
                    )
                val prepared =
                    runtime.service.prepare(
                        "shutdown-conversation",
                        context.request,
                        "aps.main",
                        "openai",
                        "fake-model",
                        setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
                    )
                runtime.service.approveAndRun(prepared.approval)
                withTimeout(5000) { started.await() }
                val closing = async(Dispatchers.IO) { runtime.close() }
                withTimeout(5000) { cleanupStarted.await() }
                assertFalse(closing.isCompleted)
                assertFalse(cleanupFinished.isCompleted)
                // The live store is still usable until the old run has fully unwound.
                assertTrue(runtime.history.load("shutdown-conversation") != null)
                assertEquals(
                    "E_AI_PROFILE_BUSY",
                    assertFailsWith<IllegalStateException> {
                        AiPlatformRuntime.open(
                            root.resolve("profile"),
                            openAiAvailable = { false },
                            openAiGateway = { error("no request") },
                        )
                    }.message,
                )
                releaseCleanup.complete(Unit)
                withTimeout(5000) { closing.await() }
                assertTrue(cleanupFinished.isCompleted)
                AiPlatformRuntime
                    .open(
                        root.resolve("profile"),
                        openAiAvailable = { false },
                        openAiGateway = { error("no request") },
                    ).use { reopened ->
                        assertTrue(reopened.history.list().all { it.turns.all { turn -> turn.status.terminal } })
                    }
                runtime.shutdown()
            } finally {
                releaseCleanup.complete(Unit)
                runtime.shutdown()
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `settings fixture is isolated and malformed persisted edits do not replace applied settings`() {
        val preferences = Preferences.userRoot().node("com/androidperformancestudio/tests/${UUID.randomUUID()}")
        try {
            val store = AiPlatformSettingsStore(preferences)
            assertEquals(AiPlatformSettings(), store.load())
            val fresh = preferences.node("fresh-malformed")
            fresh.put("provider", "unregistered-provider")
            assertEquals(AiPlatformSettings(), AiPlatformSettingsStore(fresh).load())
            store.save(AiPlatformSettings("openai", "test-model", true))
            assertEquals(AiPlatformSettings("openai", "test-model", true), store.load())
            preferences.put("provider", "unregistered-provider")
            assertEquals(AiPlatformSettings("openai", "test-model", true), store.load())
            assertTrue(
                preferences.keys().none {
                    it.contains("token", true) ||
                        it.contains("secret", true) ||
                        it.contains("credential", true)
                },
            )
        } finally {
            val parent = preferences.parent()
            preferences.removeNode()
            parent.flush()
        }
    }

    @Test
    fun `Agent selection and script policy remain applied while fresh malformed Agent identities fail closed`() {
        val preferences = Preferences.userRoot().node("com/androidperformancestudio/tests/${UUID.randomUUID()}")
        try {
            val store = AiPlatformSettingsStore(preferences)
            val selected = AiPlatformSettings("codex", "gpt-5.3-codex", agentId = "imported.agent", allowScripts = true)
            store.save(selected)
            assertEquals(selected, store.load())
            preferences.put("agent", "../unsafe")
            assertEquals(selected, store.load())
            val fresh = preferences.node("fresh-malformed")
            fresh.put("agent", "../unsafe")
            assertEquals(AiPlatformSettings(), AiPlatformSettingsStore(fresh).load())
        } finally {
            val parent = preferences.parent()
            preferences.removeNode()
            parent.flush()
        }
    }

    private fun providerRequest(
        providerId: String,
        model: String,
    ) = ProviderRunRequest(
        "provider-routing-run",
        1,
        "aps.main",
        providerId,
        model,
        createChatContext().request.copy(userQuestion = "Hello"),
        setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
    )
}
