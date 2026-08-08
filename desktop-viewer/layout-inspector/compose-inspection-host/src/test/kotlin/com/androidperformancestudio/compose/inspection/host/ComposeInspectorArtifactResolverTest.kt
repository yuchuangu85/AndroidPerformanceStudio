package com.androidperformancestudio.compose.inspection.host

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ComposeInspectorArtifactResolverTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `artifact follows the Compose KMP boundary`() {
        assertEquals("ui", ComposeInspectorArtifactResolver.composeCoordinate("1.4.3").artifact)
        assertEquals("ui-android", ComposeInspectorArtifactResolver.composeCoordinate("1.5.0").artifact)
        assertEquals("ui-android", ComposeInspectorArtifactResolver.composeCoordinate("1.11.4").artifact)
    }

    @Test
    fun `resolver extracts exact inspector and reuses checksum cache`() {
        val aar = tempDir.resolve("ui-android-1.11.4.aar")
        Files.write(aar, aar(byteArrayOf(1, 2, 3)))
        val resolver = ComposeInspectorArtifactResolver(
            cacheDir = tempDir.resolve("cache"),
            projectArtifacts = listOf(aar),
            gradleUserHome = tempDir.resolve("gradle"),
            mavenLocal = tempDir.resolve("m2"),
            enterpriseRepositories = emptyList(),
            downloader = { error("network must not be used") },
        )

        assertEquals(ComposeInspectorArtifactPlan("project-cache", false), resolver.plan("1.11.4"))
        val first = resolver.resolve("1.11.4")
        Files.delete(aar)
        val cached = resolver.resolve("1.11.4")

        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(first.jar))
        assertEquals(first.identity.sha256, cached.identity.sha256)
        assertEquals("aps-cache", cached.identity.source)
        assertEquals(ComposeInspectorArtifactPlan("aps-cache", false), resolver.plan("1.11.4"))
    }

    @Test
    fun `resolver never falls back to a nearby version`() {
        val aar = tempDir.resolve("ui-android-1.11.3.aar")
        Files.write(aar, aar(byteArrayOf(1)))
        val resolver = ComposeInspectorArtifactResolver(
            cacheDir = tempDir.resolve("cache"),
            projectArtifacts = listOf(aar),
            gradleUserHome = tempDir.resolve("gradle"),
            mavenLocal = tempDir.resolve("m2"),
            downloader = { throw IllegalStateException("not found") },
        )

        assertThrows(IllegalStateException::class.java) { resolver.resolve("1.11.4") }
    }

    @Test
    fun `explicit inspector is reported in preflight and wins without network access`() {
        val inspector = tempDir.resolve("inspector.jar")
        Files.write(inspector, byteArrayOf(4, 5, 6))
        val resolver = ComposeInspectorArtifactResolver(
            cacheDir = tempDir.resolve("cache"),
            gradleUserHome = tempDir.resolve("gradle"),
            mavenLocal = tempDir.resolve("m2"),
            downloader = { error("network must not be used") },
        )

        assertEquals(ComposeInspectorArtifactPlan("explicit-local", false), resolver.plan("1.11.4", inspector))
        assertArrayEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(resolver.resolve("1.11.4", inspector).jar))
    }

    @Test
    fun `repository path retains preview version exactly`() {
        var requested: URI? = null
        val resolver = ComposeInspectorArtifactResolver(
            cacheDir = tempDir.resolve("cache"),
            gradleUserHome = tempDir.resolve("gradle"),
            mavenLocal = tempDir.resolve("m2"),
            googleRepository = URI("https://example.test/maven/"),
            downloader = { uri -> requested = uri; aar(byteArrayOf(9)) },
        )

        resolver.resolve("1.12.0-rc01")
        assertEquals(
            "https://example.test/maven/androidx/compose/ui/ui-android/1.12.0-rc01/ui-android-1.12.0-rc01.aar",
            requested.toString(),
        )
    }

    private fun aar(inspector: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("inspector.jar"))
            zip.write(inspector)
            zip.closeEntry()
        }
        output.toByteArray()
    }
}
