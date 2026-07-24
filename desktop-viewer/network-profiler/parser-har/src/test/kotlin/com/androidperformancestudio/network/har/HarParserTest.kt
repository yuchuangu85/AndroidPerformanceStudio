package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.model.NetworkPhaseKind
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertNull(call.exchanges.single().phases.firstOrNull { it.kind == NetworkPhaseKind.DNS })
        assertTrue(result.session.warnings.isNotEmpty())
    }
}
