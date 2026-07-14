package com.androidperformancestudio.export

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionPackageServiceTest {
    @Test
    fun `exports deterministic manifest and imports complete session`() {
        val source = Files.createTempDirectory("aps-session-source-")
        source.resolve("profile.sqlite").writeText("database")
        source.resolve("simpleperf.protobuf").writeText("protobuf")
        source
            .resolve("symbols")
            .createDirectories()
            .resolve("libapp.so")
            .writeText("symbols")
        val archive = Files.createTempFile("profile", ".apsession.zip")
        val destination = Files.createTempDirectory("aps-session-import-")

        val exported = SessionPackageService().export(source, archive)
        val imported = SessionPackageService().import(archive, destination)

        assertEquals(3, exported.fileCount)
        assertEquals("database", imported.sessionDirectory.resolve("profile.sqlite").readText())
        assertEquals("protobuf", imported.sessionDirectory.resolve("simpleperf.protobuf").readText())
        assertEquals("symbols", imported.sessionDirectory.resolve("symbols/libapp.so").readText())
        assertTrue(imported.verifiedFiles == 3)
    }

    @Test
    fun `rejects zip slip entries before writing outside destination`() {
        val archive = Files.createTempFile("malicious", ".apsession.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped.txt"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        val destination = Files.createTempDirectory("aps-session-safe-")

        assertFailsWith<SessionPackageException> { SessionPackageService().import(archive, destination) }

        assertTrue(!destination.parent.resolve("escaped.txt").exists())
    }

    @Test
    fun `rejects session symlinks instead of packaging files outside the session`() {
        val source = Files.createTempDirectory("aps-session-symlink-")
        val outside = Files.createTempFile("aps-secret-", ".txt").also { it.writeText("secret") }
        val linkCreated = runCatching { Files.createSymbolicLink(source.resolve("leak.txt"), outside) }.isSuccess
        assumeTrue(linkCreated, "Symbolic links aren't available on this test host")

        assertFailsWith<SessionPackageException> {
            SessionPackageService().export(source, Files.createTempFile("profile", ".apsession.zip"))
        }
    }

    @Test
    fun `rejects archives exceeding bounded extraction limits and removes temporary files`() {
        val archive = Files.createTempFile("oversized", ".apsession.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("large.bin"))
            zip.write("123456".toByteArray())
            zip.closeEntry()
        }
        val destination = Files.createTempDirectory("aps-session-bounded-")

        assertFailsWith<SessionPackageException> {
            SessionPackageService(maxEntryBytes = 5, maxTotalBytes = 10, maxEntries = 5).import(archive, destination)
        }

        assertEquals(emptyList(), Files.list(destination).use { it.toList() })
    }
}
