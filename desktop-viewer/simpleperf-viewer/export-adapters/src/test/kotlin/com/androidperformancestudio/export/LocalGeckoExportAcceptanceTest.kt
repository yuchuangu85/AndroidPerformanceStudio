package com.androidperformancestudio.export

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opt-in local acceptance. Private fixtures and generated profiles must never enter version control. */
class LocalGeckoExportAcceptanceTest {
    @Test
    fun `export a private database copy without replacing its original browser profile`() {
        val sourceValue = System.getenv("APS_CPU_PARITY_SESSION")
        val outputValue = System.getenv("APS_CPU_PARITY_EXPORT_DIRECTORY")
        assumeTrue(
            sourceValue != null && outputValue != null,
            "Set an authorized session and a fresh ignored output directory",
        )
        val source = Path.of(requireNotNull(sourceValue)).toRealPath()
        val output = Path.of(requireNotNull(outputValue)).toAbsolutePath().normalize()
        require(!output.startsWith(source) && !source.startsWith(output)) {
            "Acceptance output must be separate from source"
        }
        require(!Files.exists(output)) { "Acceptance output directory must be new" }
        val sourceDatabase = source.resolve("profile.sqlite")
        val sourceProfile = source.resolve("perf_data.json.gz")
        val databaseHash = sha256(sourceDatabase)
        val profileHash = sha256(sourceProfile)
        Files.createDirectories(output)
        Files.copy(sourceDatabase, output.resolve("profile.sqlite"))
        val profile = output.resolve("perf_data.json.gz")
        val result = GeckoProfileExportService().export(output, profile)
        assertFalse(result.copiedOriginal)
        assertTrue(result.threadCount > 0)
        assertTrue(result.sampleCount > 0)
        GZIPInputStream(Files.newInputStream(profile)).bufferedReader().use { reader ->
            assertTrue(reader.readText().contains("\"samples\""))
        }
        assertEquals(databaseHash, sha256(output.resolve("profile.sqlite")))
        assertEquals(databaseHash, sha256(sourceDatabase), "Source database is read-only")
        assertEquals(profileHash, sha256(sourceProfile), "Original browser profile is preserved")
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
