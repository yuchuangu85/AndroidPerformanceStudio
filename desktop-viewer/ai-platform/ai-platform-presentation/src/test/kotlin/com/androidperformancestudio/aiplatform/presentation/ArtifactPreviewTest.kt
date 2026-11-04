package com.androidperformancestudio.aiplatform.presentation

import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.script.ArtifactProvenance
import com.androidperformancestudio.aiplatform.script.ArtifactRequest
import com.androidperformancestudio.aiplatform.script.ArtifactSection
import com.androidperformancestudio.aiplatform.script.PublishedArtifact
import com.androidperformancestudio.aiplatform.script.ScriptException
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.AbstractPreferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactPreviewTest {
    @Test
    fun `default HTML and explicit Markdown stage exact bytes before invoking the callback`() =
        withProfile { root ->
            openRuntime(root.resolve("profile")).use { runtime ->
                for ((report, extension) in reports()) {
                    val artifact = runtime.artifacts.publish(report)
                    assertEquals(report.format, artifact.format)
                    assertPreview(runtime, artifact, extension, runtime.artifacts.read(artifact.hash))
                }
            }
        }

    @Test
    fun `restored reports use persisted metadata despite a different current report setting`() {
        for ((report, extension) in reports()) {
            withProfile { root ->
                val preferences = PreviewPreferences().node(UUID.randomUUID().toString())
                try {
                    val settings = AiPlatformSettingsStore(preferences)
                    settings.save(AiPlatformSettings(reportFormat = report.format))
                    val profile = root.resolve("profile")
                    val (artifact, bytes) =
                        openRuntime(profile).use { runtime ->
                            val published = runtime.artifacts.publish(report)
                            assertFalse(Files.exists(runtime.directories.artifacts.resolve("previews"), NOFOLLOW_LINKS))
                            published to runtime.artifacts.read(published.hash)
                        }
                    val currentFormat = if (report.format == ArtifactFormat.HTML) ArtifactFormat.MARKDOWN else ArtifactFormat.HTML
                    settings.update { it.copy(reportFormat = currentFormat) }

                    openRuntime(profile).use { restored ->
                        assertEquals(report.format, restored.artifacts.metadata(artifact.hash).format)
                        assertEquals(currentFormat, settings.load().reportFormat)
                        assertPreview(restored, artifact, extension, bytes)
                        assertEquals(currentFormat, settings.load().reportFormat)
                    }
                } finally {
                    preferences.removeNode()
                }
            }
        }
    }

    @Test
    fun `same size content tampering rejects before staging or invoking the callback`() =
        withProfile { root ->
            openRuntime(root.resolve("profile")).use { runtime ->
                for ((report, _) in reports()) {
                    val artifact = runtime.artifacts.publish(report)
                    val tampered = runtime.artifacts.read(artifact.hash)
                    tampered[0] = (tampered[0].toInt() xor 1).toByte()
                    Files.write(
                        runtime.directories.artifacts
                            .resolve(artifact.hash)
                            .resolve("content"),
                        tampered,
                    )
                    var opened = false

                    val failure =
                        assertFailsWith<ScriptException> {
                            previewArtifact(runtime, artifact.hash) { _, _ -> opened = true }
                        }

                    assertEquals("E_ARTIFACT_HASH", failure.code)
                    assertFalse(opened)
                    assertFalse(Files.exists(runtime.directories.artifacts.resolve("previews"), NOFOLLOW_LINKS))
                }
            }
        }

    @Test
    fun `symlink preview directory rejects without opening or changing its target`() =
        withProfile { root ->
            openRuntime(root.resolve("profile")).use { runtime ->
                val artifact = runtime.artifacts.publish(request())
                val outside = Files.createDirectory(root.resolve("outside-previews"))
                val target = outside.resolve("${artifact.hash}.html")
                val original = "Do not overwrite".toByteArray()
                Files.write(target, original)
                val previews = runtime.directories.artifacts.resolve("previews")
                createSymlink(previews, outside)
                var opened = false

                assertFailsWith<IllegalArgumentException> {
                    previewArtifact(runtime, artifact.hash) { _, _ -> opened = true }
                }

                assertFalse(opened)
                assertTrue(Files.isSymbolicLink(previews))
                assertContentEquals(original, Files.readAllBytes(target))
            }
        }

    @Test
    fun `symlink preview file rejects without opening or changing its target`() =
        withProfile { root ->
            openRuntime(root.resolve("profile")).use { runtime ->
                val artifact = runtime.artifacts.publish(request().copy(format = ArtifactFormat.MARKDOWN))
                val target = root.resolve("outside.md")
                val original = "Do not overwrite".toByteArray()
                Files.write(target, original)
                val previews = Files.createDirectory(runtime.directories.artifacts.resolve("previews"))
                val preview = previews.resolve("${artifact.hash}.md")
                createSymlink(preview, target)
                var opened = false

                assertFailsWith<IllegalArgumentException> {
                    previewArtifact(runtime, artifact.hash) { _, _ -> opened = true }
                }

                assertFalse(opened)
                assertTrue(Files.isSymbolicLink(preview))
                assertContentEquals(original, Files.readAllBytes(target))
            }
        }

    private fun assertPreview(
        runtime: AiPlatformRuntime,
        artifact: PublishedArtifact,
        extension: String,
        bytes: ByteArray,
    ) {
        val expected =
            runtime.directories.artifacts
                .resolve("previews")
                .resolve("${artifact.hash}.$extension")
        val opened = mutableListOf<Pair<Path, ArtifactFormat>>()

        previewArtifact(runtime, artifact.hash) { path, format ->
            opened += path to format
            assertEquals(expected, path)
            assertEquals(artifact.format, format)
            assertTrue(Files.isRegularFile(path, NOFOLLOW_LINKS))
            assertContentEquals(bytes, Files.readAllBytes(path))
        }

        assertEquals(listOf(expected to artifact.format), opened)
    }

    private fun reports() = listOf(request() to "html", request().copy(format = ArtifactFormat.MARKDOWN) to "md")

    private fun request() =
        ArtifactRequest(
            title = "Performance report",
            sections = listOf(ArtifactSection("Finding", "Frame time < 16 ms & stable\nSecond line")),
            provenance =
                ArtifactProvenance(
                    sessionId = "session-1",
                    generation = 1,
                    agentId = "aps.report",
                    agentVersion = "1.0.0",
                    evidenceIds = setOf("evidence-1"),
                    candidateIds = setOf("candidate-1"),
                    inputHash = "a".repeat(64),
                ),
            grantedPermissions = setOf(Permission.WRITE_ARTIFACT),
            allowedEvidenceIds = setOf("evidence-1"),
            allowedCandidateIds = setOf("candidate-1"),
        )

    private fun openRuntime(profile: Path) =
        AiPlatformRuntime.open(
            root = profile,
            openAiGateway = { error("E_FIXTURE_NO_PROVIDER") },
            openAiAvailable = { false },
            codexGateway = { error("E_FIXTURE_NO_PROVIDER") },
            codexAvailable = { false },
            claudeEnvironment = { error("E_FIXTURE_NO_CREDENTIALS") },
            gatewayForRun = { _, _, _ -> error("E_FIXTURE_NO_PROVIDER") },
            claudeEnvironmentForRun = { _ -> error("E_FIXTURE_NO_CREDENTIALS") },
        )

    private fun createSymlink(
        link: Path,
        target: Path,
    ) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links unsupported: $failure")
        } catch (failure: FileSystemException) {
            assumeTrue(false, "Symbolic links unavailable: $failure")
        } catch (failure: SecurityException) {
            assumeTrue(false, "Symbolic links denied: $failure")
        }
    }

    private fun withProfile(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("aps-artifact-preview-test-").toRealPath()
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private class PreviewPreferences(
        parent: AbstractPreferences? = null,
        name: String = "",
    ) : AbstractPreferences(parent, name) {
        private val values = mutableMapOf<String, String>()
        private val children = mutableMapOf<String, PreviewPreferences>()

        override fun isUserNode(): Boolean = true

        override fun putSpi(
            key: String,
            value: String,
        ) {
            values[key] = value
        }

        override fun getSpi(key: String): String? = values[key]

        override fun removeSpi(key: String) {
            values.remove(key)
        }

        override fun removeNodeSpi() {
            values.clear()
            children.clear()
        }

        override fun keysSpi(): Array<String> = values.keys.toTypedArray()

        override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()

        override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { PreviewPreferences(this, name) }

        override fun syncSpi() = Unit

        override fun flushSpi() = Unit
    }
}
