package com.androidperformancestudio.aiplatform.providers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlledProviderProcessTest {
    @Test
    fun `bounded stdout returns while stderr stays private`() =
        withScript("cat; printf 'private stderr' >&2") { script, work ->
            val result = ControlledProviderProcess().execute(request(script, work, "fixture input"))
            assertEquals(0, result.exitCode)
            assertEquals("fixture input", result.stdout)
            assertFalse(result.toString().contains("fixture"))
            assertFalse(result.toString().contains("stderr"))
        }

    @Test
    fun `stdout and stderr overflow are stable failures`() =
        withScript("printf '%02000d' 0") { script, work ->
            val failure = assertFailsWith<ProviderProcessException> { ControlledProviderProcess().execute(request(script, work)) }
            assertEquals("E_OUTPUT_LIMIT", failure.code)
        }

    @Test
    fun `stderr overflow never exposes stderr text`() =
        withScript("printf '%02000d' 0 >&2") { script, work ->
            val failure = assertFailsWith<ProviderProcessException> { ControlledProviderProcess().execute(request(script, work)) }
            assertEquals("E_PROVIDER_STDERR_LIMIT", failure.code)
            assertEquals("E_PROVIDER_STDERR_LIMIT", failure.message)
        }

    @Test
    fun `exact utf8 byte limit is accepted`() =
        withScript("i=0; while [ \"\$i\" -lt 500 ]; do printf '\\303\\251'; i=\$((i + 1)); done") { script, work ->
            val result = ControlledProviderProcess().execute(request(script, work))
            assertEquals("\u00e9".repeat(500), result.stdout)
        }

    @Test
    fun `overflow is observed before a live process closes either pipe`() {
        listOf("" to "E_OUTPUT_LIMIT", " >&2" to "E_PROVIDER_STDERR_LIMIT").forEach { (redirect, code) ->
            withScript("printf '%01001d' 0$redirect; while :; do :; done") { script, work ->
                val failure =
                    assertFailsWith<ProviderProcessException> {
                        ControlledProviderProcess().execute(request(script, work))
                    }
                assertEquals(code, failure.code)
                assertFalse(recordedProcessIsAlive(work, "parent.pid"))
            }
        }
    }

    @Test
    fun `malformed stdout is a redacted protocol failure`() =
        withScript("printf '\\377'") { script, work ->
            val failure = assertFailsWith<ProviderProcessException> { ControlledProviderProcess().execute(request(script, work)) }
            assertEquals("E_PROVIDER_PROTOCOL", failure.code)
            assertEquals(failure.code, failure.message)
            assertEquals(null, failure.cause)
        }

    @Test
    fun `timeout cannot block closing a full stdin pipe`() =
        withScript("trap '' TERM; while :; do :; done") { script, work ->
            val started = System.nanoTime()
            val failure =
                assertFailsWith<ProviderProcessException> {
                    ControlledProviderProcess().execute(request(script, work, "x".repeat(1_048_576), timeout = 1000))
                }
            assertEquals("E_PROVIDER_TIMEOUT", failure.code)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000, "Timeout cleanup blocked on stdin")
            assertFalse(recordedProcessIsAlive(work, "parent.pid"))
        }

    @Test
    fun `cancellation cannot block closing a full stdin pipe`() =
        withScript("trap '' TERM; printf ready > ready; while :; do :; done") { script, work ->
            val job =
                launch {
                    ControlledProviderProcess().execute(
                        request(
                            script,
                            work,
                            "x".repeat(1_048_576),
                            timeout = 10_000,
                            environment = mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-token"),
                        ),
                    )
                }
            awaitFile(work.resolve("ready"))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertFalse(recordedProcessIsAlive(work, "parent.pid"))
        }

    @Test
    fun `cleanup tracks descendants spawned by a termination handler`() =
        withScript(
            "trap '(trap \"\" TERM; while :; do sleep 1; done) </dev/null >/dev/null 2>&1 & echo \$! > late.pid; wait' TERM; " +
                "while :; do :; done",
        ) { script, work ->
            val failure =
                assertFailsWith<ProviderProcessException> {
                    ControlledProviderProcess().execute(request(script, work, timeout = 1000))
                }
            assertEquals("E_PROVIDER_TIMEOUT", failure.code)
            awaitFile(work.resolve("late.pid"))
            assertFalse(recordedProcessIsAlive(work, "late.pid"))
        }

    @Test
    fun `successful parent exit still cleans up tracked descendants`() =
        withScript("sleep 30 </dev/null >/dev/null 2>&1 & echo \$! > child.pid; sleep 0.1; printf done") { script, work ->
            val result = ControlledProviderProcess().execute(request(script, work))
            assertEquals("done", result.stdout)
            assertFalse(recordedProcessIsAlive(work, "child.pid"))
        }

    @Test
    fun `timeout force terminates resistant descendant`() =
        withScript("trap '' TERM; (trap '' TERM; while :; do sleep 1; done) & echo $! > child.pid; wait") { script, work ->
            val failure =
                assertFailsWith<ProviderProcessException> {
                    ControlledProviderProcess().execute(request(script, work, timeout = 1000))
                }
            assertEquals("E_PROVIDER_TIMEOUT", failure.code)
            val pid = Files.readString(work.resolve("child.pid")).trim().toLong()
            repeat(20) { if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(25) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }

    @Test
    fun `caller cancel terminates owned process without unrelated process kill`() =
        withScript("echo $$ > parent.pid; while :; do sleep 1; done") { script, work ->
            val unrelated = ProcessBuilder("/bin/sleep", "30").start()
            try {
                val entered = CompletableDeferred<Unit>()
                val job =
                    launch {
                        entered.complete(Unit)
                        ControlledProviderProcess().execute(request(script, work, timeout = 10_000))
                    }
                entered.await()
                awaitFile(work.resolve("parent.pid"))
                val pid = Files.readString(work.resolve("parent.pid")).trim().toLong()
                job.cancelAndJoin()
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
                assertTrue(unrelated.isAlive)
                assertTrue(ProcessHandle.current().isAlive)
            } finally {
                unrelated.toHandle().destroyForcibly()
                unrelated.waitFor(1, TimeUnit.SECONDS)
            }
        }

    @Test
    fun `validated request collections cannot be changed by their caller`() {
        val arguments = mutableListOf("original")
        val names = mutableSetOf<String>()
        val environment = mutableMapOf("ANTHROPIC_API_KEY" to "fixture-key")
        val request =
            ProviderProcessRequest(
                Path.of("/fake"),
                arguments,
                workDirectory = Path.of("/tmp"),
                timeoutMillis = 1,
                maxOutputBytes = 1,
                inheritedEnvironmentNames = names,
                environment = environment,
            )
        arguments[0] = "changed"
        names += "AWS_SECRET_ACCESS_KEY"
        environment["ANTHROPIC_API_KEY"] = "changed"
        environment["PATH"] = "/injected"
        assertEquals(listOf("original"), request.arguments)
        assertEquals(emptySet(), request.inheritedEnvironmentNames)
        assertEquals(mapOf("ANTHROPIC_API_KEY" to "fixture-key"), request.environment)
        assertEquals("ProviderProcessRequest(redacted)", request.toString())
        assertFailsWith<UnsupportedOperationException> { (request.arguments as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.inheritedEnvironmentNames as MutableSet<String>).add("CODEX_HOME") }
        assertFailsWith<UnsupportedOperationException> { (request.environment as MutableMap<String, String>).clear() }
    }

    @Test
    fun `explicit credentials and base url reach only the environment without inherited credentials`() =
        withScript(
            """
            [ "${'$'}#" -eq 0 ] || exit 21
            [ "${'$'}ANTHROPIC_API_KEY" = 'fixture-key' ] || exit 22
            [ "${'$'}ANTHROPIC_AUTH_TOKEN" = 'fixture-token' ] || exit 23
            [ "${'$'}ANTHROPIC_BASE_URL" = 'http://127.0.0.1:12345/v1' ] || exit 24
            printf accepted
            """.trimIndent(),
        ) { script, work ->
            val request =
                request(
                    script,
                    work,
                    environment =
                        mapOf(
                            "ANTHROPIC_API_KEY" to "fixture-key",
                            "ANTHROPIC_AUTH_TOKEN" to "fixture-token",
                            "ANTHROPIC_BASE_URL" to "http://127.0.0.1:12345/v1",
                        ),
                )
            assertTrue(request.inheritedEnvironmentNames.isEmpty())
            val result = ControlledProviderProcess().execute(request)
            assertEquals(0, result.exitCode)
            assertEquals("accepted", result.stdout)
        }

    @Test
    fun `overrides win over inherited Anthropic environment`() =
        withScript("[ \"\$ANTHROPIC_API_KEY\" = 'fixture-key' ] && printf accepted") { script, work ->
            val request =
                ProviderProcessRequest(
                    script,
                    emptyList(),
                    workDirectory = work,
                    timeoutMillis = 2000,
                    maxOutputBytes = 1000,
                    inheritedEnvironmentNames = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL"),
                    environment = mapOf("ANTHROPIC_API_KEY" to "fixture-key"),
                )
            assertEquals("accepted", ControlledProviderProcess().execute(request).stdout)
        }

    @Test
    fun `token only override does not implicitly inherit an API key`() =
        withScript(
            "[ -z \"\${ANTHROPIC_API_KEY+x}\" ] && [ \"\$ANTHROPIC_AUTH_TOKEN\" = 'fixture-token' ] && printf accepted",
        ) { script, work ->
            val result =
                ControlledProviderProcess().execute(request(script, work, environment = mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-token")))
            assertEquals(0, result.exitCode)
            assertEquals("accepted", result.stdout)
        }

    @Test
    fun `environment stderr failures never disclose secret values`() =
        withScript("printf '%s' \"\$ANTHROPIC_AUTH_TOKEN\" >&2; printf '%02000d' 0 >&2") { script, work ->
            val failure =
                assertFailsWith<ProviderProcessException> {
                    ControlledProviderProcess()
                        .execute(
                            request(
                                script,
                                work,
                                environment = mapOf("ANTHROPIC_AUTH_TOKEN" to "fixture-secret"),
                            ),
                        )
                }
            assertEquals("E_PROVIDER_STDERR_LIMIT", failure.message)
            assertFalse(failure.toString().contains("fixture-secret"))
            assertEquals(null, failure.cause)
        }

    @Test
    fun `only bounded single line Anthropic overrides are accepted`() {
        val rejected =
            listOf(
                "PATH",
                "HOME",
                "USERPROFILE",
                "SystemRoot",
                "LD_PRELOAD",
                "DYLD_INSERT_LIBRARIES",
                "NODE_OPTIONS",
                "OPENAI_API_KEY",
                "CODEX_HOME",
            ).map { it to "fixture-secret" } +
                listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN").flatMap { name ->
                    listOf(
                        "",
                        " ",
                        "fixture-secret\nnext",
                        "fixture-secret\rnext",
                        "fixture-secret\u0000",
                        "fixture-secret\u2028next",
                        "x".repeat(16_385),
                        "\u00e9".repeat(8193),
                    ).map { name to it }
                }
        for ((name, value) in rejected) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    request(Path.of("/fake"), Path.of("/tmp"), environment = mapOf(name to value))
                }
            assertEquals("E_PROVIDER_ENVIRONMENT", failure.message)
            assertFalse(failure.toString().contains("fixture-secret"))
            assertEquals(null, failure.cause)
        }
        for (name in listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")) {
            val request = request(Path.of("/fake"), Path.of("/tmp"), environment = mapOf(name to "x".repeat(16_384)))
            assertEquals(16_384, request.environment.getValue(name).length)
        }
    }

    @Test
    fun `base url accepts HTTPS or exact loopback HTTP without credential or request components`() {
        for (url in listOf("https://api.example.test/v1", "http://localhost:12345", "http://127.0.0.1:12345/v1", "http://[::1]:12345")) {
            val request = request(Path.of("/fake"), Path.of("/tmp"), environment = mapOf("ANTHROPIC_BASE_URL" to url))
            assertEquals(url, request.environment["ANTHROPIC_BASE_URL"])
        }
        val rejected =
            listOf(
                "http://api.example.test",
                "http://localhost.example.test",
                "http://127.0.0.1.example.test",
                "http://127.1",
                "http://127.0.0.2",
                "http://[::ffff:127.0.0.1]",
                "https://fixture-secret@api.example.test",
                "https://api.example.test?key=fixture-secret",
                "https://api.example.test#fixture-secret",
                "https://api.example.test:65536",
                "https:///fixture-secret",
                "https://bad host/fixture-secret",
                "file:///fixture-secret",
                "https://api.example.test/\nfixture-secret",
            )
        for (url in rejected) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    request(Path.of("/fake"), Path.of("/tmp"), environment = mapOf("ANTHROPIC_BASE_URL" to url))
                }
            assertEquals("E_PROVIDER_ENVIRONMENT", failure.message)
            assertFalse(failure.toString().contains("fixture-secret"))
            assertEquals(null, failure.cause)
        }
    }

    @Test
    fun `arbitrary credential environment forwarding is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderProcessRequest(
                Path.of("/fake"),
                emptyList(),
                workDirectory = Path.of("/tmp"),
                timeoutMillis = 1,
                maxOutputBytes = 1,
                inheritedEnvironmentNames = setOf("AWS_SECRET_ACCESS_KEY"),
            )
        }
    }

    private fun request(
        script: Path,
        work: Path,
        input: String = "",
        timeout: Long = 2000,
        environment: Map<String, String> = emptyMap(),
    ): ProviderProcessRequest =
        ProviderProcessRequest(
            script,
            emptyList(),
            input,
            work,
            timeout,
            maxOutputBytes = 1000,
            maxErrorBytes = 1000,
            environment = environment,
        )

    private fun withScript(
        body: String,
        block: suspend kotlinx.coroutines.CoroutineScope.(Path, Path) -> Unit,
    ) = runBlocking {
        if (!Files.isExecutable(Path.of("/bin/sh"))) return@runBlocking
        val work = Files.createTempDirectory("aps-process-test-")
        val script = work.resolve("provider")
        Files.writeString(script, "#!/bin/sh\necho $$ > parent.pid\n$body\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        val watchdog =
            launch(Dispatchers.IO) {
                delay(5000)
                terminateRecordedProcesses(work)
            }
        try {
            block(script, work)
        } finally {
            withContext(NonCancellable) {
                terminateRecordedProcesses(work)
                watchdog.cancelAndJoin()
                work.toFile().deleteRecursively()
            }
        }
    }

    private suspend fun awaitFile(path: Path) =
        withTimeout(2000) {
            while (!Files.exists(path) || Files.size(path) == 0L) delay(10)
        }

    private fun recordedProcessIsAlive(
        work: Path,
        name: String,
    ): Boolean = ProcessHandle.of(Files.readString(work.resolve(name)).trim().toLong()).map { it.isAlive }.orElse(false)

    private fun terminateRecordedProcesses(work: Path) {
        listOf("parent.pid", "child.pid", "late.pid").forEach { name ->
            val path = work.resolve(name)
            if (Files.exists(path)) {
                Files.readString(path).trim().toLongOrNull()?.let { pid ->
                    ProcessHandle.of(pid).ifPresent { process ->
                        process.descendants().use { stream -> stream.forEach { it.destroyForcibly() } }
                        process.destroyForcibly()
                    }
                }
            }
        }
    }
}
