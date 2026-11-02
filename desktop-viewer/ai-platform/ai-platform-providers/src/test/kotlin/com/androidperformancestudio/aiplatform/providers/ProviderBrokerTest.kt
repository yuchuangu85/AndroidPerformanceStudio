package com.androidperformancestudio.aiplatform.providers

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiRequestException
import com.androidperformancestudio.ai.AiRequestFailureKind
import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.AiProviderDriver
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderBrokerTest {
    @Test
    fun `only exact connection revision failure is propagated from gateway factories`() =
        runBlocking {
            for ((message, expected) in listOf(
                "E_PROVIDER_CONNECTION_CHANGED" to "E_PROVIDER_CONNECTION_CHANGED",
                "E_PROVIDER_CONNECTION_CHANGED private credential" to "E_PROVIDER_FAILURE",
            )) {
                val provider =
                    object : AiProviderDriver {
                        override val descriptor = ProviderDescriptor("openai", setOf(ProviderCapability.STRUCTURED_ANALYSIS))

                        override suspend fun discover() = ProviderAvailability.Available("test")

                        override fun run(request: ProviderRunRequest): Flow<ProviderEvent> = error(message)
                    }
                val events = broker(provider).run(request(), setOf("openai")).toList()
                assertFailure(events, expected)
                assertFalse(events.toString().contains("private credential"))
            }
        }

    @Test
    fun `valid gateway result has one bounded structured result and completion`() =
        runBlocking {
            var calls = 0
            val provider =
                gateway {
                    calls++
                    result(it)
                }
            val events = broker(provider).run(request(), setOf("openai")).toList()

            assertEquals(1, calls)
            assertEquals(ProviderEvent.Started, events.first())
            assertEquals(ProviderEvent.Completed, events.last())
            assertEquals(
                "evidence-1",
                assertIs<ProviderEvent.StructuredResult>(events[1])
                    .result.findings
                    .single()
                    .performanceEvidenceIds
                    .single(),
            )
            assertEquals(3, events.size)
        }

    @Test
    fun `provider IDs cannot register twice`() {
        val provider = gateway { result(it) }
        assertFailsWith<IllegalArgumentException> { ProviderRegistry(listOf(provider, provider)) }
    }

    @Test
    fun `unknown provider never falls back to registered gateway`() =
        runBlocking {
            var calls = 0
            val provider =
                gateway {
                    calls++
                    result(it)
                }
            val registry = ProviderRegistry(listOf(provider))
            assertEquals(null, registry.descriptor("missing"))
            assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_RUNTIME), registry.discover("missing"))
            val events = ProviderBroker(registry).run(request().copy(providerId = "missing"), setOf("missing")).toList()
            assertFailure(events, "E_PROVIDER_UNREGISTERED")
            assertEquals(0, calls)
        }

    @Test
    fun `provider allowlist rejects before discovery or gateway`() =
        runBlocking {
            var discoveryCalls = 0
            var gatewayCalls = 0
            val provider =
                GatewayAnalysisProvider(
                    "openai",
                    "test-model",
                    AiAnalysisGateway {
                        gatewayCalls++
                        result(it)
                    },
                    availability = {
                        discoveryCalls++
                        ProviderAvailability.Available("test-v1")
                    },
                )
            assertFailure(broker(provider).run(request(), emptySet()).toList(), "E_PROVIDER_NOT_ALLOWED")
            assertEquals(0, discoveryCalls)
            assertEquals(0, gatewayCalls)
        }

    @Test
    fun `unavailable provider state is visible without execution`() =
        runBlocking {
            var calls = 0
            val provider =
                GatewayAnalysisProvider(
                    "openai",
                    "test-model",
                    AiAnalysisGateway {
                        calls++
                        result(it)
                    },
                    availability = { ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_CREDENTIAL) },
                )
            assertFailure(broker(provider).run(request(), setOf("openai")).toList(), "E_PROVIDER_MISSING_CREDENTIAL")
            assertEquals(0, calls)
        }

    @Test
    fun `discovery exceptions do not leak secret config or paths`() =
        runBlocking {
            val provider =
                GatewayAnalysisProvider(
                    "openai",
                    "test-model",
                    AiAnalysisGateway { result(it) },
                    availability = { error("secret-token /Users/private/config") },
                )
            val registry = ProviderRegistry(listOf(provider))
            assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.MALFORMED_CONFIG), registry.discover("openai"))
            assertFailure(ProviderBroker(registry).run(request(), setOf("openai")).toList(), "E_PROVIDER_MALFORMED_CONFIG")
        }

    @Test
    fun `unsupported capability cannot execute`() =
        runBlocking {
            var calls = 0
            val provider =
                gateway {
                    calls++
                    result(it)
                }
            assertFailure(
                broker(provider).run(request(), setOf("openai"), setOf(ProviderCapability.RESUME)).toList(),
                "E_PROVIDER_CAPABILITY",
            )
            assertEquals(0, calls)
        }

    @Test
    fun `network and evidence permissions deny before gateway`() =
        runBlocking {
            var calls = 0
            val provider =
                gateway {
                    calls++
                    result(it)
                }
            assertFailure(
                broker(provider).run(request().copy(grantedPermissions = setOf(Permission.READ_EVIDENCE)), setOf("openai")).toList(),
                "E_NETWORK_PERMISSION",
            )
            assertFailure(
                broker(provider).run(request().copy(grantedPermissions = setOf(Permission.PROVIDER_NETWORK)), setOf("openai")).toList(),
                "E_EVIDENCE_PERMISSION",
            )
            assertEquals(0, calls)
        }

    @Test
    fun `local-only driver does not require provider network grant`() =
        runBlocking {
            val provider = GatewayAnalysisProvider("openai", "test-model", AiAnalysisGateway { result(it) }, localOnly = true)
            val events =
                broker(
                    provider,
                ).run(request().copy(grantedPermissions = setOf(Permission.READ_EVIDENCE)), setOf("openai")).toList()
            assertEquals(ProviderEvent.Completed, events.last())
        }

    @Test
    fun `source candidates and snippets require independent grants`() =
        runBlocking {
            var calls = 0
            val provider =
                gateway {
                    calls++
                    result(it)
                }
            val request = request().let { it.copy(analysis = it.analysis.copy(sourceCandidates = listOf(candidate("private source")))) }
            assertFailure(broker(provider).run(request, setOf("openai")).toList(), "E_SOURCE_PERMISSION")
            assertFailure(
                broker(
                    provider,
                ).run(
                    request.copy(grantedPermissions = request.grantedPermissions + Permission.READ_SOURCE_CANDIDATES),
                    setOf("openai"),
                ).toList(),
                "E_SOURCE_SNIPPET_PERMISSION",
            )
            assertEquals(0, calls)
            val accepted =
                request.copy(
                    grantedPermissions = request.grantedPermissions + Permission.READ_SOURCE_CANDIDATES + Permission.SEND_SOURCE_SNIPPETS,
                )
            assertEquals(ProviderEvent.Completed, broker(provider).run(accepted, setOf("openai")).toList().last())
            assertEquals(1, calls)
        }

    @Test
    fun `input limits count UTF8 bytes and never silently truncate`() =
        runBlocking {
            var calls = 0
            val provider =
                gateway {
                    calls++
                    result(it)
                }
            val request =
                request().let {
                    it.copy(
                        analysis =
                            it.analysis.copy(
                                evidence =
                                    listOf(
                                        it.analysis.evidence
                                            .single()
                                            .copy(summary = "界".repeat(300)),
                                    ),
                            ),
                        budget = ResourceBudget(maxInputBytes = 1000),
                    )
                }
            assertFailure(broker(provider).run(request, setOf("openai")).toList(), "E_INPUT_LIMIT")
            assertEquals(0, calls)
        }

    @Test
    fun `empty selection and duplicate evidence never become summary fallback`() =
        runBlocking {
            var calls = 0
            val provider =
                gateway {
                    calls++
                    result(it)
                }
            val request = request()
            assertFailure(
                broker(provider).run(request.copy(analysis = request.analysis.copy(evidence = emptyList())), setOf("openai")).toList(),
                "E_EVIDENCE_LIMIT",
            )
            assertFailure(
                broker(provider)
                    .run(
                        request.copy(
                            analysis =
                                request.analysis.copy(
                                    evidence =
                                        request.analysis.evidence + request.analysis.evidence,
                                ),
                        ),
                        setOf("openai"),
                    ).toList(),
                "E_EVIDENCE_ID",
            )
            assertEquals(0, calls)
        }

    @Test
    fun `model mismatch is rejected before provider calls`() =
        runBlocking {
            var calls = 0
            val provider =
                gateway {
                    calls++
                    result(it)
                }
            assertFailure(broker(provider).run(request().copy(model = "another-model"), setOf("openai")).toList(), "E_PROVIDER_MODEL")
            assertEquals(0, calls)
        }

    @Test
    fun `result session model evidence candidate and confidence are independently validated`() =
        runBlocking {
            val changes: List<Pair<String, (AnalysisResult) -> AnalysisResult>> =
                listOf(
                    "E_RESULT_SESSION" to { it.copy(sessionId = AnalysisSessionId("other")) },
                    "E_RESULT_MODEL" to { it.copy(model = "other-model") },
                    "E_RESULT_EVIDENCE" to
                        { it.copy(findings = listOf(it.findings.single().copy(performanceEvidenceIds = listOf("invented")))) },
                    "E_RESULT_CANDIDATE" to
                        { it.copy(findings = listOf(it.findings.single().copy(sourceCandidateIds = listOf("invented")))) },
                    "E_FINDING_CONFIDENCE" to { it.copy(findings = listOf(it.findings.single().copy(analysisConfidence = Float.NaN))) },
                    "E_RESULT_EVIDENCE" to
                        {
                            it.copy(
                                findings = listOf(it.findings.single().copy(performanceEvidenceIds = listOf("evidence-1", "evidence-1"))),
                            )
                        },
                )
            for ((code, transform) in changes) {
                val events = broker(gateway { transform(result(it)) }).run(request(), setOf("openai")).toList()
                assertFailure(events, code)
                assertTrue(events.none { it is ProviderEvent.StructuredResult })
            }
        }

    @Test
    fun `oversized output is rejected before structured result is published`() =
        runBlocking {
            val provider = gateway { result(it).copy(summary = "界".repeat(1000)) }
            val events = broker(provider).run(request().copy(budget = ResourceBudget(maxOutputBytes = 1000)), setOf("openai")).toList()
            assertFailure(events, "E_OUTPUT_LIMIT")
            assertTrue(events.none { it is ProviderEvent.StructuredResult })
        }

    @Test
    fun `timeout cancels gateway and produces retryable stable error`() =
        runBlocking {
            var cancelled = false
            val provider =
                gateway {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            val events = broker(provider).run(request().copy(budget = ResourceBudget(timeoutMillis = 25)), setOf("openai")).toList()
            assertFailure(events, "E_PROVIDER_TIMEOUT", retryable = true)
            assertTrue(cancelled)
        }

    @Test
    fun `caller cancellation propagates without reporting success or retry`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            var cancelled = false
            val provider =
                gateway {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            val events = mutableListOf<ProviderEvent>()
            val job = launch { broker(provider).run(request(), setOf("openai")).toList(events) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(cancelled)
            assertFalse(events.any { it is ProviderEvent.Failure || it == ProviderEvent.Completed })
        }

    @Test
    fun `authentication rate limit and unknown exceptions only expose stable errors`() =
        runBlocking {
            val errors =
                listOf(
                    Triple(AiRequestException(AiRequestFailureKind.AUTHENTICATION), "E_PROVIDER_AUTHENTICATION", false),
                    Triple(AiRequestException(AiRequestFailureKind.RATE_LIMIT), "E_PROVIDER_RATE_LIMIT", true),
                    Triple(IllegalStateException("sk-secret-token PRIVATE SOURCE"), "E_PROVIDER_FAILURE", false),
                )
            for ((failure, code, retryable) in errors) {
                val events = broker(gateway { throw failure }).run(request(), setOf("openai")).toList()
                assertFailure(events, code, retryable)
                assertFalse(events.toString().contains("secret"))
            }
        }

    @Test
    fun `broker validates fake driver result and forbids unexpected tool execution`() =
        runBlocking {
            val invalid =
                result(request().analysis).let {
                    it.copy(findings = listOf(it.findings.single().copy(sourceCandidateIds = listOf("fake"))))
                }
            assertFailure(
                broker(
                    driver {
                        emit(ProviderEvent.Started)
                        emit(ProviderEvent.StructuredResult(invalid))
                    },
                ).run(request(), setOf("openai"))
                    .toList(),
                "E_RESULT_CANDIDATE",
            )
            assertFailure(
                broker(
                    driver {
                        emit(ProviderEvent.Started)
                        emit(ProviderEvent.ToolCall("call", "arbitrary-shell"))
                    },
                ).run(request(), setOf("openai"))
                    .toList(),
                "E_TOOL_UNSUPPORTED",
            )
        }

    @Test
    fun `completion without result and silent streams cannot succeed`() =
        runBlocking {
            assertFailure(broker(driver { emit(ProviderEvent.Completed) }).run(request(), setOf("openai")).toList(), "E_PROVIDER_PROTOCOL")
            assertFailure(broker(driver { emit(ProviderEvent.Started) }).run(request(), setOf("openai")).toList(), "E_PROVIDER_INCOMPLETE")
        }

    @Test
    fun `broker stops consuming after terminal completion`() =
        runBlocking {
            var lateEventAttempted = false
            val provider =
                driver {
                    emit(ProviderEvent.Started)
                    emit(ProviderEvent.StructuredResult(result(request().analysis)))
                    emit(ProviderEvent.Completed)
                    lateEventAttempted = true
                    emit(ProviderEvent.TextDelta("late secret"))
                }
            val events = broker(provider).run(request(), setOf("openai")).toList()
            assertEquals(ProviderEvent.Completed, events.last())
            assertFalse(lateEventAttempted)
            assertEquals(3, events.size)
        }

    @Test
    fun `streaming output has a cumulative byte budget`() =
        runBlocking {
            val provider =
                driver(setOf(ProviderCapability.STRUCTURED_ANALYSIS, ProviderCapability.STREAMING)) {
                    emit(ProviderEvent.Started)
                    emit(ProviderEvent.TextDelta("界".repeat(200)))
                    emit(ProviderEvent.TextDelta("界".repeat(200)))
                }
            val events = broker(provider).run(request().copy(budget = ResourceBudget(maxOutputBytes = 1000)), setOf("openai")).toList()
            assertFailure(events, "E_OUTPUT_LIMIT")
            assertEquals(1, events.filterIsInstance<ProviderEvent.TextDelta>().size)
        }

    @Test
    fun `registered capability snapshots cannot be mutated through caller collections`() =
        runBlocking {
            val capabilities = mutableSetOf(ProviderCapability.STRUCTURED_ANALYSIS)
            val provider = driver(capabilities) { error("must not execute") }
            val registry = ProviderRegistry(listOf(provider))
            capabilities.add(ProviderCapability.RESUME)
            val descriptor = registry.descriptor("openai")!!
            assertFalse(ProviderCapability.RESUME in descriptor.capabilities)
            assertFailure(
                ProviderBroker(registry).run(request(), setOf("openai"), setOf(ProviderCapability.RESUME)).toList(),
                "E_PROVIDER_CAPABILITY",
            )
        }

    @Test
    fun `unknown provider failure codes cannot relay secrets`() =
        runBlocking {
            val provider = driver { emit(ProviderEvent.Failure("E_SK_SECRET_CREDENTIAL", retryable = false)) }
            val events = broker(provider).run(request(), setOf("openai")).toList()
            assertFailure(events, "E_PROVIDER_FAILURE")
            assertFalse(events.toString().contains("SECRET"))
        }

    @Test
    fun `downstream cancellation is not converted into a provider failure`() =
        runBlocking {
            val provider = gateway { result(it) }
            assertFailsWith<CancellationException> {
                broker(provider).run(request(), setOf("openai")).collect { throw CancellationException("consumer stopped") }
            }
        }

    @Test
    fun `caller timeout remains cancellation rather than provider timeout failure`() =
        runBlocking {
            val events = mutableListOf<ProviderEvent>()
            var cancelled = false
            val provider =
                gateway {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) {
                    broker(provider).run(request().copy(budget = ResourceBudget(timeoutMillis = 2000)), setOf("openai")).toList(events)
                }
            }
            assertTrue(cancelled)
            assertFalse(events.any { it is ProviderEvent.Failure || it == ProviderEvent.Completed })
        }

    @Test
    fun `direct gateway also preserves caller timeout cancellation`() =
        runBlocking {
            val events = mutableListOf<ProviderEvent>()
            val provider = gateway { awaitCancellation() }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) {
                    provider.run(request().copy(budget = ResourceBudget(timeoutMillis = 2000))).toList(events)
                }
            }
            assertFalse(events.any { it is ProviderEvent.Failure || it == ProviderEvent.Completed })
        }

    @Test
    fun `empty streaming deltas have a finite event budget`() =
        runBlocking {
            var attempts = 0
            val provider =
                driver(setOf(ProviderCapability.STRUCTURED_ANALYSIS, ProviderCapability.STREAMING)) {
                    emit(ProviderEvent.Started)
                    repeat(MAX_PROVIDER_EVENTS * 2) {
                        attempts++
                        emit(ProviderEvent.TextDelta(""))
                    }
                }
            val events = broker(provider).run(request(), setOf("openai")).toList()
            assertFailure(events, "E_PROVIDER_EVENT_LIMIT")
            assertEquals(MAX_PROVIDER_EVENTS, attempts)
            assertEquals(MAX_PROVIDER_EVENTS - 1, events.filterIsInstance<ProviderEvent.TextDelta>().size)
        }

    @Test
    fun `zero token usage events also have a finite event budget`() =
        runBlocking {
            val provider =
                driver {
                    emit(ProviderEvent.Started)
                    repeat(MAX_PROVIDER_EVENTS * 2) { emit(ProviderEvent.Usage(0, 0)) }
                }
            val events = broker(provider).run(request(), setOf("openai")).toList()
            assertFailure(events, "E_PROVIDER_EVENT_LIMIT")
            assertEquals(MAX_PROVIDER_EVENTS - 1, events.filterIsInstance<ProviderEvent.Usage>().size)
        }

    @Test
    fun `downstream ordinary exceptions preserve original cause instead of becoming provider failures`() =
        runBlocking {
            val failure = IllegalStateException("consumer error must propagate")
            val provider = gateway { result(it) }
            val events = mutableListOf<ProviderEvent>()
            val observed =
                assertFailsWith<IllegalStateException> {
                    broker(provider).run(request(), setOf("openai")).collect {
                        events.add(it)
                        throw failure
                    }
                }
            assertEquals(failure.message, observed.message)
            // Coroutine stacktrace recovery may clone the exception, retaining the original as its cause.
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.take(8).any { it === failure })
            assertEquals(listOf<ProviderEvent>(ProviderEvent.Started), events)
            assertFalse(events.any { it is ProviderEvent.Failure })
        }

    private fun gateway(analyze: suspend (AnalysisRequest) -> AnalysisResult): GatewayAnalysisProvider =
        GatewayAnalysisProvider("openai", "test-model", AiAnalysisGateway { analyze(it) })

    private fun broker(provider: AiProviderDriver): ProviderBroker = ProviderBroker(ProviderRegistry(listOf(provider)))

    private fun driver(
        capabilities: Set<ProviderCapability> = setOf(ProviderCapability.STRUCTURED_ANALYSIS),
        events: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.() -> Unit,
    ): AiProviderDriver =
        object : AiProviderDriver {
            override val descriptor = ProviderDescriptor("openai", capabilities)

            override suspend fun discover(): ProviderAvailability = ProviderAvailability.Available("fake-v1")

            override fun run(request: ProviderRunRequest): Flow<ProviderEvent> = flow(events)
        }

    private fun request(): ProviderRunRequest =
        ProviderRunRequest(
            runId = "run-1",
            generation = 1,
            agentId = "simpleperf",
            providerId = "openai",
            model = "test-model",
            analysis =
                AnalysisRequest(
                    sessionId = AnalysisSessionId("session-1"),
                    originProfiler = ProfilerKind.SIMPLEPERF,
                    scope = AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "selected node"),
                    evidence = listOf(PerformanceEvidence("evidence-1", "simpleperf-node", "hot node", "{}")),
                    sourceCandidates = emptyList(),
                    promptVersion = "v1",
                    payloadPolicyVersion = "v1",
                ),
            grantedPermissions = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
        )

    private fun result(request: AnalysisRequest): AnalysisResult =
        AnalysisResult(
            sessionId = request.sessionId,
            model = "test-model",
            summary = "Verified selected node",
            findings =
                listOf(
                    AnalysisFinding(
                        AnalysisFindingId("finding-1"),
                        AnalysisSeverity.WARNING,
                        "Repeated work",
                        "Selected call has measurable work",
                        "Inspect the selected evidence",
                        0.8f,
                        listOf("evidence-1"),
                        emptyList(),
                    ),
                ),
        )

    private fun candidate(snippet: String?): AiSourceCandidate =
        AiSourceCandidate(
            "candidate-1",
            "src/Screen.kt",
            "render",
            "PROBABLE",
            listOf("symbol match"),
            snippet,
        )

    private fun assertFailure(
        events: List<ProviderEvent>,
        code: String,
        retryable: Boolean = false,
    ) {
        assertEquals(ProviderEvent.Failure(code, retryable), events.last())
        assertFalse(events.contains(ProviderEvent.Completed))
    }
}
