package com.androidperformancestudio.aiplatform.runtime

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
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.model.AiProviderDriver
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionManifest
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.providers.ProviderRegistry
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.PlatformDirectories
import com.androidperformancestudio.aiplatform.registry.RegistryEntry
import com.androidperformancestudio.aiplatform.script.ScriptAudit
import com.androidperformancestudio.aiplatform.script.ScriptRunRequest
import com.androidperformancestudio.aiplatform.script.ScriptRunResult
import com.androidperformancestudio.aiplatform.storage.ExecutionKind
import com.androidperformancestudio.aiplatform.storage.ExecutionPhase
import com.androidperformancestudio.aiplatform.storage.SqliteConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentGraphOrchestrationTest {
    @Test
    fun `connection change between graph calls fails closed without second provider dispatch`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 1)
                fixture.agent("custom.child")
                var revision = "private-graph-connection-A"
                val runtime = fixture.runtime(connectionRevision = { revision })
                val prepared = fixture.prepare(runtime)
                runtime.approve(prepared.approval)
                val events =
                    runtime
                        .run(prepared.approval.runId)
                        .onEach { event ->
                            if (event.timeline?.kind == ExecutionKind.CHILD_AGENT && event.timeline.phase == ExecutionPhase.SUCCEEDED) {
                                revision = "private-graph-connection-B"
                            }
                        }.toList()
                val failure = events.last().event as ProviderEvent.Failure
                assertEquals("E_PROVIDER_CONNECTION_CHANGED", failure.code)
                assertFalse(failure.retryable)
                assertEquals(listOf("custom.child"), fixture.calls.map { it.agentId })
                assertEquals("private-graph-connection-A", fixture.calls.single().connectionRevision)
                assertEquals(
                    RunStatus.PERMANENT_FAILURE,
                    fixture.store
                        .load("root-conversation")!!
                        .turns
                        .single()
                        .status,
                )
                assertTrue(
                    fixture.store.audit("root-conversation").any {
                        it.kind == ExecutionKind.RUN && it.phase == ExecutionPhase.FAILED && it.errorCode == failure.code
                    },
                )
                assertFalse(events.toString().contains("private-graph-connection"))
                assertFalse(
                    fixture.store
                        .audit("root-conversation")
                        .toString()
                        .contains("private-graph-connection"),
                )
                assertEquals(
                    "E_RUN_UNAVAILABLE",
                    assertFailsWith<AgentPolicyException> { runtime.approve(prepared.approval) }.code,
                )
                val retry = fixture.prepare(runtime)
                assertNotEquals(prepared.approval.id, retry.approval.id)
                assertEquals(1, fixture.calls.size)
                runtime.approve(retry.approval)
                runtime.run(retry.approval.runId).toList()
                assertEquals(listOf("custom.child", "custom.child", "custom.root"), fixture.calls.map { it.agentId })
                assertEquals(List(2) { "private-graph-connection-B" }, fixture.calls.drop(1).map { it.connectionRevision })
            }
        }

    @Test
    fun `generic trusted imported graph performs independent calls and persists actual parent records`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 2)
                fixture.agent("custom.child", children = setOf("custom.leaf"), depth = 1)
                fixture.agent("custom.leaf")
                val runtime = fixture.runtime()
                val prepared = fixture.prepare(runtime)
                assertEquals(3, prepared.plannedCallCount)
                assertEquals(3, prepared.plannedProviderCallCount)
                assertEquals(0, prepared.plannedScriptCount)
                assertTrue(fixture.calls.isEmpty())
                runtime.approve(prepared.approval)
                val events = runtime.run(prepared.approval.runId).toList()
                assertEquals(listOf("custom.leaf", "custom.child", "custom.root"), fixture.calls.map { it.agentId })
                assertEquals(
                    3,
                    fixture.calls
                        .map { it.runId }
                        .distinct()
                        .size,
                )
                assertEquals(
                    3,
                    fixture.calls
                        .map { it.analysis.sessionId }
                        .distinct()
                        .size,
                )
                assertTrue(
                    fixture.calls.all {
                        it.providerId == "fake" &&
                            it.model == "model" &&
                            it.analysis.promptVersion == "versioned-system"
                    },
                )
                assertTrue(fixture.calls.all { it.analysis.userQuestion!!.contains("Untrusted imported Agent context") })
                assertTrue(fixture.calls.all { it.analysis.userQuestion!!.contains("Context for ${it.agentId}") })
                val records = fixture.store.list()
                assertEquals(3, records.size)
                assertTrue(records.all { it.turns.single().status == RunStatus.SUCCEEDED })
                val root = records.single { it.id == "root-conversation" }
                val child = records.single { it.parentConversationId == root.id }
                val leaf = records.single { it.parentConversationId == child.id }
                assertEquals("custom.child", fixture.store.result(child.id, child.turns.single().runId)!!.summary)
                assertEquals("custom.leaf", fixture.store.result(leaf.id, leaf.turns.single().runId)!!.summary)
                assertNotEquals(root.analysisSessionId, child.analysisSessionId)
                assertNotEquals(child.analysisSessionId, leaf.analysisSessionId)
                assertTrue(fixture.store.audit(child.id).any { it.kind == ExecutionKind.PROVIDER && it.phase == ExecutionPhase.SUCCEEDED })
                val linked = fixture.store.audit(root.id).filter { it.kind == ExecutionKind.CHILD_AGENT }
                assertEquals(setOf(child.id, leaf.id), linked.map { it.relatedConversationId }.toSet())
                assertTrue(
                    linked.all { event ->
                        records.any {
                            it.id == event.relatedConversationId &&
                                it.turns.single().runId == event.relatedRunId
                        }
                    },
                )
                assertTrue(
                    fixture.store.audit(child.id).filter { it.kind == ExecutionKind.RUN }.all {
                        it.relatedConversationId == null &&
                            it.relatedRunId == null
                    },
                )
                assertEquals("custom.root", events.mapNotNull { (it.event as? ProviderEvent.StructuredResult)?.result }.single().summary)
                assertTrue(events.all { it.runId == prepared.approval.runId })
                assertEquals(
                    2,
                    events.count {
                        it.timeline?.kind == ExecutionKind.CHILD_AGENT &&
                            it.timeline.phase == ExecutionPhase.SUCCEEDED
                    },
                )
            }
        }

    @Test
    fun `validated child and script conclusions reach parent as derived context with unchanged evidence`() =
        runBlocking {
            Fixture().use { fixture ->
                val permissions = grants + Permission.EXECUTE_SCRIPT
                fixture.agent(
                    "custom.root",
                    children = setOf("custom.child"),
                    skills = setOf("custom.script"),
                    depth = 1,
                    permissions = permissions,
                )
                val child = fixture.agent("custom.child")
                val script = fixture.script()
                val scriptRequests = mutableListOf<ScriptRunRequest>()
                val runtime =
                    fixture.runtime(
                        scriptRunner = { request ->
                            scriptRequests += request
                            fixture.scriptResult(request, buildJsonObject { put("conclusion", "script-known-conclusion") })
                        },
                        respond = { request ->
                            fixture.result(request).copy(
                                summary =
                                    if (request.agentId ==
                                        "custom.child"
                                    ) {
                                        "child-known-conclusion"
                                    } else {
                                        "parent synthesis"
                                    },
                            )
                        },
                    )
                val original = analysis().copy(userQuestion = "Explain these metrics")
                val prepared = runtime.prepare("root-conversation", original, "custom.root", "fake", "model", permissions)
                runtime.approve(prepared.approval)
                val events = runtime.run(prepared.approval.runId).toList()
                assertEquals(ProviderEvent.Completed, events.last().event)
                val parentInput = fixture.calls.last().analysis
                assertEquals(original, parentInput.copy(userQuestion = original.userQuestion))
                val question = assertNotNull(parentInput.userQuestion)
                assertTrue(question.startsWith(original.userQuestion!!))
                assertTrue(question.contains("UNTRUSTED derived analysis"))
                assertTrue(question.contains("child-known-conclusion"))
                assertTrue(question.contains("script-known-conclusion"))
                assertTrue(question.contains(child.contentHash))
                assertTrue(question.contains(script.contentHash))
                assertTrue(question.contains(script.manifest.files.getValue("scripts/analyze.py")))
                assertTrue(
                    question.contains(
                        fixture.calls
                            .first()
                            .analysis
                            .payloadText()
                            .toByteArray()
                            .sha256(),
                    ),
                )
                val envelope = PlatformJson.codec.parseToJsonElement(question.substringAfter(DerivedAnalysisPolicy.HEADER)).jsonObject
                assertEquals(prepared.approval.payloadHash, envelope.getValue("approvalHash").jsonPrimitive.content)
                val provenance = (envelope.getValue("contributions") as JsonArray).map { it.jsonObject }
                val scriptProvenance = provenance.single { it.getValue("kind").jsonPrimitive.content == "script" }
                assertEquals(
                    PlatformJson.codec
                        .encodeToString(scriptRequests.single().input)
                        .toByteArray()
                        .sha256(),
                    scriptProvenance.getValue("inputHash").jsonPrimitive.content,
                )
                assertTrue(
                    provenance.all {
                        it
                            .getValue("content")
                            .toString()
                            .toByteArray()
                            .sha256() ==
                            it.getValue("contentHash").jsonPrimitive.content
                    },
                )
                val childProvenance = provenance.single { it.getValue("kind").jsonPrimitive.content == "child-agent" }
                assertEquals(fixture.calls.first().runId, childProvenance.getValue("runId").jsonPrimitive.content)
                val inputBytes =
                    fixture.calls.sumOf {
                        it.analysis
                            .payloadText()
                            .toByteArray()
                            .size
                            .toLong()
                    } +
                        scriptRequests.sumOf {
                            PlatformJson.codec
                                .encodeToString(it.input)
                                .toByteArray()
                                .size
                                .toLong()
                        }
                assertEquals(inputBytes, events.last().budget!!.inputBytes)
                val outputBytes =
                    fixture.calls.sumOf { request ->
                        fixture
                            .result(request)
                            .copy(
                                summary =
                                    if (request.agentId ==
                                        "custom.child"
                                    ) {
                                        "child-known-conclusion"
                                    } else {
                                        "parent synthesis"
                                    },
                            ).toAnalysisJson()
                            .toString()
                            .toByteArray()
                            .size
                            .toLong()
                    } +
                        fixture
                            .scriptResult(
                                scriptRequests.single(),
                                buildJsonObject { put("conclusion", "script-known-conclusion") },
                            ).audit.stdoutBytes
                assertEquals(outputBytes, events.last().budget!!.outputBytes)
                assertEquals(3, events.last().budget!!.invocations)
                assertEquals(
                    listOf(ExecutionPhase.STARTED, ExecutionPhase.SUCCEEDED),
                    fixture.store
                        .audit("root-conversation")
                        .filter {
                            it.kind ==
                                ExecutionKind.SCRIPT
                        }.map { it.phase },
                )
                val frozen =
                    FrozenAgentGraph(fixture.registry).freeze(
                        fixture.registry.resolve(ExtensionKind.AGENT, "custom.root"),
                        original,
                        "fake",
                        permissions,
                        true,
                    )
                assertTrue(frozen.approvalMaterial().contains("aps.derived-analysis.v1"))
            }
        }

    @Test
    fun `derived conclusions redact root snippets and credentials including script object keys`() =
        runBlocking {
            Fixture().use { fixture ->
                val secret = "sk-derivedsecret0123456789"
                val snippet = "fun privateSource() = \"source-body\""
                val permissions =
                    grants + setOf(Permission.EXECUTE_SCRIPT, Permission.READ_SOURCE_CANDIDATES, Permission.SEND_SOURCE_SNIPPETS)
                fixture.agent(
                    "custom.root",
                    children = setOf("custom.child"),
                    skills = setOf("custom.script"),
                    depth = 1,
                    permissions = permissions,
                )
                fixture.agent("custom.child")
                fixture.script()
                val original =
                    analysis().copy(
                        sourceCandidates = listOf(AiSourceCandidate("candidate", "Main.kt", null, "EXACT", emptyList(), snippet)),
                    )
                val runtime =
                    fixture.runtime(
                        sourceAuthorization = { true },
                        scriptRunner = { request ->
                            assertTrue(request.input.candidateIds.isEmpty())
                            fixture.scriptResult(
                                request,
                                buildJsonObject {
                                    put(secret, snippet)
                                    put("api_key", "structured-script-credential")
                                    put("password", 123456789)
                                    put(
                                        "useful signal",
                                        JsonArray(
                                            listOf(
                                                JsonPrimitive("Bearer script-secret-value"),
                                                buildJsonObject { put("quoted", "$snippet $secret") },
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                        respond = { request ->
                            if (request.agentId == "custom.child") {
                                assertTrue(request.analysis.sourceCandidates.isEmpty())
                                fixture.result(request).copy(summary = "useful child conclusion $secret $snippet")
                            } else {
                                fixture.result(request)
                            }
                        },
                    )
                val prepared = runtime.prepare("root-conversation", original, "custom.root", "fake", "model", permissions)
                runtime.approve(prepared.approval)
                assertEquals(
                    ProviderEvent.Completed,
                    runtime
                        .run(prepared.approval.runId)
                        .toList()
                        .last()
                        .event,
                )
                val parent = fixture.calls.last().analysis
                assertEquals(original.sourceCandidates, parent.sourceCandidates)
                val derived = assertNotNull(parent.userQuestion)
                assertTrue(derived.contains("useful child conclusion"))
                assertTrue(derived.contains("useful signal"))
                assertTrue(derived.contains("[credential omitted]"))
                assertTrue(derived.contains("[source omitted]"))
                assertFalse(derived.contains(secret))
                assertFalse(derived.contains("script-secret-value"))
                assertFalse(derived.contains("structured-script-credential"))
                assertFalse(derived.contains("123456789"))
                assertFalse(derived.contains("privateSource"))
                assertFalse(derived.contains("source-body"))
                val childRecord = fixture.store.list().single { it.parentConversationId == "root-conversation" }
                val summary = fixture.store.result(childRecord.id, childRecord.turns.single().runId)!!.summary
                assertFalse(summary.contains(secret))
                assertFalse(summary.contains("privateSource"))
            }
        }

    @Test
    fun `oversized child or script context fails without truncation before parent send`() =
        runBlocking {
            for (script in listOf(false, true)) {
                Fixture().use { fixture ->
                    val permissions = if (script) grants + Permission.EXECUTE_SCRIPT else grants
                    fixture.agent(
                        "custom.root",
                        children = if (script) emptySet() else setOf("custom.child"),
                        skills = if (script) setOf("custom.script") else emptySet(),
                        depth = 1,
                        permissions = permissions,
                    )
                    if (script) fixture.script() else fixture.agent("custom.child")
                    val conclusion = "x".repeat(4_500) + "end-must-not-be-truncated"
                    val runtime =
                        fixture.runtime(
                            scriptRunner =
                                if (script) {
                                    (
                                        { request ->
                                            fixture.scriptResult(request, buildJsonObject { put("conclusion", conclusion) })
                                        }
                                    )
                                } else {
                                    null
                                },
                            respond = { request -> fixture.result(request).copy(summary = conclusion) },
                        )
                    val prepared = fixture.prepare(runtime, permissions)
                    runtime.approve(prepared.approval)
                    val events = runtime.run(prepared.approval.runId).toList()
                    assertEquals("E_DERIVED_INPUT_LIMIT", (events.last().event as ProviderEvent.Failure).code)
                    assertEquals(if (script) 0 else 1, fixture.calls.size)
                    assertTrue(fixture.calls.none { it.agentId == "custom.root" })
                    assertEquals(1, events.last().budget!!.invocations)
                    assertTrue(events.last().budget!!.outputBytes >= conclusion.length)
                }
            }
        }

    @Test
    fun `derived references never enlarge parent candidate allowlist`() =
        runBlocking {
            Fixture().use { fixture ->
                val permissions = grants + Permission.EXECUTE_SCRIPT
                fixture.agent("custom.root", skills = setOf("custom.script"), permissions = permissions)
                fixture.script()
                val runtime =
                    fixture.runtime(
                        scriptRunner = { request ->
                            fixture.scriptResult(request, buildJsonObject { put("suggestedCandidate", "invented-candidate") })
                        },
                        respond = { request ->
                            assertTrue(request.analysis.sourceCandidates.isEmpty())
                            assertTrue(request.analysis.userQuestion!!.contains("invented-candidate"))
                            fixture
                                .result(
                                    request,
                                ).copy(
                                    findings =
                                        listOf(
                                            AnalysisFinding(
                                                AnalysisFindingId("finding"),
                                                AnalysisSeverity.WARNING,
                                                "Title",
                                                "Detail",
                                                "Check",
                                                .5f,
                                                listOf("evidence"),
                                                listOf("invented-candidate"),
                                            ),
                                        ),
                                )
                        },
                    )
                val prepared = fixture.prepare(runtime, permissions)
                runtime.approve(prepared.approval)
                val events = runtime.run(prepared.approval.runId).toList()
                assertEquals("E_RESULT_CANDIDATE", (events.last().event as ProviderEvent.Failure).code)
                assertEquals(1, fixture.calls.size)
                assertEquals(null, fixture.store.result("root-conversation", prepared.approval.runId))
            }
        }

    @Test
    fun `augmented parent bytes consume shared input budget before provider send`() =
        runBlocking {
            Fixture().use { fixture ->
                val budget = ResourceBudget(maxChildDepth = 1, maxInputBytes = 2_000)
                val entry = fixture.agent("custom.root", children = setOf("custom.child"), budget = budget)
                fixture.agent("custom.child")
                val frozen = FrozenAgentGraph(fixture.registry).freeze(entry, analysis(), "fake", grants, false)
                val originalParentBytes =
                    frozen
                        .analysis(analysis())
                        .payloadText()
                        .toByteArray()
                        .size
                val runtime = fixture.runtime { request -> fixture.result(request).copy(summary = "c".repeat(500)) }
                val prepared = fixture.prepare(runtime)
                runtime.approve(prepared.approval)
                val events = runtime.run(prepared.approval.runId).toList()
                assertEquals("E_INPUT_BUDGET", (events.last().event as ProviderEvent.Failure).code)
                val childInput =
                    fixture.calls
                        .single()
                        .analysis
                        .payloadText()
                        .toByteArray()
                        .size
                assertTrue(childInput + originalParentBytes < budget.maxInputBytes)
                assertEquals(childInput.toLong(), events.last().budget!!.inputBytes)
                assertEquals("custom.child", fixture.calls.single().agentId)
            }
        }

    @Test
    fun `derived context respects original question limit and refuses redaction key collisions`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 1)
                fixture.agent("custom.child")
                val runtime = fixture.runtime()
                val prepared =
                    runtime.prepare(
                        "root-conversation",
                        analysis().copy(userQuestion = "q".repeat(3_500)),
                        "custom.root",
                        "fake",
                        "model",
                        grants,
                    )
                runtime.approve(prepared.approval)
                assertEquals(
                    "E_DERIVED_INPUT_LIMIT",
                    (
                        runtime
                            .run(prepared.approval.runId)
                            .toList()
                            .last()
                            .event as ProviderEvent.Failure
                    ).code,
                )
                assertEquals(1, fixture.calls.size)
            }
            Fixture().use { fixture ->
                val permissions = grants + Permission.EXECUTE_SCRIPT
                fixture.agent("custom.root", skills = setOf("custom.script"), permissions = permissions)
                fixture.script()
                val runtime =
                    fixture.runtime(scriptRunner = { request ->
                        fixture.scriptResult(
                            request,
                            buildJsonObject {
                                put("sk-firstsecret0123456789", "first")
                                put("sk-secondsecret0123456789", "second")
                            },
                        )
                    })
                val prepared = fixture.prepare(runtime, permissions)
                runtime.approve(prepared.approval)
                assertEquals(
                    "E_DERIVED_REDACTION_COLLISION",
                    (
                        runtime
                            .run(prepared.approval.runId)
                            .toList()
                            .last()
                            .event as ProviderEvent.Failure
                    ).code,
                )
                assertTrue(fixture.calls.isEmpty())
                assertTrue(
                    fixture.store.audit("root-conversation").any {
                        it.kind == ExecutionKind.SCRIPT &&
                            it.phase == ExecutionPhase.FAILED
                    },
                )
            }
        }

    @Test
    fun `revoked descendant fails closed before dispatch and during result acceptance`() =
        runBlocking {
            for (duringCall in listOf(false, true)) {
                Fixture().use { fixture ->
                    fixture.agent("custom.root", children = setOf("custom.child"), depth = 1)
                    val child = fixture.agent("custom.child")
                    val runtime =
                        fixture.runtime { request ->
                            fixture.registry.revoke(child.key)
                            fixture.result(request)
                        }
                    val prepared = fixture.prepare(runtime)
                    runtime.approve(prepared.approval)
                    if (!duringCall) {
                        fixture.registry.revoke(child.key)
                        assertEquals(
                            "E_AGENT_CHANGED",
                            assertFailsWith<AgentPolicyException> { runtime.run(prepared.approval.runId).toList() }.code,
                        )
                        assertTrue(fixture.calls.isEmpty())
                    } else {
                        val events = runtime.run(prepared.approval.runId).toList()
                        assertEquals("E_AGENT_CHANGED", (events.last().event as ProviderEvent.Failure).code)
                        assertEquals(1, fixture.calls.size)
                        assertFalse(
                            events.any {
                                it.timeline?.kind == ExecutionKind.CHILD_AGENT &&
                                    it.timeline.phase == ExecutionPhase.SUCCEEDED
                            },
                        )
                        assertTrue(fixture.store.list().all { it.turns.single().status == RunStatus.PERMANENT_FAILURE })
                    }
                }
            }
        }

    @Test
    fun `cycle ancestor depth and aggregate call cap are rejected during freeze`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 3)
                fixture.agent("custom.child", children = setOf("custom.root"), depth = 3)
                assertEquals("E_CHILD_CYCLE", assertFailsWith<AgentPolicyException> { fixture.prepare(fixture.runtime()) }.code)
            }
            Fixture().use { fixture ->
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 3)
                fixture.agent("custom.child", children = setOf("custom.leaf"), depth = 0)
                fixture.agent("custom.leaf")
                assertEquals("E_CHILD_DEPTH", assertFailsWith<AgentPolicyException> { fixture.prepare(fixture.runtime()) }.code)
            }
            Fixture().use { fixture ->
                val children = (1..16).map { "custom.child$it" }.toSet()
                fixture.agent("custom.root", children = children, depth = 1)
                children.forEach { fixture.agent(it) }
                assertEquals("E_CALL_BUDGET", assertFailsWith<AgentPolicyException> { fixture.prepare(fixture.runtime()) }.code)
                assertTrue(fixture.calls.isEmpty())
            }
        }

    @Test
    fun `untrusted child and disallowed provider never fallback`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 1)
                fixture.agent("custom.child", trusted = false)
                assertEquals("E_AGENT_UNAVAILABLE", assertFailsWith<AgentPolicyException> { fixture.prepare(fixture.runtime()) }.code)
                assertTrue(fixture.calls.isEmpty())
            }
            Fixture().use { fixture ->
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 1)
                fixture.agent("custom.child", providers = setOf("other"))
                assertEquals("E_PROVIDER_NOT_ALLOWED", assertFailsWith<AgentPolicyException> { fixture.prepare(fixture.runtime()) }.code)
                assertTrue(fixture.calls.isEmpty())
            }
        }

    @Test
    fun `wrong child references cannot be persisted or relabeled as execution success`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 1)
                fixture.agent("custom.child")
                val runtime =
                    fixture.runtime { request ->
                        fixture
                            .result(
                                request,
                            ).copy(
                                findings =
                                    listOf(
                                        AnalysisFinding(
                                            AnalysisFindingId("finding"),
                                            AnalysisSeverity.WARNING,
                                            "Title",
                                            "Detail",
                                            "Check",
                                            .5f,
                                            listOf("invented"),
                                            emptyList(),
                                        ),
                                    ),
                            )
                    }
                val prepared = fixture.prepare(runtime)
                runtime.approve(prepared.approval)
                val events = runtime.run(prepared.approval.runId).toList()
                assertEquals("E_RESULT_EVIDENCE", (events.last().event as ProviderEvent.Failure).code)
                assertEquals(1, fixture.calls.size)
                assertFalse(events.any { it.timeline?.kind == ExecutionKind.CHILD_AGENT && it.timeline.phase == ExecutionPhase.SUCCEEDED })
                assertTrue(fixture.store.list().all { fixture.store.result(it.id, it.turns.single().runId) == null })
            }
        }

    @Test
    fun `frozen child and script package content cannot change after approval`() =
        runBlocking {
            for (script in listOf(false, true)) {
                Fixture().use { fixture ->
                    val permissions = if (script) grants + Permission.EXECUTE_SCRIPT else grants
                    fixture.agent(
                        "custom.root",
                        children = if (script) emptySet() else setOf("custom.child"),
                        skills = if (script) setOf("custom.script") else emptySet(),
                        depth = 1,
                        permissions = permissions,
                    )
                    val entry = if (script) fixture.script() else fixture.agent("custom.child")
                    val runtime = fixture.runtime(scriptRunner = if (script) ({ request -> fixture.scriptResult(request) }) else null)
                    val prepared = fixture.prepare(runtime, permissions)
                    runtime.approve(prepared.approval)
                    Files.writeString(entry.directory.resolve(if (script) "scripts/analyze.py" else "prompt.md"), "changed content")
                    assertFailsWith<Exception> { runtime.run(prepared.approval.runId).toList() }
                    assertTrue(fixture.calls.isEmpty())
                    assertEquals(
                        RunStatus.PERMANENT_FAILURE,
                        fixture.store
                            .load("root-conversation")!!
                            .turns
                            .single()
                            .status,
                    )
                }
            }
        }

    @Test
    fun `child drops ungranted snippets and live source revocation rejects its result`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                val permissions = grants + Permission.READ_SOURCE_CANDIDATES + Permission.SEND_SOURCE_SNIPPETS
                fixture.agent("custom.root", children = setOf("custom.child"), depth = 1, permissions = permissions)
                fixture.agent("custom.child", permissions = grants + Permission.READ_SOURCE_CANDIDATES)
                var authorized = true
                val runtime =
                    fixture.runtime(sourceAuthorization = { authorized }, respond = { request ->
                        assertTrue(request.analysis.sourceCandidates.all { it.sourceSnippet == null })
                        authorized = false
                        fixture.result(request)
                    })
                val analysis =
                    analysis().copy(
                        sourceCandidates = listOf(AiSourceCandidate("candidate", "Main.kt", null, "EXACT", emptyList(), "private source")),
                    )
                val prepared = runtime.prepare("root-conversation", analysis, "custom.root", "fake", "model", permissions)
                runtime.approve(prepared.approval)
                val events = runtime.run(prepared.approval.runId).toList()
                assertEquals("E_SOURCE_UNAUTHORIZED", (events.last().event as ProviderEvent.Failure).code)
                assertEquals(1, fixture.calls.size)
                assertTrue(fixture.store.list().all { fixture.store.result(it.id, it.turns.single().runId) == null })
                assertNotNull(analysis.sourceCandidates.single().sourceSnippet)
            }
        }

    @Test
    fun `script callback receives frozen declared path intersected grants and bounded budgets`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.agent("custom.root", skills = setOf("custom.script"), permissions = grants + Permission.EXECUTE_SCRIPT)
                fixture.script()
                val scripts = mutableListOf<ScriptRunRequest>()
                val runtime =
                    fixture.runtime(scriptRunner = { request ->
                        scripts += request
                        fixture.scriptResult(request)
                    })
                val prepared = fixture.prepare(runtime, grants + Permission.EXECUTE_SCRIPT)
                assertEquals(2, prepared.plannedCallCount)
                assertEquals(1, prepared.plannedProviderCallCount)
                assertEquals(1, prepared.plannedScriptCount)
                runtime.approve(prepared.approval)
                val events = runtime.run(prepared.approval.runId).toList()
                val script = scripts.single()
                assertEquals("scripts/analyze.py", script.scriptPath)
                assertEquals(setOf(Permission.READ_EVIDENCE, Permission.EXECUTE_SCRIPT), script.grantedPermissions)
                assertEquals(8_192, script.budget.maxInputBytes)
                assertEquals(setOf("evidence"), script.input.evidenceIds)
                assertTrue(script.input.payload.isNotEmpty())
                assertEquals(1, fixture.calls.size)
                assertEquals(ProviderEvent.Completed, events.last().event)
                assertEquals(2, events.last().budget!!.invocations)
            }
        }

    @Test
    fun `scripts require host permission and runner with no fallback`() =
        runBlocking {
            for (runnerAvailable in listOf(false, true)) {
                Fixture().use { fixture ->
                    fixture.agent("custom.root", skills = setOf("custom.script"), permissions = grants + Permission.EXECUTE_SCRIPT)
                    fixture.script()
                    var scripts = 0
                    val runner: (suspend (ScriptRunRequest) -> ScriptRunResult)? =
                        if (runnerAvailable) {
                            (
                                { request ->
                                    scripts++
                                    fixture.scriptResult(request)
                                }
                            )
                        } else {
                            null
                        }
                    val runtime = fixture.runtime(scriptRunner = runner)
                    val permissions = if (runnerAvailable) grants else grants + Permission.EXECUTE_SCRIPT
                    val error = assertFailsWith<AgentPolicyException> { fixture.prepare(runtime, permissions) }
                    assertEquals(if (runnerAvailable) "E_SCRIPT_PERMISSION" else "E_SCRIPT_UNAVAILABLE", error.code)
                    assertEquals(0, scripts)
                    assertTrue(fixture.calls.isEmpty())
                }
            }
        }

    @Test
    fun `host script qualification is required at prepare before any provider or script call`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.agent("custom.root", skills = setOf("custom.script"), permissions = grants + Permission.EXECUTE_SCRIPT)
                fixture.script()
                var scripts = 0
                val discovered = mutableListOf<ScriptRuntime>()
                val runtime =
                    fixture.runtime(
                        scriptRunner = { request ->
                            scripts++
                            fixture.scriptResult(request)
                        },
                        scriptSupport = {
                            discovered += it
                            false
                        },
                    )
                assertEquals(
                    "E_SANDBOX_UNAVAILABLE",
                    assertFailsWith<AgentPolicyException> {
                        fixture.prepare(
                            runtime,
                            grants + Permission.EXECUTE_SCRIPT,
                        )
                    }.code,
                )
                assertEquals(listOf(ScriptRuntime.PYTHON), discovered)
                assertEquals(0, scripts)
                assertTrue(fixture.calls.isEmpty())
                assertTrue(fixture.store.list().isEmpty())
            }
        }

    @Test
    fun `script result references and post callback trust are revalidated`() =
        runBlocking {
            for (revoke in listOf(false, true)) {
                Fixture().use { fixture ->
                    fixture.agent("custom.root", skills = setOf("custom.script"), permissions = grants + Permission.EXECUTE_SCRIPT)
                    val skill = fixture.script()
                    val runtime =
                        fixture.runtime(scriptRunner = { request ->
                            if (revoke) fixture.registry.revoke(skill.key)
                            val result = fixture.scriptResult(request)
                            if (revoke) {
                                result
                            } else {
                                result.copy(
                                    output =
                                        JsonObject(
                                            result.output + ("evidenceIds" to JsonArray(listOf(JsonPrimitive("invented")))),
                                        ),
                                )
                            }
                        })
                    val prepared = fixture.prepare(runtime, grants + Permission.EXECUTE_SCRIPT)
                    runtime.approve(prepared.approval)
                    val events = runtime.run(prepared.approval.runId).toList()
                    assertEquals(
                        if (revoke) "E_EXTENSION_CHANGED" else "E_SCRIPT_OUTPUT_SCHEMA",
                        (events.last().event as ProviderEvent.Failure).code,
                    )
                    assertTrue(fixture.calls.isEmpty())
                }
            }
        }

    @Test
    fun `aggregate provider input and output budgets do not reset per child`() =
        runBlocking {
            for (inputBound in listOf(false, true)) {
                Fixture().use { fixture ->
                    val budget =
                        ResourceBudget(
                            maxChildDepth = 1,
                            maxInputBytes =
                                if (inputBound) {
                                    analysis().payloadText().toByteArray().size +
                                        500
                                } else {
                                    16_384
                                },
                            maxOutputBytes = 1_200,
                        )
                    fixture.agent("custom.root", children = setOf("custom.child"), budget = budget)
                    fixture.agent("custom.child")
                    val runtime = fixture.runtime { request -> fixture.result(request).copy(summary = "x".repeat(700)) }
                    val prepared = fixture.prepare(runtime)
                    runtime.approve(prepared.approval)
                    val events = runtime.run(prepared.approval.runId).toList()
                    assertEquals(
                        if (inputBound) "E_INPUT_BUDGET" else "E_OUTPUT_BUDGET",
                        (events.last().event as ProviderEvent.Failure).code,
                    )
                    assertEquals(if (inputBound) 1 else 2, fixture.calls.size)
                    assertEquals(
                        RunStatus.PERMANENT_FAILURE,
                        fixture.store
                            .load("root-conversation")!!
                            .turns
                            .single()
                            .status,
                    )
                }
            }
        }

    @Test
    fun `cancelled child and cancelled script propagate cancellation without late success`() =
        runBlocking {
            for (script in listOf(false, true)) {
                Fixture().use { fixture ->
                    val started = CompletableDeferred<Unit>()
                    var cancelled = false
                    val permissions = if (script) grants + Permission.EXECUTE_SCRIPT else grants
                    fixture.agent(
                        "custom.root",
                        children = if (script) emptySet() else setOf("custom.child"),
                        skills = if (script) setOf("custom.script") else emptySet(),
                        depth = 1,
                        permissions = permissions,
                    )
                    if (script) fixture.script() else fixture.agent("custom.child")
                    val runtime =
                        fixture.runtime(
                            scriptRunner =
                                if (script) {
                                    (
                                        { request ->
                                            started.complete(Unit)
                                            try {
                                                awaitCancellation()
                                            } catch (_: CancellationException) {
                                                withContext(NonCancellable) { delay(10) }
                                                fixture.scriptResult(request, buildJsonObject { put("conclusion", "stale script result") })
                                            } finally {
                                                cancelled = true
                                            }
                                        }
                                    )
                                } else {
                                    null
                                },
                            respond = { request ->
                                started.complete(Unit)
                                try {
                                    awaitCancellation()
                                } catch (_: CancellationException) {
                                    withContext(NonCancellable) { delay(10) }
                                    fixture.result(request).copy(summary = "stale child result")
                                } finally {
                                    cancelled = true
                                }
                            },
                        )
                    val prepared = fixture.prepare(runtime, permissions)
                    runtime.approve(prepared.approval)
                    val running = async { runtime.run(prepared.approval.runId).toList() }
                    started.await()
                    runtime.cancel(prepared.approval.runId)
                    withTimeout(1_000) { assertFailsWith<CancellationException> { running.await() } }
                    assertTrue(cancelled)
                    assertTrue(fixture.store.list().all { it.turns.single().status == RunStatus.CANCELLED })
                    if (script) {
                        assertTrue(
                            fixture.store.audit("root-conversation").any {
                                it.kind == ExecutionKind.SCRIPT &&
                                    it.phase == ExecutionPhase.CANCELLED
                            },
                        )
                    }
                    assertFalse(
                        fixture.store.audit("root-conversation").any {
                            it.phase == ExecutionPhase.SUCCEEDED &&
                                it.kind == ExecutionKind.CHILD_AGENT
                        },
                    )
                    assertEquals(if (script) 0 else 1, fixture.calls.size)
                }
            }
        }

    private class Fixture : AutoCloseable {
        private val root = Files.createTempDirectory("aps-agent-graph-").toRealPath()
        private val directories = PlatformDirectories(root.resolve("profile"))
        val registry = ExtensionRegistry(directories)
        val store: SqliteConversationRepository
        val calls = mutableListOf<ProviderRunRequest>()

        init {
            directories.initialize()
            store = SqliteConversationRepository(directories.conversations.resolve("test.sqlite"))
        }

        fun agent(
            id: String,
            children: Set<String> = emptySet(),
            skills: Set<String> = emptySet(),
            depth: Int = 0,
            permissions: Set<Permission> = grants,
            providers: Set<String> = setOf("fake"),
            budget: ResourceBudget = ResourceBudget(maxChildDepth = depth),
            trusted: Boolean = true,
        ): RegistryEntry =
            install(
                ExtensionKind.AGENT,
                id,
                mapOf("prompt.md" to "Context for $id"),
                children,
                skills,
                permissions,
                providers,
                budget,
                trusted = trusted,
            )

        fun script(): RegistryEntry =
            install(
                ExtensionKind.SKILL,
                "custom.script",
                mapOf("prompt.md" to "Script context", "scripts/analyze.py" to "print('bounded')"),
                permissions = setOf(Permission.READ_EVIDENCE, Permission.EXECUTE_SCRIPT),
                budget = ResourceBudget(maxInputBytes = 8_192),
                runtime = ScriptRuntime.PYTHON,
            )

        private fun install(
            kind: ExtensionKind,
            id: String,
            files: Map<String, String>,
            children: Set<String> = emptySet(),
            skills: Set<String> = emptySet(),
            permissions: Set<Permission>,
            providers: Set<String> = emptySet(),
            budget: ResourceBudget,
            runtime: ScriptRuntime = ScriptRuntime.NONE,
            trusted: Boolean = true,
        ): RegistryEntry {
            val bundle = Files.createTempDirectory(root, "bundle-")
            files.forEach { (path, content) ->
                Files.createDirectories(bundle.resolve(path).parent)
                Files.writeString(bundle.resolve(path), content)
            }
            val manifest =
                ExtensionManifest(
                    kind = kind,
                    id = id,
                    version = "1.0.0",
                    name = id,
                    description = "Test graph",
                    entrypoint = "prompt.md",
                    inputSchema = "analysis-input-v1",
                    outputSchema = "analysis-output-v1",
                    contextKinds = setOf("simpleperf"),
                    evidenceKinds = setOf("hotspot"),
                    allowedAgents = children,
                    allowedSkills = skills,
                    allowedProviders = providers,
                    permissions = permissions,
                    budget = budget,
                    runtime = runtime,
                    scripts = files.keys.filter { it.startsWith("scripts/") }.toSet(),
                    files = files.mapValues { (_, text) -> text.toByteArray().sha256() },
                )
            Files.writeString(bundle.resolve("manifest.json"), PlatformJson.codec.encodeToString(manifest))
            val entry = registry.install(bundle)
            if (trusted) {
                registry.trust(entry.key)
                registry.enable(entry.key)
            }
            return registry.resolve(entry.key)
        }

        fun runtime(
            scriptRunner: (suspend (ScriptRunRequest) -> ScriptRunResult)? = null,
            scriptSupport: (suspend (ScriptRuntime) -> Boolean)? = null,
            sourceAuthorization: (AnalysisRequest) -> Boolean = { false },
            connectionRevision: (String) -> String = { "" },
            respond: suspend (ProviderRunRequest) -> AnalysisResult = ::result,
        ): MainAgentOrchestrator {
            val provider =
                object : AiProviderDriver {
                    override val descriptor = ProviderDescriptor("fake", setOf(ProviderCapability.STRUCTURED_ANALYSIS))

                    override suspend fun discover() = ProviderAvailability.Available("test")

                    override fun run(request: ProviderRunRequest) =
                        flow {
                            calls += request
                            emit(ProviderEvent.Started)
                            emit(ProviderEvent.StructuredResult(respond(request)))
                            emit(ProviderEvent.Completed)
                        }
                }
            return MainAgentOrchestrator(
                registry,
                ProviderBroker(ProviderRegistry(listOf(provider))),
                store,
                sourceAuthorization = sourceAuthorization,
                scriptRunner = scriptRunner,
                scriptSupport = scriptSupport,
                connectionRevision = connectionRevision,
            )
        }

        suspend fun prepare(
            runtime: MainAgentOrchestrator,
            permissions: Set<Permission> = grants,
        ) = runtime.prepare("root-conversation", analysis(), "custom.root", "fake", "model", permissions)

        fun result(request: ProviderRunRequest) = AnalysisResult(request.analysis.sessionId, request.model, request.agentId, emptyList())

        fun scriptResult(
            request: ScriptRunRequest,
            content: JsonObject = buildJsonObject { put("count", request.input.evidenceIds.size) },
        ): ScriptRunResult {
            val output =
                buildJsonObject {
                    put("schema", "analysis-output-v1")
                    put("evidenceIds", JsonArray(request.input.evidenceIds.map(::JsonPrimitive)))
                    put("candidateIds", JsonArray(request.input.candidateIds.map(::JsonPrimitive)))
                    put("result", content)
                }
            return ScriptRunResult(
                output,
                ScriptAudit(
                    request.key.id,
                    request.key.version,
                    PlatformJson.codec
                        .encodeToString(request.input)
                        .toByteArray()
                        .sha256(),
                    output.toString().toByteArray().sha256(),
                    ByteArray(0).sha256(),
                    output.toString().toByteArray().size,
                    0,
                    ScriptRuntime.PYTHON,
                    "3.test",
                    "host-test-sandbox",
                    0,
                ),
            )
        }

        override fun close() {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    companion object {
        private val grants = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK)

        private fun analysis() =
            AnalysisRequest(
                AnalysisSessionId("root-analysis"),
                ProfilerKind.SIMPLEPERF,
                AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Hotspot"),
                listOf(PerformanceEvidence("evidence", "hotspot", "Bounded metrics", "{\"count\":1}")),
                emptyList(),
                "versioned-system",
                "minimized-v1",
            )
    }
}
