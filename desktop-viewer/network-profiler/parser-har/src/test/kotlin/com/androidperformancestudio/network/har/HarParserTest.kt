package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.analysis.NetworkAnalyzer
import com.androidperformancestudio.network.model.ConnectionUse
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.model.TimingAvailability
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarParserTest {
    @Test
    fun `parses and redacts sensitive HAR without fabricating missing dns`() {
        val file = createTempFile(suffix = ".har")
        file.writeText(
            """{"log":{"version":"1.2","creator":{"name":"fixture"},"entries":[{"startedDateTime":"2026-01-01T00:00:00Z","time":30,"request":{"method":"GET","url":"https://example.test/users?token=secret","headers":[{"name":"Authorization","value":"Bearer secret"}],"bodySize":-1},"response":{"status":200,"httpVersion":"HTTP/2","headers":[],"bodySize":100},"timings":{"blocked":1,"dns":-1,"connect":-1,"ssl":-1,"send":1,"wait":20,"receive":8}}]}}""",
        )
        val result = HarParser().parse(file)
        val call = result.calls.single()
        assertFalse(call.redactedUrl.contains("secret"))
        assertEquals("<redacted>", call.exchanges.single().requestHeaders["Authorization"])
        assertTrue(call.redactedUrl.contains("/<redacted-path>"))
        assertEquals(TimingAvailability.UNAVAILABLE, call.exchanges.single().phases.single { it.kind == NetworkPhaseKind.DNS }.availability)
        assertTrue(result.session.warnings.isEmpty())
        assertEquals(100, call.exchanges.single().responseBytes)
        assertEquals(null, call.exchanges.single().decodedResponseBytes)
    }

    @Test
    fun `keeps nested ssl duration and wire size semantics`() {
        val file = createTempFile(suffix = ".har")
        file.writeText(
            """{"log":{"version":"1.2","creator":{"name":"fixture"},"entries":[{"startedDateTime":"2026-01-01T00:00:00Z","time":30,"request":{"method":"GET","url":"https://example.test/path","headers":[],"bodySize":0},"response":{"status":200,"httpVersion":"HTTP/2","headers":[],"bodySize":100,"content":{"size":120}},"timings":{"blocked":0,"dns":1,"connect":10,"ssl":5,"send":1,"wait":10,"receive":8}}]}}""",
        )
        val exchange = HarParser().parse(file).calls.single().exchanges.single()
        val connect = exchange.phases.single { it.kind == NetworkPhaseKind.CONNECT }
        val tls = exchange.phases.single { it.kind == NetworkPhaseKind.TLS }
        assertEquals(10_000_000, connect.durationNs)
        assertEquals(5_000_000, tls.durationNs)
        assertEquals(null, connect.startNs)
        assertEquals(NetworkPhaseKind.CONNECT, tls.parentKind)
        assertEquals(100, exchange.responseBytes)
        assertEquals(120, exchange.decodedResponseBytes)
    }

    @Test
    fun `HAR reuse evidence feeds analyzer metrics`() {
        val file = createTempFile(suffix = ".har")
        file.writeText(
            """{"log":{"version":"1.2","creator":{"name":"fixture"},"entries":[{"startedDateTime":"2026-01-01T00:00:00Z","time":30,"request":{"method":"GET","url":"https://example.test/users/123","headers":[],"bodySize":0},"response":{"status":200,"httpVersion":"HTTP/2","headers":[],"bodySize":100},"timings":{"blocked":1,"dns":-1,"connect":-1,"ssl":-1,"send":1,"wait":20,"receive":8}}]}}""",
        )

        val result = HarParser().parse(file)
        val exchange = result.calls.single().exchanges.single()
        val summary = NetworkAnalyzer().summarize(result.calls)

        assertEquals(ConnectionUse.REUSED, exchange.connectionUse)
        assertEquals(1, summary.connectionReuse.reusedExchangeCount)
        assertEquals(0, summary.connectionReuse.newExchangeCount)
        assertEquals(1.0, summary.connectionReuse.reuseRateAmongKnown)
    }

    @Test
    fun `rejects unsupported HAR versions`() {
        val file = createTempFile(suffix = ".har")
        file.writeText("""{"log":{"version":"9.9","entries":[]}}""")
        assertFailsWith<IllegalArgumentException> { HarParser().parse(file) }
    }

    @Test
    fun `stream decoding keeps lenient Unicode model and fingerprint of all raw bytes`() {
        val input = " \n" + """{"log":{"version":"1.1","creator":{"name":"fixture-\u96ea"},"entries":[{"request":{"method":GET,"url":"https://example.test/private?token=secret"},"time":1}]}}""" + "\r\n \t"
        fixture(input) { file ->
            val originalEntry = HarParser().parse(file)
            var checks = 0
            val guarded = HarParser().parse(file) { checks++ }
            assertTrue(checks > 10)
            assertEquals(originalEntry.calls, guarded.calls)
            assertEquals(originalEntry.session, guarded.session.copy(id = originalEntry.session.id))
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(fingerprint, guarded.session.sourceFingerprint)
            assertEquals("fixture-\u96ea", guarded.session.sourceProducer)
            assertEquals("GET", guarded.calls.single().method)
        }
    }

    @Test
    fun `cancellation interrupts actual stream JSON decoding before malformed tail`() {
        val input = """{"log":{"version":"1.2","entries":[{"request":{},"body":"${"x".repeat(512 * 1024)}"}]}} invalid-tail"""
        fixture(input) { file ->
            val cancellation = CancellationException("cancel decoding")
            var decoderReads = 0
            val caught = assertFailsWith<CancellationException> {
                HarParser().parse(file) {
                    if (Thread.currentThread().stackTrace.any { it.className.startsWith("kotlinx.serialization.json.") }) {
                        if (++decoderReads == 6) throw cancellation
                    }
                }
            }
            assertTrue(caught === cancellation)
            assertEquals(6, decoderReads)
        }
    }

    @Test
    fun `baseline mapping header iteration and final aggregation propagate cancellation unchanged`() {
        val entry = """{"request":{"headers":[{"name":"Authorization","value":"secret"},{"name":"Cookie","value":"secret"}]},"startedDateTime":"2026-01-01T00:00:00Z","time":1}"""
        fixture("""{"log":{"version":"1.2","entries":[$entry,$entry,{}]}}""") { file ->
            for (phase in listOf("entryStart", "add", "headerPairs", "finish")) {
                val cancellation = CancellationException("cancel $phase")
                var checks = 0
                val caught = assertFailsWith<CancellationException> {
                    HarParser().parse(file) {
                        if (Thread.currentThread().stackTrace.any { it.className.contains("HarParser") && it.methodName.startsWith(phase) }) {
                            if (++checks == 2) throw cancellation
                        }
                    }
                }
                assertTrue(caught === cancellation, phase)
                assertEquals(2, checks, phase)
            }
        }
    }

    @Test
    fun `stream enforces size limit when input grows after initial size check`() {
        fixture("""{"log":{"version":"1.2","entries":[{"request":{}}]}}""") { file ->
            val maxBytes = Files.size(file)
            var grew = false
            assertFailsWith<IllegalArgumentException> {
                HarParser(maxBytes).parse(file) {
                    if (!grew && Thread.currentThread().stackTrace.any { it.className.startsWith("kotlinx.serialization.json.") }) {
                        grew = true
                        Files.writeString(file, " ".repeat(16 * 1024), StandardOpenOption.APPEND)
                    }
                }
            }
            assertTrue(grew)
        }
    }

    private fun fixture(input: String, test: (Path) -> Unit) {
        val file = Files.createTempFile("aps-har-cancel-", ".har")
        try {
            Files.writeString(file, input)
            test(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
