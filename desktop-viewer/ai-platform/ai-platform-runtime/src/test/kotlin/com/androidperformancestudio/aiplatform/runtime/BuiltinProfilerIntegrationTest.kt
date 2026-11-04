package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.providers.GatewayAnalysisProvider
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.providers.ProviderRegistry
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.PlatformDirectories
import com.androidperformancestudio.aiplatform.storage.SqliteConversationRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltinProfilerIntegrationTest {
    @Test
    fun `every published profiler evidence kind executes the real builtin plan`() =
        runBlocking {
            fixture { runtime, calls, _ ->
                val kinds =
                    listOf(
                        ProfilerKind.LAYOUT_INSPECTOR to "layout-snapshot",
                        ProfilerKind.SIMPLEPERF to "simpleperf-hotspots",
                        ProfilerKind.METHOD_RECORDING to "method-recording-summary",
                        ProfilerKind.TRACE to "trace-selection",
                        ProfilerKind.SOURCE_WORKSPACE to "source-workspace-selection",
                    ) +
                        listOf(
                            ProfilerKind.MEMORY,
                            ProfilerKind.FRAME,
                            ProfilerKind.STARTUP,
                            ProfilerKind.BATTERY,
                            ProfilerKind.NETWORK,
                            ProfilerKind.GPU,
                            ProfilerKind.BENCHMARK,
                        ).flatMap { profiler ->
                            listOf("selection", "summary").map { profiler to "${profiler.name.lowercase()}-$it" }
                        }
                for ((index, pair) in kinds.withIndex()) {
                    val analysis = request(pair.first, pair.second)
                    val callsBefore = calls.size
                    val prepared = runtime.prepare("conversation-$index", analysis, "aps.main", "openai", "test-model", permissions)
                    assertEquals(callsBefore, calls.size, "preparing ${pair.second} must not call a provider")
                    runtime.approve(prepared.approval)
                    val events = runtime.run(prepared.approval.runId).toList()
                    assertTrue(events.any { it.event == ProviderEvent.Completed }, "${pair.second}: $events")
                    assertEquals(callsBefore + if (pair.first == ProfilerKind.SIMPLEPERF) 3 else 2, calls.size)
                    if (pair.first == ProfilerKind.SOURCE_WORKSPACE) {
                        assertEquals(analysis.evidence, calls.last().evidence)
                        assertTrue(calls.last().sourceCandidates.isEmpty())
                        assertTrue(
                            calls
                                .last()
                                .evidence
                                .single()
                                .structuredPayload
                                .contains("\"performanceMeasurement\":false"),
                        )
                    }
                }
            }
        }

    @Test
    fun `shared builtins allow source metadata without granting snippet access`() =
        runBlocking {
            fixture { runtime, calls, registry ->
                val shared =
                    listOf(
                        ExtensionKind.AGENT to "aps.main",
                        ExtensionKind.AGENT to "aps.source-evidence",
                        ExtensionKind.AGENT to "aps.report",
                        ExtensionKind.SKILL to "aps.evidence-summary",
                        ExtensionKind.SKILL to "aps.source-candidates",
                        ExtensionKind.SKILL to "aps.report-artifact",
                    )
                shared.forEach { (kind, id) ->
                    val entry = registry.resolve(kind, id)
                    assertTrue(entry.usable, id)
                    assertEquals("1.4.0", entry.manifest.version, id)
                    assertTrue("source-workspace" in entry.manifest.contextKinds, id)
                    assertTrue("method-recording" in entry.manifest.contextKinds, id)
                    assertTrue("method-recording-summary" in entry.manifest.evidenceKinds, id)
                    assertTrue("source-workspace-selection" in entry.manifest.evidenceKinds, id)
                }
                val simpleperf = registry.resolve(ExtensionKind.AGENT, "aps.simpleperf").manifest
                assertFalse("source-workspace" in simpleperf.contextKinds)
                assertFalse("source-workspace-selection" in simpleperf.evidenceKinds)

                for ((index, agent) in listOf("aps.main", "aps.source-evidence", "aps.report").withIndex()) {
                    val analysis = request(ProfilerKind.SOURCE_WORKSPACE, "source-workspace-selection")
                    val callsBefore = calls.size
                    val prepared = runtime.prepare("source-$index", analysis, agent, "openai", "test-model", permissions)
                    assertEquals(callsBefore, calls.size)
                    runtime.approve(prepared.approval)
                    assertTrue(runtime.run(prepared.approval.runId).toList().any { it.event == ProviderEvent.Completed })
                    assertEquals(callsBefore + if (agent == "aps.main") 2 else 1, calls.size)
                    assertEquals(ProfilerKind.SOURCE_WORKSPACE, calls.last().originProfiler)
                    assertEquals(analysis.evidence, calls.last().evidence)
                    assertTrue(calls.last().sourceCandidates.isEmpty())
                }
            }
        }

    @Test
    fun `source context and evidence allowlists still reject unsupported inputs before dispatch`() =
        runBlocking {
            fixture { runtime, calls, _ ->
                assertEquals(
                    "E_EVIDENCE_KIND",
                    assertFailsWith<AgentPolicyException> {
                        runtime.prepare(
                            "unsupported-evidence",
                            request(ProfilerKind.SOURCE_WORKSPACE, "source-workspace-summary"),
                            "aps.main",
                            "openai",
                            "test-model",
                            permissions,
                        )
                    }.code,
                )
                assertEquals(
                    "E_CONTEXT_UNAVAILABLE",
                    assertFailsWith<AgentPolicyException> {
                        runtime.prepare(
                            "unsupported-context",
                            request(ProfilerKind.SOURCE_WORKSPACE, "source-workspace-selection"),
                            "aps.simpleperf",
                            "openai",
                            "test-model",
                            permissions,
                        )
                    }.code,
                )
                assertTrue(calls.isEmpty())
            }
        }

    @Test
    fun `authorized source snippets work and revocation before execution sends nothing`() =
        runBlocking {
            var authorized = true
            fixture(sourceAuthorization = { authorized }) { runtime, calls, _ ->
                val analysis =
                    request(ProfilerKind.SIMPLEPERF, "simpleperf-hotspots").copy(
                        sourceCandidates =
                            listOf(
                                AiSourceCandidate(
                                    "candidate",
                                    "src/Main.kt",
                                    null,
                                    "EXACT",
                                    emptyList(),
                                    "fun boundedSource() = Unit",
                                    1,
                                    1,
                                    "a".repeat(64),
                                ),
                            ),
                    )
                val sourcePermissions = permissions + setOf(Permission.READ_SOURCE_CANDIDATES, Permission.SEND_SOURCE_SNIPPETS)
                val first = runtime.prepare("authorized", analysis, "aps.main", "openai", "test-model", sourcePermissions)
                runtime.approve(first.approval)
                assertTrue(runtime.run(first.approval.runId).toList().any { it.event == ProviderEvent.Completed })
                assertEquals(4, calls.size)
                val revoked = runtime.prepare("revoked", analysis, "aps.main", "openai", "test-model", sourcePermissions)
                runtime.approve(revoked.approval)
                authorized = false
                assertEquals(
                    "E_SOURCE_UNAUTHORIZED",
                    assertFailsWith<AgentPolicyException> {
                        runtime.run(revoked.approval.runId).toList()
                    }.code,
                )
                assertEquals(4, calls.size)
                assertEquals(
                    "E_SOURCE_UNAUTHORIZED",
                    assertFailsWith<AgentPolicyException> {
                        runtime.prepare("denied", analysis, "aps.main", "openai", "test-model", sourcePermissions)
                    }.code,
                )
                assertEquals(4, calls.size)
            }
        }

    private suspend fun fixture(
        sourceAuthorization: (AnalysisRequest) -> Boolean = { false },
        block: suspend (MainAgentOrchestrator, List<AnalysisRequest>, ExtensionRegistry) -> Unit,
    ) {
        val root = Files.createTempDirectory("aps-profiler-plan-").toRealPath()
        try {
            val directories = PlatformDirectories(root)
            val registry = ExtensionRegistry(directories)
            registry.installBuiltins()
            val calls = mutableListOf<AnalysisRequest>()
            val provider =
                GatewayAnalysisProvider(
                    "openai",
                    "test-model",
                    AiAnalysisGateway { analysis ->
                        calls += analysis
                        AnalysisResult(analysis.sessionId, "test-model", "Evidence summary", emptyList())
                    },
                )
            SqliteConversationRepository(directories.conversations.resolve("test.sqlite")).use { history ->
                block(
                    MainAgentOrchestrator(registry, ProviderBroker(ProviderRegistry(listOf(provider))), history, sourceAuthorization),
                    calls,
                    registry,
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun request(
        profiler: ProfilerKind,
        evidenceKind: String,
    ) = AnalysisRequest(
        AnalysisSessionId.create(),
        profiler,
        AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Bounded profiler evidence"),
        listOf(
            if (profiler == ProfilerKind.SOURCE_WORKSPACE) {
                PerformanceEvidence(
                    "evidence",
                    evidenceKind,
                    "Verified source metadata, not performance measurement",
                    "{\"evidenceType\":\"source-metadata\",\"performanceMeasurement\":false,\"lineCount\":1}",
                )
            } else {
                PerformanceEvidence("evidence", evidenceKind, "Numeric metrics", "{\"count\":1}")
            },
        ),
        emptyList(),
        "test-v1",
        "minimized-v1",
    )

    private val permissions = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK)
}
