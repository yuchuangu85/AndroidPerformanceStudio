package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.analysis.NetworkAnalyzer
import com.androidperformancestudio.network.model.ConnectionUse
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.model.TimingAvailability
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
}
