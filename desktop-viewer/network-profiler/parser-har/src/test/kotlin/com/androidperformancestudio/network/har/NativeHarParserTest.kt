@file:Suppress("MagicNumber")

package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.analysis.NetworkAnalyzer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHarParserTest {
    @Test
    fun `real Rust has full model and analyzer parity across pages and metadata order`() {
        val entries = (0..129).map { index ->
            val timings = when (index % 4) {
                0 -> """{"blocked":0,"dns":null,"connect":10,"ssl":5,"send":1,"wait":20,"receive":8,"custom":2}"""
                1 -> """{"dns":-1,"connect":-1,"ssl":-1,"wait":-2}"""
                2 -> """{"dns":"bad","ssl":-1,"receive":-3}"""
                else -> "{}"
            }
            """
            {"startedDateTime":"2026-01-01T00:01:00Z","time":${if (index % 3 == 0) "null" else "30.25"},
            "_requestId":"call-$index","connection":"conn-1",${if (index == 4) "\"_error\":\"private-error\"," else ""}
            "request":{"method":"POST","url":"http://example.test/private?token=secret","bodySize":${if (index % 2 == 0) -1 else 17},
              "headers":[{"name":"Authorization","value":"Bearer secret"},{"name":"X-Private","value":"private-header"}],"postData":{"text":"private-body"}},
            "response":{"status":${if (index % 3 == 0) 0 else 201},"httpVersion":"HTTP/2","bodySize":100,"content":{"size":120,"text":"private-response"},
              "headers":[{"name":"Set-Cookie","value":"private-cookie"}]},"timings":$timings}
            """.trimIndent()
        } + listOf(
            """{"startedDateTime":"2025-12-31T23:59:00Z"}""",
            "null",
            """{"startedDateTime":"not-a-date","request":{},"time":-1}""",
            """{"request":{"url":{"private-url":"secret"}}}""",
        )
        fixture("""{"log":{"entries":[${entries.joinToString(",")}],"creator":{"name":"fixture","version":"2"},"version":"1.2"}}""") { file ->
            val legacy = HarParser().parse(file)
            val native = NativeHarParser(binary(), pageSize = 3).parse(file)
            assertEquals(legacy.calls, native.calls)
            assertEquals(legacy.session, native.session.copy(id = legacy.session.id))
            assertEquals(NetworkAnalyzer().summarize(legacy.calls), NetworkAnalyzer().summarize(native.calls))
            assertEquals("2025-12-31T23:59:00Z", native.session.startedAt.toString())
            assertEquals(3, native.session.completeness.skippedRecords)
            for (secret in listOf("Bearer secret", "private-header", "private-body", "private-response", "private-cookie", "private-error", "private-url")) {
                assertFalse(native.toString().contains(secret), secret)
            }
        }
    }

    @Test
    fun `bad syntax limits trailing data empty input and unsupported versions reject atomically`() {
        val executable = binary()
        for (input in listOf(
            "", "{", """{"log":{"version":"1.2","entries":[]}}""",
            """{"log":{"version":"1.2","entries":[{}]}} trailing-secret""",
            """{"log":{"version":"1.2","entries":[{}],"entries":[]}}""",
            """{"log":{"version":"9.9","entries":[]}}""",
            """{"log":{"version":"1.2","entries":[{"request":{},"body":"${"x".repeat(256 * 1024)}"}]}}""",
        )) {
            fixture(input) { file ->
                val error = assertFailsWith<NativeHarException> { NativeHarParser(executable).parse(file) }
                assertEquals("Native HAR import rejected or unavailable", error.message)
                assertEquals(null, error.cause)
            }
        }
    }

    @Test
    fun `cancellation is propagated and never converted into rejection`() {
        fixture("""{"log":{"version":"1.2","entries":[]}}""") { file ->
            val cancellation = CancellationException("cancelled")
            val error = assertFailsWith<CancellationException> {
                NativeHarParser(Path.of("not-used")).parse(file) { throw cancellation }
            }
            assertTrue(error === cancellation)
        }
    }

    @Test
    fun `large pages preserve Unicode HAR 11 and body size semantics without retaining bodies`() {
        val entries = (0..11).joinToString(",") { index ->
            """
            {"request":{"url":"https://example.test/path","headers":[{"name":"Content-Type","value":"application/json"}],
            "postData":{"text":"${"x".repeat(200_000)}"}},"response":{"bodySize":-2,"content":{"size":-1}},"time":0,
            "startedDateTime":"2026-01-01T00:00:00.${index.toString().padStart(3, '0')}Z"}
            """.trimIndent()
        }
        fixture("""{"log":{"entries":[$entries],"version":"1.1","creator":{"name":"fixture-\u96ea"}}}""") { file ->
            val legacy = HarParser().parse(file)
            val native = NativeHarParser(binary(), pageSize = 256).parse(file)
            assertEquals(legacy.calls, native.calls)
            assertEquals(legacy.session, native.session.copy(id = legacy.session.id))
            assertEquals(null, native.calls.first().exchanges.single().responseBytes)
            assertEquals("application/json", native.calls.first().exchanges.single().requestHeaders["Content-Type"])
        }
    }

    @Test
    fun `bad wire protocol never publishes partial calls or private diagnostics`() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")))
        val directory = Files.createTempDirectory("aps-har-wire-")
        val script = directory.resolve("native.sh")
        try {
            fixture("{}") { file ->
                for ((wire, exit) in listOf(
                    "APS-HAR-ENTRIES\t9\n" to 0,
                    "APS-HAR-ENTRIES\t1\nentry\t0\t2147483647\n" to 0,
                    "APS-HAR-ENTRIES\t1\nentry\t2\t2\n{}\n" to 0,
                    "APS-HAR-ENTRIES\t1\nversion\t3\n1.2\nentry\t0\t14\n{\"request\":{}}\nend\t1\ntrailing-private-body" to 0,
                    "APS-HAR-ENTRIES\t1\nversion\t3\n1.2\nentry\t0\t14\n{\"request\":{}}\nend\t1\n" to 1,
                    "private-url-secret\n" to 0,
                )) {
                    Files.writeString(script, "#!/bin/sh\nprintf '%s' '${wire.replace("'", "'\\''")}'\nprintf '%s' 'private-header-secret' >&2\nexit $exit\n")
                    assertTrue(script.toFile().setExecutable(true, true))
                    val error = assertFailsWith<NativeHarException> { NativeHarParser(script).parse(file) }
                    assertEquals("Native HAR import rejected or unavailable", error.message)
                    assertEquals(null, error.cause)
                }
            }
        } finally {
            Files.deleteIfExists(script)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `input mutation between fingerprint and publication is rejected`() {
        val executable = binary()
        fixture("""{"log":{"version":"1.2","entries":[{"request":{},"time":1}]}}""") { file ->
            var checks = 0
            assertFailsWith<NativeHarException> {
                NativeHarParser(executable).parse(file) {
                    if (++checks == 6) Files.writeString(file, """{"log":{"version":"1.2","entries":[{"request":{},"time":2}]}}""")
                }
            }
            assertTrue(checks >= 6)
        }
    }

    @Test
    fun `silent native is cancellable times out and is reaped`() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")))
        val directory = Files.createTempDirectory("aps-har-process-")
        val script = directory.resolve("native.sh")
        val pid = directory.resolve("pid")
        Files.writeString(script, "#!/bin/sh\nprintf '%s' \"\$\$\" > '${pid.toString().replace("'", "'\\''")}'\nexec sleep 30\n")
        assertTrue(script.toFile().setExecutable(true, true))
        try {
            fixture("{}") { file ->
                val started = System.nanoTime()
                assertFailsWith<NativeHarException> { NativeHarParser(script, timeoutMillis = 2000).parse(file) }
                assertTrue((System.nanoTime() - started) < 5_000_000_000L)
                val handle = ProcessHandle.of(Files.readString(pid).toLong())
                assertFalse(handle.isPresent && handle.get().isAlive)
                Files.delete(pid)
                assertFailsWith<CancellationException> {
                    NativeHarParser(script).parse(file) {
                        if (Files.exists(pid)) throw CancellationException("cancel while reading")
                    }
                }
                val cancelledHandle = ProcessHandle.of(Files.readString(pid).toLong())
                assertFalse(cancelledHandle.isPresent && cancelledHandle.get().isAlive)
            }
        } finally {
            Files.deleteIfExists(pid)
            Files.deleteIfExists(script)
            Files.deleteIfExists(directory)
        }
    }

    private fun binary(): Path {
        val binary = System.getenv("APS_HAR_ENGINE_TEST")?.let(Path::of)
        assumeTrue(binary != null && Files.isExecutable(binary), "Build Rust and set APS_HAR_ENGINE_TEST")
        return requireNotNull(binary)
    }

    private fun fixture(input: String, test: (Path) -> Unit) {
        val path = Files.createTempFile("aps-native-har-", ".har")
        try {
            Files.writeString(path, input)
            test(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
