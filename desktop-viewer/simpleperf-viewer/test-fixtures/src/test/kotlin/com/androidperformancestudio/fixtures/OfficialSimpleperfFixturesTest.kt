package com.androidperformancestudio.fixtures

import com.androidperformancestudio.export.SessionPackageService
import com.androidperformancestudio.storage.SQLiteSampleStore
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OfficialSimpleperfFixturesTest {
    @Test
    fun `aosp perf data fixture is pinned and checksum verified`() {
        val fixture = OfficialSimpleperfFixtures.AOSP_PERF_DATA
        val resource = assertNotNull(javaClass.getResourceAsStream(fixture.resourcePath))
        val checksum =
            resource.use { input ->
                val hash = MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())
                hash.joinToString("") { byte -> "%02x".format(byte) }
            }

        assertEquals(136_396L, fixture.sizeBytes)
        assertEquals(fixture.sha256, checksum)
        assertEquals(
            "0913958dce781fb91c415e666623e46d3c17b3e1",
            fixture.upstreamRevision,
        )
    }

    @Test
    fun `generated golden session is deterministic importable and queryable`() {
        val fixture = OfficialSimpleperfFixtures.GENERATED_GOLDEN_SESSION
        val archive = Files.createTempFile("golden", ".apsession.zip")
        val bytes = assertNotNull(javaClass.getResourceAsStream(fixture.resourcePath)).use { it.readAllBytes() }
        Files.write(archive, bytes)

        val imported = SessionPackageService().import(archive, Files.createTempDirectory("golden-import-"))

        assertEquals(fixture.sizeBytes, bytes.size.toLong())
        assertEquals(fixture.sha256, bytes.sha256())
        SQLiteSampleStore.open(imported.sessionDirectory.resolve("profile.sqlite")).use { store ->
            assertEquals(3L, store.sampleCount())
            assertEquals("mainLoop", store.topFunctions(limit = 1).single().symbolName)
        }
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
