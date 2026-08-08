package com.androidperformancestudio.gpu.artifact

import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuCaptureContext
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.model.GraphicsApi
import com.androidperformancestudio.gpu.model.GraphicsImplementationContext
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgiArtifactIndexTest {
    @Test
    fun `indexes standard trace without parsing private payload`() {
        val file = createTempDirectory().resolve("sample.perfetto-trace")
        file.writeText("PERFETTO sample")
        val artifact = AgiArtifactIndexer().import(file)
        assertEquals(GpuArtifactKind.PERFETTO_TRACE, artifact.kind)
        assertTrue(AgiArtifactIndexer().verify(artifact))
    }

    @Test
    fun `round trips complete artifact evidence`() {
        val dir = createTempDirectory()
        val file = dir.resolve("capture.gfxtrace")
        val alternate = dir.resolve("copy.gfxtrace")
        file.writeText("opaque")
        alternate.writeText("opaque")
        val context =
            GpuCaptureContext(
                device =
                    GpuDeviceContext(
                        serial = "device-1",
                        model = "Pixel",
                        apiLevel = 36,
                        gpuVendor = "Vendor",
                        gpuRenderer = "Renderer",
                        driverVersion = "1.2",
                        evidenceSources = mapOf("driverVersion" to "AGI metadata"),
                    ),
                packageName = "example.app",
                graphicsApi = GraphicsApi.OPENGL_ES,
                graphicsImplementation =
                    GraphicsImplementationContext(
                        name = "ANGLE",
                        version = "123",
                        backendApi = GraphicsApi.VULKAN,
                        evidenceSource = "AGI metadata",
                    ),
                frameCapture = true,
            )
        val artifact =
            AgiArtifactIndexer()
                .import(file, context, agiVersion = "3.3.3", notes = "baseline")
                .copy(alternativePaths = listOf(alternate))
        val store = JsonAgiArtifactStore(dir.resolve("index.json"))

        store.save(listOf(artifact))

        assertEquals(artifact, store.load().single())
    }

    @Test
    fun `corrupt index is reported without being replaced`() {
        val index = createTempDirectory().resolve("index.json")
        index.writeText("{broken")

        assertFailsWith<IllegalStateException> { JsonAgiArtifactStore(index).load() }
        assertEquals("{broken", index.readText())
    }

    @Test
    fun `loads legacy array index`() {
        val index = createTempDirectory().resolve("index.json")
        index.writeText(
            """
            [{
              "id": "legacy",
              "kind": "PERFETTO_TRACE",
              "path": "/tmp/legacy.pftrace",
              "sha256": "abc",
              "sizeBytes": 3,
              "openCapability": "PERFETTO",
              "warnings": []
            }]
            """.trimIndent(),
        )

        val artifact = JsonAgiArtifactStore(index).load().single()

        assertEquals("legacy", artifact.id)
        assertEquals(ArtifactOpenRoute.PERFETTO, artifact.openRoute)
    }

    @Test
    fun `duplicate content retains every imported location`() {
        val dir = createTempDirectory()
        val firstFile = dir.resolve("first.gfxtrace").also { it.writeText("same") }
        val secondFile = dir.resolve("second.gfxtrace").also { it.writeText("same") }
        val indexer = AgiArtifactIndexer()
        val first = indexer.import(firstFile)

        val merged = indexer.mergeLocation(listOf(first), indexer.import(secondFile)).single()

        assertEquals(secondFile.toAbsolutePath(), merged.path)
        assertEquals(listOf(firstFile.toAbsolutePath()), merged.alternativePaths)
        assertEquals(ArtifactLocationStatus.AVAILABLE, indexer.resolveLocation(merged).status)
    }

    @Test
    fun `location status and relocation preserve content identity`() {
        val dir = createTempDirectory()
        val original = dir.resolve("capture.gfxtrace").also { it.writeText("same") }
        val replacement = dir.resolve("replacement.gfxtrace").also { it.writeText("same") }
        val wrong = dir.resolve("wrong.gfxtrace").also { it.writeText("different") }
        val indexer = AgiArtifactIndexer()
        val artifact = indexer.import(original)
        original.deleteExisting()

        assertEquals(ArtifactLocationStatus.MISSING, indexer.resolveLocation(artifact).status)
        assertFailsWith<IllegalArgumentException> { indexer.relocate(artifact, wrong) }
        assertEquals(replacement.toAbsolutePath(), indexer.relocate(artifact, replacement).path)
    }
}
