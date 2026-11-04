package com.androidperformancestudio.aiplatform.app

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiHttpMethod
import com.androidperformancestudio.ai.AiHttpRequest
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.OpenAiAnalysisGateway
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.model.AiProviderDriver
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.providers.GatewayAnalysisProvider
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.providers.ProviderRegistry
import com.androidperformancestudio.aiplatform.providers.StructuredAnalysisCodec
import com.androidperformancestudio.aiplatform.runtime.AgentPolicyException
import com.androidperformancestudio.aiplatform.runtime.MainAgentOrchestrator
import com.androidperformancestudio.aiplatform.storage.ConversationRepository
import com.androidperformancestudio.aiplatform.storage.redactAnalysisText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline fixtures always run; only liveProviderAcceptance can contact a real provider. */
@Execution(ExecutionMode.SAME_THREAD)
class LiveProviderAcceptanceTest {
    @Test
    fun liveProviderAcceptance() =
        runBlocking {
            val selection = liveSelection(System::getenv)
            assumeTrue(selection != null, "Opt-in requires APS_RUN_LIVE_PROVIDER=true, APS_LIVE_PROVIDER and APS_LIVE_MODEL")
            val selected = checkNotNull(selection)
            val receipt = Receipt(selected)
            try {
                val receiptPath = System.getenv("APS_LIVE_RECEIPT")?.let(Path::of)
                receiptPath?.let(::checkReceiptPath)
                withTimeout(65_000) { runLive(selected, receipt) }
                receipt.resultStatus = "PASSED"
                emitReceipt(receipt, receiptPath)
            } catch (_: Throwable) {
                receipt.resultStatus = "FAILED"
                // Never attach a provider exception, its cause, result, request or environment to JUnit.
                try {
                    emitReceipt(receipt, System.getenv("APS_LIVE_RECEIPT")?.let(Path::of))
                } catch (_: Throwable) {
                    println("APS_LIVE_ACCEPTANCE_RECEIPT_UNAVAILABLE")
                }
                throw AssertionError("E_LIVE_ACCEPTANCE_FAILED")
            }
        }

    @Test
    fun `offline selection requires the exact opt in and an explicit provider and model`() {
        assertEquals(null, liveSelection(emptyMap<String, String>()::get))
        assertEquals(null, liveSelection(mapOf("APS_RUN_LIVE_PROVIDER" to "false")::get))
        val valid =
            mapOf(
                "APS_RUN_LIVE_PROVIDER" to "true",
                "APS_LIVE_PROVIDER" to "openai",
                "APS_LIVE_MODEL" to "explicit-model",
            )
        assertEquals("explicit-model", liveSelection(valid::get)?.model)
        for (key in listOf("APS_LIVE_PROVIDER", "APS_LIVE_MODEL")) {
            assertFailsWith<IllegalStateException> { liveSelection((valid - key)::get) }
        }
        val invalidSelections =
            listOf(
                "APS_RUN_LIVE_PROVIDER" to "TRUE",
                "APS_LIVE_PROVIDER" to "unknown",
                "APS_LIVE_MODEL" to "model\nsecret",
                "APS_LIVE_MODEL" to "sk-fixture-only-not-a-real-credential",
            )
        for ((key, value) in invalidSelections) {
            assertFailsWith<IllegalStateException> { liveSelection((valid + (key to value))::get) }
        }
        assertFailsWith<IllegalStateException> { liveSelection((valid + ("APS_LIVE_PROVIDER" to "codex"))::get) }
        assertEquals(
            "explicit-codex-model",
            liveSelection((valid + mapOf("APS_LIVE_PROVIDER" to "codex", "APS_LIVE_MODEL" to "explicit-codex-model"))::get)?.model,
        )
    }

    @Test
    fun `offline real approval invokes once and rejects unknown evidence and candidates`() =
        runBlocking {
            // This factory has no HTTP client; it is deliberately separate from the live factory.
            val root = Files.createTempDirectory("aps-live-fixture-").toRealPath()
            try {
                AiPlatformRuntime.open(root.resolve("app"), { error("E_FIXTURE_NO_NETWORK") }, { false }).use { app ->
                    for (variant in listOf("valid", "evidence", "candidate", "empty", "confidence", "oversize")) {
                        var calls = 0
                        val selection = Selection("openai", "fixture-model")
                        val receipt = Receipt(selection)
                        val driver =
                            GatewayAnalysisProvider(
                                "openai",
                                selection.model,
                                AiAnalysisGateway {
                                    calls++
                                    val result = fixtureResult(it, selection.model)
                                    when (variant) {
                                        "evidence" ->
                                            result.copy(
                                                findings =
                                                    result.findings.map { f ->
                                                        f.copy(performanceEvidenceIds = listOf("invented"))
                                                    },
                                            )
                                        "candidate" ->
                                            result.copy(
                                                findings =
                                                    result.findings.map { f ->
                                                        f.copy(sourceCandidateIds = listOf("invented"))
                                                    },
                                            )
                                        "empty" -> result.copy(findings = emptyList())
                                        "confidence" ->
                                            result.copy(
                                                findings =
                                                    result.findings.map { f ->
                                                        f.copy(analysisConfidence = Float.NaN)
                                                    },
                                            )
                                        "oversize" -> result.copy(summary = "x".repeat(4097))
                                        else -> result
                                    }
                                },
                            )
                        val store = MemoryConversations()
                        val guarded = GuardedDriver(driver, selection, receipt)
                        val orchestrator = MainAgentOrchestrator(app.registry, ProviderBroker(ProviderRegistry(listOf(guarded))), store)
                        val prepared =
                            orchestrator.prepare(
                                "fixture-$variant",
                                syntheticRequest(),
                                AGENT,
                                "openai",
                                selection.model,
                                PERMISSIONS,
                            )
                        assertEquals(
                            "E_APPROVAL_REQUIRED",
                            assertFailsWith<AgentPolicyException> { orchestrator.run(prepared.approval.runId).toList() }.code,
                        )
                        assertFailsWith<AgentPolicyException> { orchestrator.approve(prepared.approval.copy(generation = 99)) }
                        assertEquals(0, calls)
                        orchestrator.approve(prepared.approval)
                        val events = orchestrator.run(prepared.approval.runId).toList().map { it.event }
                        assertEquals(1, calls)
                        assertEquals(1, receipt.brokerInvocations)
                        if (variant == "valid") {
                            assertEquals(ProviderEvent.Completed, events.last())
                            assertEquals(
                                RunStatus.SUCCEEDED,
                                store
                                    .load("fixture-$variant")
                                    ?.turns
                                    ?.single()
                                    ?.status,
                            )
                            assertFailsWith<AgentPolicyException> { orchestrator.run(prepared.approval.runId).toList() }
                            assertEquals(1, calls)
                        } else {
                            assertTrue(events.last() is ProviderEvent.Failure)
                            assertFalse(events.any { it is ProviderEvent.StructuredResult || it == ProviderEvent.Completed })
                        }
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `offline wire qualification distinguishes final output commentary and refusals`() {
        val valid =
            Json
                .parseToJsonElement(
                    """{"output":[
            {"type":"reasoning","summary":[]},
            {"type":"message","phase":"commentary","content":[{"type":"output_text","text":"Inspecting"}]},
            {"type":"message","phase":"final_answer","content":[{"type":"output_text","text":"{\"summary\":"},{"type":"output_text","text":"\"ok\"}"}]}
        ]}""",
                ).jsonObject
        assertEquals("""{"summary":"ok"}""", wireFinalOutput(valid))
        for (bad in listOf(
            """{"output":[{"type":"message","phase":"commentary","content":[{"type":"output_text","text":"Not final"}]}]}""",
            """{"output":[{"type":"message","phase":"final_answer","content":[{"type":"refusal","refusal":"No"}]}]}""",
            """{"output":[{"type":"function_call","name":"unsafe"}]}""",
        )) {
            assertFailsWith<IllegalStateException> { wireFinalOutput(Json.parseToJsonElement(bad).jsonObject) }
        }
    }

    @Test
    fun `offline receipt admits only fixed metadata and rejects raw runtime output`() {
        val receipt = Receipt(Selection("claude", "fixture-model"))
        receipt.runtimeVersion = versionMetadata("claude", "2.1.308 (Claude Code)")
        assertFailsWith<IllegalStateException> { versionMetadata("claude", "secret-bearing raw output") }
        val json = receipt.json()
        assertEquals(
            setOf(
                "provider",
                "runtimeVersion",
                "model",
                "discoveryStatus",
                "invocationStatus",
                "resultStatus",
                "brokerInvocationCount",
                "startedEventCount",
                "structuredResultCount",
                "transportAttemptCount",
            ),
            Json.parseToJsonElement(json).jsonObject.keys,
        )
        assertFalse(json.contains("secret-bearing"))
        assertFalse(json.contains(syntheticRequest().evidence.single().summary))
        assertEquals(JsonNull, Json.parseToJsonElement(json).jsonObject["transportAttemptCount"])
    }

    @Test
    fun `offline HTTP body cap cancels overflow without retaining raw failure text`() {
        for (size in listOf(16_384, 16_385)) {
            var cancelled = false
            val body = LimitedBody()
            body.onSubscribe(
                object : java.util.concurrent.Flow.Subscription {
                    override fun request(count: Long) = Unit

                    override fun cancel() {
                        cancelled = true
                    }
                },
            )
            body.onNext(listOf(ByteBuffer.wrap(ByteArray(size))))
            body.onComplete()
            if (size == 16_384) {
                assertEquals(
                    size,
                    body
                        .getBody()
                        .toCompletableFuture()
                        .get()
                        .size,
                )
                assertFalse(cancelled)
            } else {
                val failure =
                    assertFailsWith<java.util.concurrent.ExecutionException> {
                        body.getBody().toCompletableFuture().get()
                    }
                assertEquals("E_LIVE_HTTP_OUTPUT_LIMIT", failure.cause?.message)
                assertTrue(cancelled)
            }
        }
    }

    private suspend fun runLive(
        selection: Selection,
        receipt: Receipt,
    ) {
        safeCheck(
            listOf("jdk.httpclient.HttpClient.log", "javax.net.debug").all { System.getProperty(it).isNullOrBlank() },
        )
        // Key values go only into the existing API client, never argv, settings or receipts.
        val credentialName = if (selection.provider == "claude") "ANTHROPIC_API_KEY" else "OPENAI_API_KEY"
        safeCheck(!System.getenv(credentialName).isNullOrBlank())
        val root = Files.createTempDirectory("aps-live-acceptance-").toRealPath()
        try {
            val transport = BoundedResponsesTransport(selection, receipt)
            AiPlatformRuntime
                .open(
                    root.resolve("app"),
                    openAiAvailable = { !System.getenv("OPENAI_API_KEY").isNullOrBlank() },
                    openAiGateway = { model ->
                        safeCheck(model == selection.model && selection.provider != "claude")
                        OpenAiAnalysisGateway(
                            OpenAiResponsesClient(
                                checkNotNull(System.getenv("OPENAI_API_KEY")),
                                model,
                                transport = transport,
                                maxOutputTokens = MAX_OUTPUT_TOKENS,
                            ),
                        )
                    },
                ).use { app ->
                    val agent = app.registry.resolve(ExtensionKind.AGENT, AGENT).manifest
                    safeCheck(agent.allowedAgents.isEmpty() && agent.allowedTools.isEmpty() && agent.scripts.isEmpty())
                    val selectedDescriptor = app.providers.descriptor(selection.provider)
                    safeCheck(selectedDescriptor != null && ProviderCapability.STRUCTURED_ANALYSIS in selectedDescriptor.capabilities)
                    // Use actual app registration, including conditional Codex support, not a test stand-in.
                    val appBroker = ProviderBroker(app.providers)
                    val registered =
                        object : AiProviderDriver {
                            override val descriptor: ProviderDescriptor = checkNotNull(selectedDescriptor)

                            override suspend fun discover(): ProviderAvailability = app.providers.discover(selection.provider)

                            override fun run(request: ProviderRunRequest): Flow<ProviderEvent> =
                                appBroker.run(request, setOf(selection.provider))
                        }
                    val guarded = GuardedDriver(registered, selection, receipt)
                    when (val availability = guarded.discover()) {
                        is ProviderAvailability.Unavailable -> {
                            receipt.discoveryStatus = availability.code.name
                            error("E_LIVE_PROVIDER_UNAVAILABLE")
                        }
                        is ProviderAvailability.Available -> {
                            receipt.runtimeVersion = versionMetadata(selection.provider, availability.runtimeVersion)
                            receipt.discoveryStatus = "AVAILABLE"
                        }
                    }
                    val store = MemoryConversations()
                    val orchestrator = MainAgentOrchestrator(app.registry, ProviderBroker(ProviderRegistry(listOf(guarded))), store)
                    val prepared =
                        orchestrator.prepare(CONVERSATION, syntheticRequest(), AGENT, selection.provider, selection.model, PERMISSIONS)
                    safeCheck(prepared.evidenceCount == 1 && prepared.candidateCount == 0 && prepared.payloadBytes <= MAX_INPUT_BYTES)
                    safeCheck(prepared.approval.permissions == PERMISSIONS && receipt.brokerInvocations == 0)
                    safeCheck(
                        store
                            .load(CONVERSATION)
                            ?.turns
                            ?.single()
                            ?.status == RunStatus.AWAITING_APPROVAL,
                    )
                    // APS_RUN_LIVE_PROVIDER=true is the explicit host decision for this exact prepared payload.
                    orchestrator.approve(prepared.approval)
                    val events = orchestrator.run(prepared.approval.runId).toList()
                    safeCheck(events.all { it.runId == prepared.approval.runId && it.generation == prepared.approval.generation })
                    safeCheck(events.none { it.event is ProviderEvent.Failure || it.event == ProviderEvent.Cancelled })
                    val accepted = events.mapNotNull { (it.event as? ProviderEvent.StructuredResult)?.result }.single()
                    verifyResult(accepted, syntheticRequest(), selection.model)
                    safeCheck(events.count { it.event == ProviderEvent.Completed } == 1)
                    safeCheck(receipt.brokerInvocations == 1 && receipt.startedEvents == 1 && receipt.structuredResults == 1)
                    safeCheck(selection.provider == "claude" || receipt.transportAttempts == 1)
                    val stored = checkNotNull(store.load(CONVERSATION)).turns.single()
                    safeCheck(
                        stored.status == RunStatus.SUCCEEDED && stored.evidenceIds == listOf(EVIDENCE) && stored.candidateIds.isEmpty(),
                    )
                    safeCheck(app.history.list().isEmpty())
                    receipt.invocationStatus = "COMPLETED"
                }
        } finally {
            safeCheck(root.toFile().deleteRecursively())
        }
    }

    private class GuardedDriver(
        private val delegate: AiProviderDriver,
        private val selection: Selection,
        private val receipt: Receipt,
    ) : AiProviderDriver {
        override val descriptor: ProviderDescriptor = delegate.descriptor

        override suspend fun discover(): ProviderAvailability = delegate.discover()

        override fun run(request: ProviderRunRequest): Flow<ProviderEvent> =
            flow {
                safeCheck(receipt.brokerInvocations == 0)
                safeCheck(request.agentId == AGENT && request.providerId == selection.provider && request.model == selection.model)
                safeCheck(request.analysis == syntheticRequest() && request.grantedPermissions == PERMISSIONS)
                receipt.brokerInvocations++
                receipt.invocationStatus = "BROKER_ENTERED"
                val bounded =
                    request.copy(
                        budget =
                            request.budget.copy(
                                timeoutMillis = minOf(request.budget.timeoutMillis, PROVIDER_TIMEOUT_MILLIS),
                                maxInputBytes = minOf(request.budget.maxInputBytes, MAX_INPUT_BYTES),
                                maxOutputBytes = minOf(request.budget.maxOutputBytes, MAX_OUTPUT_BYTES),
                            ),
                    )
                withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                    delegate.run(bounded).collect { event ->
                        when (event) {
                            ProviderEvent.Started -> {
                                receipt.startedEvents++
                                safeCheck(receipt.startedEvents == 1)
                                receipt.invocationStatus = "STARTED"
                            }
                            is ProviderEvent.StructuredResult -> {
                                receipt.structuredResults++
                                safeCheck(receipt.structuredResults == 1)
                                verifyResult(event.result, request.analysis, selection.model)
                            }
                            is ProviderEvent.Usage ->
                                safeCheck(
                                    event.inputTokens in 0..2048 && event.outputTokens in 0..MAX_OUTPUT_TOKENS.toLong(),
                                )
                            is ProviderEvent.TextDelta, is ProviderEvent.ToolCall, is ProviderEvent.ApprovalRequired ->
                                error(
                                    "E_LIVE_UNEXPECTED_EVENT",
                                )
                            else -> Unit
                        }
                        emit(event)
                    }
                }
            }
    }

    /** No normal app history/result projection is written, even temporarily. */
    private class MemoryConversations : ConversationRepository {
        private val records = linkedMapOf<String, ConversationRecord>()

        override fun save(record: ConversationRecord) {
            records[record.id] = record
        }

        override fun load(id: String): ConversationRecord? = records[id]

        override fun list(
            limit: Int,
            offset: Int,
        ): List<ConversationRecord> = records.values.drop(offset).take(limit)

        override fun recoverInterrupted(): Int = 0
    }

    private data class Selection(
        val provider: String,
        val model: String,
    )

    private class Receipt(
        private val selection: Selection,
    ) {
        var runtimeVersion: String? = null
        var discoveryStatus: String = "NOT_CHECKED"
        var invocationStatus: String = "NOT_INVOKED"
        var resultStatus: String = "FAILED"
        var brokerInvocations: Int = 0
        var startedEvents: Int = 0
        var structuredResults: Int = 0
        var transportAttempts: Int = 0

        fun json(): String =
            buildJsonObject {
                put("provider", selection.provider)
                put("runtimeVersion", runtimeVersion?.let(::JsonPrimitive) ?: JsonNull)
                put("model", selection.model)
                put("discoveryStatus", discoveryStatus)
                put("invocationStatus", invocationStatus)
                put("resultStatus", resultStatus)
                put("brokerInvocationCount", brokerInvocations)
                put("startedEventCount", startedEvents)
                put("structuredResultCount", structuredResults)
                put("transportAttemptCount", if (selection.provider == "claude") JsonNull else JsonPrimitive(transportAttempts))
            }.toString()
    }

    /** Existing public Responses client/gateway, with test-only transport resource/store limits. */
    private class BoundedResponsesTransport(
        private val selection: Selection,
        private val receipt: Receipt,
    ) : AiHttpTransport {
        private val client =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()

        override suspend fun execute(request: AiHttpRequest): AiHttpResponse {
            safeCheck(receipt.transportAttempts == 0)
            safeCheck(request.url == OpenAiResponsesClient.DEFAULT_ENDPOINT && request.method == AiHttpMethod.POST)
            val original = Json.parseToJsonElement(request.body).jsonObject
            safeCheck(original.getValue("model").jsonPrimitive.content == selection.model)
            safeCheck(original.getValue("input").jsonPrimitive.content == syntheticRequest().payloadText())
            safeCheck(original["max_output_tokens"]?.jsonPrimitive?.intOrNull == MAX_OUTPUT_TOKENS)
            safeCheck(original["store"]?.jsonPrimitive?.booleanOrNull == false)
            safeCheck(original["tools"] == JsonArray(emptyList()))
            safeCheck(original["tool_choice"]?.jsonPrimitive?.content == "none")
            safeCheck(original["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull == false)
            val builder =
                HttpRequest
                    .newBuilder(URI.create(OpenAiResponsesClient.DEFAULT_ENDPOINT))
                    .timeout(Duration.ofMillis(PROVIDER_TIMEOUT_MILLIS))
                    .POST(HttpRequest.BodyPublishers.ofString(request.body))
            request.headers.forEach(builder::header)
            receipt.transportAttempts++
            val response =
                suspendCancellableCoroutine<HttpResponse<ByteArray>> { continuation ->
                    val future = client.sendAsync(builder.build(), HttpResponse.BodyHandler { LimitedBody() })
                    continuation.invokeOnCancellation { future.cancel(true) }
                    future.whenComplete { result, failure ->
                        if (failure ==
                            null
                        ) {
                            continuation.resumeWith(Result.success(result))
                        } else {
                            continuation.resumeWith(Result.failure(failure))
                        }
                    }
                }
            safeCheck(response.statusCode() in 200..299)
            val text = response.body().decodeToString(throwOnInvalidSequence = true)
            val envelope = Json.parseToJsonElement(text).jsonObject
            safeCheck(envelope["status"]?.jsonPrimitive?.content == "completed")
            safeCheck(envelope["model"]?.jsonPrimitive?.content == selection.model)
            safeCheck(envelope["error"] == null || envelope["error"] == JsonNull)
            val usage = envelope.getValue("usage").jsonObject
            safeCheck(usage["input_tokens"]?.jsonPrimitive?.intOrNull in 1..2048)
            safeCheck(usage["output_tokens"]?.jsonPrimitive?.intOrNull in 1..MAX_OUTPUT_TOKENS)
            val output = wireFinalOutput(envelope)
            StructuredAnalysisCodec.decode(
                ProviderRunRequest("wire-check", 0, AGENT, selection.provider, selection.model, syntheticRequest(), PERMISSIONS, BUDGET),
                output,
            )
            safeCheck(envelope["output_text"] == null || envelope["output_text"]?.jsonPrimitive?.content == output)
            return AiHttpResponse(response.statusCode(), text)
        }
    }

    private class LimitedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val delegate = HttpResponse.BodySubscribers.ofByteArray()
        private lateinit var subscription: java.util.concurrent.Flow.Subscription
        private var bytes = 0L
        private var failed = false

        override fun getBody(): CompletionStage<ByteArray> = delegate.body

        override fun onSubscribe(value: java.util.concurrent.Flow.Subscription) {
            subscription = value
            delegate.onSubscribe(value)
        }

        override fun onNext(items: List<ByteBuffer>) {
            if (failed) return
            bytes += items.sumOf { it.remaining().toLong() }
            if (bytes > 16_384) {
                failed = true
                subscription.cancel()
                delegate.onError(IllegalStateException("E_LIVE_HTTP_OUTPUT_LIMIT"))
            } else {
                delegate.onNext(items)
            }
        }

        override fun onError(failure: Throwable) {
            if (!failed) delegate.onError(IllegalStateException("E_LIVE_HTTP_FAILURE"))
        }

        override fun onComplete() {
            if (!failed) delegate.onComplete()
        }
    }

    private companion object {
        const val AGENT = "aps.simpleperf"
        const val CONVERSATION = "live-synthetic-acceptance"
        const val EVIDENCE = "synthetic-hotspot-1"
        const val MAX_INPUT_BYTES = 2048
        const val MAX_OUTPUT_BYTES = 4096
        const val MAX_OUTPUT_TOKENS = 512
        const val PROVIDER_TIMEOUT_MILLIS = 30_000L
        val PERMISSIONS = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK)
        val BUDGET = ResourceBudget(PROVIDER_TIMEOUT_MILLIS, MAX_INPUT_BYTES, MAX_OUTPUT_BYTES)

        fun safeCheck(condition: Boolean) {
            check(condition) { "E_LIVE_ACCEPTANCE_CONTRACT" }
        }

        fun wireFinalOutput(envelope: JsonObject): String {
            val output = envelope.getValue("output").jsonArray.map { it.jsonObject }
            output.forEach { safeCheck(it["type"]?.jsonPrimitive?.content in setOf("message", "reasoning")) }
            val messages = output.filter { it["type"]?.jsonPrimitive?.content == "message" }
            messages.forEach { safeCheck(it["phase"]?.jsonPrimitive?.contentOrNull in setOf(null, "commentary", "final_answer")) }
            val final = messages.filter { it["phase"]?.jsonPrimitive?.contentOrNull == "final_answer" }
            val candidates = final.ifEmpty { messages.filter { it["phase"]?.jsonPrimitive?.contentOrNull == null } }
            safeCheck(candidates.size == 1)
            val text =
                candidates.single().getValue("content").jsonArray.joinToString("") { content ->
                    val part = content.jsonObject
                    safeCheck(part["type"]?.jsonPrimitive?.content == "output_text")
                    part.getValue("text").jsonPrimitive.content
                }
            safeCheck(text.isNotBlank())
            return text
        }

        fun liveSelection(environment: (String) -> String?): Selection? {
            val enabled = environment("APS_RUN_LIVE_PROVIDER")
            if (enabled == null || enabled == "false") return null
            safeCheck(enabled == "true")
            val provider = environment("APS_LIVE_PROVIDER")
            val model = environment("APS_LIVE_MODEL")
            safeCheck(provider in setOf("openai", "codex", "claude"))
            safeCheck(model != null && model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")))
            safeCheck(redactAnalysisText(checkNotNull(model)) == model)
            safeCheck(model != environment(if (provider == "claude") "ANTHROPIC_API_KEY" else "OPENAI_API_KEY"))
            safeCheck(
                provider != "codex" ||
                    checkNotNull(model).matches(Regex("(?:[A-Za-z0-9][A-Za-z0-9._:-]*-)?codex(?:[-.:][A-Za-z0-9._:-]+)?")),
            )
            return Selection(checkNotNull(provider), checkNotNull(model))
        }

        fun syntheticRequest(): AnalysisRequest =
            AnalysisRequest(
                AnalysisSessionId("aps-synthetic-acceptance"),
                ProfilerKind.SIMPLEPERF,
                AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "One synthetic CPU hotspot, not a project capture"),
                listOf(
                    PerformanceEvidence(
                        EVIDENCE,
                        "simpleperf-hotspots",
                        "Synthetic selected work accounts for 80 of 100 samples",
                        "{\"synthetic\":true,\"selectedSamples\":80,\"totalSamples\":100}",
                    ),
                ),
                emptyList(),
                "analysis-v1",
                "evidence-v1",
                "Return one short finding about the supplied synthetic sample share, not a proven bug. " +
                    "Reference only synthetic-hotspot-1; sourceCandidateIds must be empty. " +
                    "No files, paths, tools or extra context. Summary <=160 characters, title <=80, " +
                    "explanation <=240, recommendation <=160. Keep total output below 512 tokens.",
            )

        fun verifyResult(
            result: AnalysisResult,
            request: AnalysisRequest,
            model: String,
        ) {
            safeCheck(result.sessionId == request.sessionId && result.model == model)
            safeCheck(result.summary.isNotBlank() && result.summary.length <= 160 && result.findings.size == 1)
            val finding = result.findings.single()
            safeCheck(finding.id.value.matches(Regex("[A-Za-z0-9._-]{1,64}")))
            safeCheck(finding.performanceEvidenceIds == listOf(EVIDENCE) && finding.sourceCandidateIds.isEmpty())
            safeCheck(finding.analysisConfidence.isFinite() && finding.analysisConfidence in 0f..1f)
            safeCheck(finding.title.isNotBlank() && finding.title.length <= 80)
            safeCheck(finding.explanation.isNotBlank() && finding.explanation.length <= 240)
            safeCheck(finding.recommendation.isNotBlank() && finding.recommendation.length <= 160)
        }

        fun fixtureResult(
            request: AnalysisRequest,
            model: String,
        ): AnalysisResult =
            AnalysisResult(
                request.sessionId,
                model,
                "Synthetic sample concentration",
                listOf(
                    AnalysisFinding(
                        AnalysisFindingId("synthetic-finding"),
                        AnalysisSeverity.INFO,
                        "Selected work",
                        "80 of 100 synthetic samples are selected.",
                        "Measure a representative workload before concluding.",
                        0.6f,
                        listOf(EVIDENCE),
                        emptyList(),
                    ),
                ),
            )

        fun versionMetadata(
            provider: String,
            version: String,
        ): String {
            val pattern =
                if (provider == "claude") {
                    Regex("[0-9]+\\.[0-9]+\\.[0-9]+ \\(Claude Code\\)")
                } else {
                    Regex("(?:openai-responses-v1|codex-responses-v1)")
                }
            safeCheck(version.length <= 80 && pattern.matches(version))
            return version
        }

        fun checkReceiptPath(path: Path) {
            safeCheck(path.isAbsolute && Files.isDirectory(path.parent, NOFOLLOW_LINKS) && !Files.exists(path, NOFOLLOW_LINKS))
            var current = path.root
            for (part in path) {
                current = current.resolve(part)
                safeCheck(!Files.isSymbolicLink(current))
            }
        }

        fun emitReceipt(
            receipt: Receipt,
            path: Path?,
        ) {
            val text = receipt.json()
            if (path != null) {
                checkReceiptPath(path)
                val attributes =
                    if (Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
                        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    } else {
                        emptyArray()
                    }
                Files.newByteChannel(path, setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS), *attributes).use { channel ->
                    val bytes = ByteBuffer.wrap((text + "\n").toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                }
            }
            println("APS_LIVE_ACCEPTANCE_RECEIPT $text")
        }
    }
}
