package com.androidperformancestudio.gpu.artifact

import com.androidperformancestudio.gpu.model.GpuArtifactKind
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `round trips artifact index`() {
        val dir = createTempDirectory()
        val file = dir.resolve("capture.gfxtrace")
        file.writeText("opaque")
        val artifact = AgiArtifactIndexer().import(file)
        val store = JsonAgiArtifactStore(dir.resolve("index.json"))
        store.save(listOf(artifact))
        assertEquals(artifact.sha256, store.load().single().sha256)
    }
}
