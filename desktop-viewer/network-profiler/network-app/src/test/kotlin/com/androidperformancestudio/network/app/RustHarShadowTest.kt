package com.androidperformancestudio.network.app

import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.network.har.HarParser
import com.androidperformancestudio.platform.toolchain.NativeShadowStatus
import com.androidperformancestudio.platform.toolchain.NativeSummaryShadow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RustHarShadowTest {
    @Test
    fun `actual Rust shadow agrees with retained HAR parser without publishing private fields`() = runBlocking {
        val binary = System.getenv("APS_HAR_ENGINE_TEST")?.let(Path::of)
        assumeTrue(binary != null, "Set APS_HAR_ENGINE_TEST to an explicitly built native executable")
        val file = Files.createTempFile("aps-har-shadow-", ".har")
        try {
            Files.writeString(
                file,
                """
                {"log":{"version":"1.2","creator":{"name":"fixture"},"entries":[{
                  "startedDateTime":"2026-01-01T00:00:00Z","time":30,
                  "request":{"method":"GET","url":"https://example.test/private?token=secret","headers":[],"bodySize":0},
                  "response":{"status":200,"headers":[],"bodySize":100},
                  "timings":{"blocked":1,"dns":-1,"connect":-1,"ssl":-1,"send":1,"wait":20,"receive":8}
                }]}}
                """.trimIndent(),
            )
            val digest = ArtifactFileEvidence.sha256(file).value
            val legacy = HarParser().parse(file)
            val shadow = NativeSummaryShadow().compare(
                requireNotNull(binary), file, mapOf("entryCount" to legacy.calls.size.toLong()), digest,
            )
            assertEquals(NativeShadowStatus.SUMMARY_MATCHED, shadow.status)
            assertFalse(shadow.properties().contains("secret"))
            assertFalse(shadow.properties().contains("example.test"))
            assertEquals(digest, ArtifactFileEvidence.sha256(file).value)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
