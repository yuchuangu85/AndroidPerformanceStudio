package com.androidperformancestudio.aiplatform.provider.claude

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import com.androidperformancestudio.aiplatform.providers.LocalProviderConfigResolver
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.providers.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeCliProviderTest {
    @Test
    fun `discovery connection change is rejected before Claude receives any payload`() {
        var revision = "A"
        var captures = 0
        withProvider(
            SUCCESS,
            credentials = false,
            connectionEnvironment = {
                revision = "B"
                mapOf("ANTHROPIC_API_KEY" to "fixture-changed-secret")
            },
            connectionEnvironmentForRun = { request ->
                captures++
                assertEquals("A", request.connectionRevision)
                check(request.connectionRevision == revision) { "E_PROVIDER_CONNECTION_CHANGED" }
                mapOf("ANTHROPIC_API_KEY" to "fixture-original-secret")
            },
        ) { provider, root, _ ->
            val events =
                ProviderBroker(
                    ProviderRegistry(listOf(provider)),
                ).run(request().copy(connectionRevision = "A"), setOf("claude")).toList()
            assertEquals(listOf(ProviderEvent.Failure("E_PROVIDER_CONNECTION_CHANGED", false)), events)
            assertEquals(1, captures)
            assertFalse(Files.exists(root.resolve("calls")))
            assertFalse(Files.exists(root.resolve("input")))
            assertFalse(events.toString().contains("fixture-changed-secret"))
        }
    }

    @Test
    fun `revision aware environment is captured once and not replaced after discovery`() {
        var captures = 0
        val environment = mutableMapOf("ANTHROPIC_API_KEY" to "fixture-original-secret")
        withProvider(
            SUCCESS,
            credentials = false,
            connectionEnvironment = { error("E_FIXTURE_LEGACY_ENVIRONMENT") },
            connectionEnvironmentForRun = { request ->
                captures++
                assertEquals("A", request.connectionRevision)
                environment
            },
            runPrelude = "[ \"\$ANTHROPIC_API_KEY\" = 'fixture-original-secret' ] || exit 26",
        ) { provider, _, _ ->
            val events =
                provider
                    .run(request().copy(connectionRevision = "A"))
                    .onEach {
                        if (it == ProviderEvent.Started) environment["ANTHROPIC_API_KEY"] = "fixture-changed-secret"
                    }.toList()
            assertEquals(ProviderEvent.Completed, events.last())
            assertEquals(1, captures)
        }
    }

    @Test
    fun `version help and metadata discovery require no real AI call`() =
        withProvider(SUCCESS) { provider, _, _ ->
            assertEquals(ProviderAvailability.Available("2.1.308 (Claude Code)"), provider.discover())
        }

    @Test
    fun `no credential is visible unavailable rather than subscription fallback`() =
        withProvider(SUCCESS, credentials = false) { provider, _, _ ->
            assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_CREDENTIAL), provider.discover())
        }

    @Test
    fun `saved key works without resolver credentials and base url reaches only the environment`() =
        withProvider(
            SUCCESS,
            credentials = false,
            connectionEnvironment = {
                mapOf("ANTHROPIC_API_KEY" to "fixture-api-secret", "ANTHROPIC_BASE_URL" to "http://127.0.0.1:12345/v1")
            },
            runPrelude =
                """
                [ "${'$'}ANTHROPIC_API_KEY" = 'fixture-api-secret' ] || exit 21
                [ "${'$'}ANTHROPIC_BASE_URL" = 'http://127.0.0.1:12345/v1' ] || exit 22
                """.trimIndent(),
        ) { provider, root, _ ->
            // Explicit bounded credentials do not import or depend on a user's Claude config.
            Files.createDirectories(root.resolve("home/.claude"))
            Files.writeString(root.resolve("home/.claude/settings.json"), "{malformed")
            assertIs<ProviderAvailability.Available>(provider.discover())
            val events = provider.run(request()).toList()
            assertEquals(ProviderEvent.Completed, events.last())
            for (record in listOf(events.toString(), Files.readString(root.resolve("args")), Files.readString(root.resolve("input")))) {
                assertFalse(record.contains("fixture-api-secret"))
                assertFalse(record.contains("http://127.0.0.1:12345/v1"))
            }
        }

    @Test
    fun `saved auth token is independently usable without an API key reference`() =
        withProvider(
            SUCCESS,
            credentials = false,
            connectionEnvironment = { mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-token-secret") },
            runPrelude = "[ \"\$ANTHROPIC_AUTH_TOKEN\" = 'fixture-token-secret' ] || exit 23",
        ) { provider, root, _ ->
            assertIs<ProviderAvailability.Available>(provider.discover())
            val events = provider.run(request()).toList()
            assertEquals(ProviderEvent.Completed, events.last())
            assertFalse(events.toString().contains("fixture-token-secret"))
            assertFalse(Files.readString(root.resolve("args")).contains("fixture-token-secret"))
            assertFalse(Files.readString(root.resolve("input")).contains("fixture-token-secret"))
        }

    @Test
    fun `base url alone cannot borrow resolver credentials`() {
        for (credentials in listOf(false, true)) {
            withProvider(
                SUCCESS,
                credentials = credentials,
                connectionEnvironment = { mapOf("ANTHROPIC_BASE_URL" to "https://api.example.test") },
            ) { provider, root, _ ->
                assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_CREDENTIAL), provider.discover())
                val events = provider.run(request()).toList()
                assertEquals("E_PROVIDER_MISSING_CREDENTIAL", assertIs<ProviderEvent.Failure>(events.single()).code)
                assertFalse(Files.exists(root.resolve("probes")))
                assertFalse(Files.exists(root.resolve("calls")))
            }
        }
    }

    @Test
    fun `saved API key and auth token together are unsupported without choosing a credential`() =
        withProvider(
            SUCCESS,
            connectionEnvironment = {
                mapOf("ANTHROPIC_API_KEY" to "fixture-api-secret", "ANTHROPIC_AUTH_TOKEN" to "fixture-token-secret")
            },
        ) { provider, root, _ ->
            assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.UNSUPPORTED_CAPABILITY), provider.discover())
            val events = provider.run(request()).toList()
            assertEquals("E_PROVIDER_UNSUPPORTED_CAPABILITY", assertIs<ProviderEvent.Failure>(events.single()).code)
            assertFalse(events.toString().contains("fixture-api-secret"))
            assertFalse(events.toString().contains("fixture-token-secret"))
            assertFalse(Files.exists(root.resolve("probes")))
            assertFalse(Files.exists(root.resolve("calls")))
        }

    @Test
    fun `saved connections are isolated from inherited authentication and endpoints in a child JVM`() {
        if (!Files.isExecutable(Path.of("/bin/sh"))) return
        val work = Files.createTempDirectory("aps-claude-isolation-test-").toRealPath()
        val classpath =
            (
                generateSequence(javaClass.classLoader) { it.parent }
                    .filterIsInstance<URLClassLoader>()
                    .flatMap { it.getURLs().asSequence() }
                    .filter { it.protocol == "file" }
                    .map { Path.of(it.toURI()).toString() }
                    .toList() + System.getProperty("java.class.path").split(File.pathSeparator)
            ).distinct().joinToString(File.pathSeparator)
        try {
            val builder =
                ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Duser.home=$work",
                    "-Djava.io.tmpdir=$work",
                    "-cp",
                    classpath,
                    ConnectionIsolationFixture::class.java.name,
                ).directory(work.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().apply {
                clear()
                put("PATH", "/usr/bin:/bin")
                put("HOME", work.toString())
                putAll(INHERITED_CONNECTION)
            }
            val child = builder.start()
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "E_FIXTURE_ISOLATION_TIMEOUT")
                assertEquals(0, child.exitValue(), "E_FIXTURE_CONNECTION_ISOLATION")
            } finally {
                child.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run snapshots the callback once for discovery and execution`() {
        val calls = AtomicInteger()
        val environment = mutableMapOf("ANTHROPIC_API_KEY" to "fixture-api-secret")
        withProvider(
            SUCCESS,
            credentials = false,
            connectionEnvironment = {
                calls.incrementAndGet()
                environment
            },
            runPrelude = "[ \"\$ANTHROPIC_API_KEY\" = 'fixture-api-secret' ] || exit 24",
        ) { provider, _, _ ->
            val events =
                provider
                    .run(request())
                    .onEach { if (it == ProviderEvent.Started) environment["ANTHROPIC_API_KEY"] = "changed-secret" }
                    .toList()
            assertEquals(ProviderEvent.Completed, events.last())
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `invalid connection overrides fail closed before any CLI launch without disclosing values`() {
        val invalid =
            listOf(
                mapOf("ANTHROPIC_API_KEY" to "fixture-secret", "PATH" to "/injected"),
                mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-secret", "NODE_OPTIONS" to "--require=helper"),
                mapOf("ANTHROPIC_API_KEY" to "fixture-secret\nnext"),
                mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-secret\rnext"),
                mapOf("ANTHROPIC_API_KEY" to "fixture-secret", "ANTHROPIC_BASE_URL" to "http://remote.example.test"),
                mapOf("ANTHROPIC_API_KEY" to "fixture-secret", "ANTHROPIC_BASE_URL" to "https://fixture-secret@api.example.test"),
            )
        for (environment in invalid) {
            withProvider(SUCCESS, connectionEnvironment = { environment }) { provider, root, _ ->
                assertIs<ProviderAvailability.Unavailable>(provider.discover())
                val events = provider.run(request()).toList()
                assertIs<ProviderEvent.Failure>(events.single())
                assertFalse(events.toString().contains("fixture-secret"))
                assertFalse(Files.exists(root.resolve("probes")))
                assertFalse(Files.exists(root.resolve("calls")))
            }
        }
    }

    @Test
    fun `connection callback failures are redacted but cancellation is propagated`() {
        withProvider(SUCCESS, connectionEnvironment = { error("fixture-callback-secret") }) { provider, _, _ ->
            assertIs<ProviderAvailability.Unavailable>(provider.discover())
            val events = provider.run(request()).toList()
            assertIs<ProviderEvent.Failure>(events.single())
            assertFalse(events.toString().contains("fixture-callback-secret"))
        }
        withProvider(SUCCESS, connectionEnvironment = { throw CancellationException("fixture-cancel-secret") }) { provider, _, _ ->
            assertFailsWith<CancellationException> { provider.discover() }
            assertFailsWith<CancellationException> { provider.run(request()).toList() }
        }
    }

    @Test
    fun `saved token process errors never expose stderr or connection secrets`() =
        withProvider(
            SUCCESS,
            credentials = false,
            connectionEnvironment = { mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-token-secret") },
            runPrelude = "cat > /dev/null; printf '%s' \"\$ANTHROPIC_AUTH_TOKEN\" >&2; exit 25",
        ) { provider, _, _ ->
            val events = provider.run(request()).toList()
            assertEquals("E_PROVIDER_PROCESS_EXIT", assertIs<ProviderEvent.Failure>(events.last()).code)
            assertFalse(events.toString().contains("fixture-token-secret"))
        }

    @Test
    fun `cancelling a saved token run still terminates the owned process`() =
        withProvider(
            SUCCESS,
            credentials = false,
            connectionEnvironment = { mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-token-secret") },
            runPrelude = "while :; do sleep 1; done",
        ) { provider, root, _ ->
            coroutineScope {
                val job = launch { provider.run(request()).toList() }
                try {
                    val pidFile = root.resolve("run.pid")
                    withTimeout(5000) {
                        while (!Files.exists(pidFile) || Files.size(pidFile) == 0L) delay(10)
                    }
                    val pid = Files.readString(pidFile).trim().toLong()
                    withTimeout(3000) { job.cancelAndJoin() }
                    assertTrue(job.isCancelled)
                    assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun `old runtime and missing confinement flags cannot run`() =
        withProvider(SUCCESS, version = "2.1.100 (Claude Code)") { provider, _, _ ->
            assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.INCOMPATIBLE_VERSION), provider.discover())
        }

    @Test
    fun `missing tools flag explicitly disables compatibility`() =
        withProvider(SUCCESS, help = "--bare --json-schema") { provider, _, _ ->
            assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.UNSUPPORTED_CAPABILITY), provider.discover())
        }

    @Test
    fun `controlled Claude process receives no tools and stdin evidence and returns validated result`() =
        withProvider(SUCCESS) { provider, root, _ ->
            val events = ProviderBroker(ProviderRegistry(listOf(provider))).run(request(), setOf("claude")).toList()
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
            val args = Files.readString(root.resolve("args"))
            assertTrue(args.contains("--tools\n\n--disallowedTools\nmcp__*"))
            assertTrue(args.contains("--bare"))
            assertTrue(args.contains("--strict-mcp-config"))
            assertTrue(args.contains("--no-session-persistence"))
            assertFalse(args.contains("selected evidence"))
            assertTrue(Files.readString(root.resolve("input")).contains("selected evidence"))
            assertFalse(events.toString().contains("stderr-secret"))
        }

    @Test
    fun `malformed envelope unknown evidence and tool-shaped output fail closed`() {
        val outputs =
            listOf(
                "{broken",
                SUCCESS.replace("evidence-1", "invented"),
                """{"type":"result","subtype":"success","is_error":false,"structured_output":{"tool":"Bash"}}""",
                SUCCESS.replace("\"is_error\":false", "\"is_error\":true"),
            )
        for (output in outputs) {
            withProvider(output) { provider, _, _ ->
                val events = ProviderBroker(ProviderRegistry(listOf(provider))).run(request(), setOf("claude")).toList()
                assertIs<ProviderEvent.Failure>(events.last())
                assertFalse(events.any { it is ProviderEvent.StructuredResult || it == ProviderEvent.Completed })
            }
        }
    }

    @Test
    fun `retry is a new explicit run not an automatic process replay`() =
        withProvider(SUCCESS) { provider, root, _ ->
            val broker = ProviderBroker(ProviderRegistry(listOf(provider)))
            repeat(2) { generation ->
                val events =
                    broker
                        .run(
                            request().copy(runId = "run-$generation", generation = generation.toLong()),
                            setOf("claude"),
                        ).toList()
                assertEquals(ProviderEvent.Completed, events.last())
            }
            assertEquals(2, Files.readAllLines(root.resolve("calls")).size)
        }

    private fun withProvider(
        output: String,
        credentials: Boolean = true,
        version: String = "2.1.308 (Claude Code)",
        help: String = HELP,
        connectionEnvironment: () -> Map<String, String> = { emptyMap() },
        connectionEnvironmentForRun: ((ProviderRunRequest) -> Map<String, String>)? = null,
        runPrelude: String = "",
        block: suspend (ClaudeCliProvider, Path, Path) -> Unit,
    ) = runBlocking {
        if (!Files.isExecutable(Path.of("/bin/sh"))) return@runBlocking
        val root = Files.createTempDirectory("aps-claude-test-").toRealPath()
        val home = Files.createDirectories(root.resolve("home"))
        val executable = root.resolve("claude")
        val script =
            """
            #!/bin/sh
            echo probe >> '${root.resolve("probes")}'
            case "${'$'}1" in
              --version) printf '%s' '$version'; exit 0;;
              --help) printf '%s' '$help'; exit 0;;
            esac
            echo ${'$'}${'$'} > '${root.resolve("run.pid")}'
            ${runPrelude.prependIndent("            ")}
            printf '%s\n' "${'$'}@" > '${root.resolve("args")}'
            cat > '${root.resolve("input")}'
            echo run >> '${root.resolve("calls")}'
            printf 'stderr-secret' >&2
            cat <<'RESULT'
            $output
            RESULT
            """.trimIndent()
        Files.writeString(executable, script)
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val resolver = LocalProviderConfigResolver(home, if (credentials) setOf("ANTHROPIC_API_KEY") else emptySet())
        val provider =
            ClaudeCliProvider(
                executable,
                resolver,
                connectionEnvironment = connectionEnvironment,
                connectionEnvironmentForRun = connectionEnvironmentForRun,
            )
        try {
            block(provider, root, executable)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun verifySavedConnectionIsolation() {
        INHERITED_CONNECTION.forEach { (name, value) ->
            check(System.getenv(name) == value) { "E_FIXTURE_INHERITED_ENVIRONMENT" }
        }
        val connections =
            listOf(
                mapOf("ANTHROPIC_API_KEY" to "fixture-api-secret"),
                mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-token-secret"),
                mapOf("ANTHROPIC_API_KEY" to "fixture-api-secret", "ANTHROPIC_BASE_URL" to "https://saved.example.test"),
                emptyMap(),
            )
        for (connection in connections) {
            val expected = connection.ifEmpty { INHERITED_CONNECTION }
            val environmentChecks =
                INHERITED_CONNECTION.keys.joinToString("\n") { name ->
                    expected[name]?.let { value ->
                        "[ \"\${$name}\" = '$value' ] || exit 31"
                    } ?: "[ -z \"\${$name+x}\" ] || exit 32"
                }
            withProvider(
                SUCCESS,
                credentials = connection.isEmpty(),
                connectionEnvironment = { connection },
                runPrelude = environmentChecks,
            ) { provider, _, _ ->
                assertIs<ProviderAvailability.Available>(provider.discover())
                assertEquals(ProviderEvent.Completed, provider.run(request()).toList().last())
            }
        }
    }

    object ConnectionIsolationFixture {
        @JvmStatic
        fun main(args: Array<String>) {
            require(args.isEmpty())
            ClaudeCliProviderTest().verifySavedConnectionIsolation()
        }
    }

    private fun request(): ProviderRunRequest =
        ProviderRunRequest(
            runId = "run-1",
            generation = 1,
            agentId = "simpleperf",
            providerId = "claude",
            model = "test-model",
            analysis =
                AnalysisRequest(
                    sessionId = AnalysisSessionId("session-1"),
                    originProfiler = ProfilerKind.SIMPLEPERF,
                    scope = AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "selected evidence"),
                    evidence = listOf(PerformanceEvidence("evidence-1", "simpleperf-node", "selected evidence", "{}")),
                    sourceCandidates = emptyList(),
                    promptVersion = "v1",
                    payloadPolicyVersion = "v1",
                ),
            grantedPermissions = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
        )

    private companion object {
        val INHERITED_CONNECTION =
            mapOf(
                "ANTHROPIC_API_KEY" to "fixture-inherited-api-secret",
                "ANTHROPIC_AUTH_TOKEN" to "fixture-inherited-token-secret",
                "ANTHROPIC_BASE_URL" to "https://inherited.example.test",
            )
        const val HELP =
            "--bare --tools --disallowedTools --strict-mcp-config --mcp-config --json-schema " +
                "--setting-sources --no-session-persistence --disable-slash-commands --model --system-prompt"
        const val SUCCESS =
            """{"type":"result","subtype":"success","is_error":false,"structured_output":{"summary":"Selected evidence",""" +
                """"findings":[{"id":"finding-1","severity":"WARNING","title":"Hot","explanation":"Measured work",""" +
                """"recommendation":"Inspect evidence","analysisConfidence":0.8,"performanceEvidenceIds":["evidence-1"],"sourceCandidateIds":[]}]}}"""
    }
}
