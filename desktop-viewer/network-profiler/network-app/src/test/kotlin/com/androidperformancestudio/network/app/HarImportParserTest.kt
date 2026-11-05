package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.har.HarParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HarImportParserTest {
    @Test
    fun `default stays legacy and fallback is explicit and observable`() = fixture { path ->
        val legacy = HarParser().parse(path)
        assertEquals(legacy.calls, HarImportParser(executable = Path.of("not-used")).parse(path).calls)
        for (binary in listOf(null, Path.of("/missing/aps-har-engine"))) {
            val fallback = HarImportParser(HarImportMode.NATIVE_WITH_LEGACY_FALLBACK, binary).parse(path)
            assertEquals(legacy.calls, fallback.calls)
            assertEquals(legacy.session.warnings + "Native HAR unavailable or rejected; used legacy parser.", fallback.session.warnings)
            assertFailsWith<IllegalArgumentException> { HarImportParser(HarImportMode.NATIVE_EXPERIMENTAL, binary).parse(path) }
        }
    }

    @Test
    fun `native app entry uses actual Rust and retained analyzer model`() = fixture { path ->
        val binary = System.getenv("APS_HAR_ENGINE_TEST")?.let(Path::of)
        assumeTrue(binary != null, "Build Rust and set APS_HAR_ENGINE_TEST")
        val legacy = HarParser().parse(path)
        val result = HarImportParser(HarImportMode.NATIVE_EXPERIMENTAL, binary).parse(path)
        assertEquals(legacy.calls, result.calls)
        assertEquals(legacy.session, result.session.copy(id = legacy.session.id))
    }

    @Test
    fun `fallback never swallows cancellation`() = fixture { path ->
        var checks = 0
        assertFailsWith<CancellationException> {
            HarImportParser(HarImportMode.NATIVE_WITH_LEGACY_FALLBACK).parse(path) {
                checks++
                if (checks > 1) throw CancellationException("cancelled")
            }
        }
        assertTrue(checks > 1)
    }

    @Test
    fun `real native strict rejection falls back only in explicit fallback mode`() = fixture { path ->
        val binary = System.getenv("APS_HAR_ENGINE_TEST")?.let(Path::of)
        assumeTrue(binary != null, "Build Rust and set APS_HAR_ENGINE_TEST")
        Files.writeString(path, """{"log":{"version":"1.2","entries":[{"request":{"method":GET,"url":"http://example.test/private"}}]}}""")
        val legacy = HarParser().parse(path)
        assertFailsWith<IllegalArgumentException> { HarImportParser(HarImportMode.NATIVE_EXPERIMENTAL, binary).parse(path) }
        val result = HarImportParser(HarImportMode.NATIVE_WITH_LEGACY_FALLBACK, binary).parse(path)
        assertEquals(legacy.calls, result.calls)
        assertEquals(listOf("Native HAR unavailable or rejected; used legacy parser."), result.session.warnings)
    }

    @Test
    fun `legacy JSON errors are also sanitized at import boundary`() = fixture { path ->
        Files.writeString(path, """{"log":{"version":"1.2","entries":["private-body-secret"""")
        val error = assertFailsWith<IllegalArgumentException> { HarImportParser().parse(path) }
        assertEquals("HAR import rejected", error.message)
        assertEquals(null, error.cause)
    }

    @Test
    fun `shadow and missing native fallback cancel during actual legacy decoding`() = fixture { path ->
        Files.writeString(path, """{"log":{"version":"1.2","entries":[{"request":{},"body":"${"x".repeat(512 * 1024)}"}]}} invalid-tail""")
        for (mode in listOf(HarImportMode.SHADOW, HarImportMode.NATIVE_WITH_LEGACY_FALLBACK)) {
            val cancellation = CancellationException("cancel legacy decoding")
            var decoderReads = 0
            val caught = assertFailsWith<CancellationException> {
                HarImportParser(mode).parse(path) {
                    if (Thread.currentThread().stackTrace.any { it.className.startsWith("kotlinx.serialization.json.") }) {
                        if (++decoderReads == 6) throw cancellation
                    }
                }
            }
            assertTrue(caught === cancellation, mode.name)
            assertEquals(6, decoderReads, mode.name)
        }
    }

    @Test
    fun `real native rejection fallback remains cancellable while legacy decodes`() = fixture { path ->
        val binary = System.getenv("APS_HAR_ENGINE_TEST")?.let(Path::of)
        assumeTrue(binary != null, "Build Rust and set APS_HAR_ENGINE_TEST")
        Files.writeString(path, """{"log":{"version":"1.2","entries":[{"request":{"method":GET},"body":"${"x".repeat(200_000)}"}]}}""")
        val cancellation = CancellationException("cancel native fallback decoding")
        var decoderReads = 0
        val caught = assertFailsWith<CancellationException> {
            HarImportParser(HarImportMode.NATIVE_WITH_LEGACY_FALLBACK, binary).parse(path) {
                if (Thread.currentThread().stackTrace.any { it.className.startsWith("kotlinx.serialization.json.") }) {
                    if (++decoderReads == 6) throw cancellation
                }
            }
        }
        assertTrue(caught === cancellation)
        assertEquals(6, decoderReads)
    }

    private fun fixture(test: (Path) -> Unit) {
        val path = Files.createTempFile("aps-har-mode-", ".har")
        try {
            Files.writeString(path, """{"log":{"version":"1.2","entries":[{"request":{"method":"GET","url":"https://example.test/private?token=secret"},"time":2}]}}""")
            test(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
