package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.har.HarParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HarImportParserTest {
    @Test
    fun `imports through the retained Kotlin parser without changing redaction or warnings`() = fixture { path ->
        val expected = HarParser().parse(path)
        val result = HarImportParser().parse(path)
        assertEquals(expected.calls, result.calls)
        assertEquals(expected.session, result.session.copy(id = expected.session.id))
    }

    @Test
    fun `import never swallows cancellation`() = fixture { path ->
        var checks = 0
        assertFailsWith<CancellationException> {
            HarImportParser().parse(path) {
                checks++
                if (checks > 1) throw CancellationException("cancelled")
            }
        }
        assertTrue(checks > 1)
    }

    @Test
    fun `JSON errors are sanitized at import boundary`() = fixture { path ->
        Files.writeString(path, """{"log":{"version":"1.2","entries":["private-body-secret"""")
        val error = assertFailsWith<IllegalArgumentException> { HarImportParser().parse(path) }
        assertEquals("HAR import rejected", error.message)
        assertEquals(null, error.cause)
    }

    @Test
    fun `import cancels during actual JSON decoding`() = fixture { path ->
        Files.writeString(path, """{"log":{"version":"1.2","entries":[{"request":{},"body":"${"x".repeat(512 * 1024)}"}]}} invalid-tail""")
        val cancellation = CancellationException("cancel decoding")
        var decoderReads = 0
        val caught = assertFailsWith<CancellationException> {
            HarImportParser().parse(path) {
                if (Thread.currentThread().stackTrace.any { it.className.startsWith("kotlinx.serialization.json.") }) {
                    if (++decoderReads == 6) throw cancellation
                }
            }
        }
        assertTrue(caught === cancellation)
        assertEquals(6, decoderReads)
    }

    private fun fixture(test: (Path) -> Unit) {
        val path = Files.createTempFile("aps-har-import-", ".har")
        try {
            Files.writeString(path, """{"log":{"version":"1.2","entries":[{"request":{"method":"GET","url":"https://example.test/private?token=secret"},"time":2}]}}""")
            test(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
