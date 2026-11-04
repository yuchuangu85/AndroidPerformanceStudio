package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.presentation.GpuIntegrationState
import com.androidperformancestudio.gpu.model.*
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.boundedAiContext
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuAiContextTest {
    @Test
    fun `canonical selection preserves metrics missing states and rejects stale ID`() {

        val artifact=GpuArtifact(id="artifact-1",kind=GpuArtifactKind.PERFETTO_TRACE,path=Path.of("/private/secret/trace"),sha256="a".repeat(64),sizeBytes=123,agiVersion=null,device=null,packageName=null,graphicsApi=null,capturedAt=null,notes="secret notes",openRoute=ArtifactOpenRoute.PERFETTO)
        val state=GpuIntegrationState(artifacts=listOf(artifact),selectedArtifactId=artifact.id)
        val context=gpuAiContext(state)!!
        assertTrue(context.currentSelection)
        assertFalse("/private" in context.evidence.single().payloadJson)
        assertFalse("secret notes" in context.evidence.single().payloadJson)
        assertTrue("\"gpuTimingMetricsAvailable\":false" in context.evidence.single().payloadJson)
        assertEquals(null,gpuAiContext(state.copy(selectedArtifactId="stale")))
    }

    @Test
    fun `bounded context uses UTF8 byte limits`() {
        val oversized = UiAiContext("gpu", "title", false, listOf(UiAiEvidence("id", "kind", "ok", "\u4e2d".repeat(3_000))))
        assertNull(boundedAiContext(oversized))
    }

    @Test
    fun `artifact summary keeps eight metadata rows without files or notes`() {
        val artifact = GpuArtifact(
            id = "artifact-1", kind = GpuArtifactKind.PERFETTO_TRACE,
            path = Path.of("/private/secret/trace"), sha256 = "a".repeat(64), sizeBytes = 123,
            agiVersion = null, device = null, packageName = null, graphicsApi = null,
            capturedAt = null, notes = "secret notes", openRoute = ArtifactOpenRoute.PERFETTO,
        )
        val artifacts = List(22) { artifact.copy(id = "artifact-$it") }
        val context = boundedAiContext(gpuAiContext(GpuIntegrationState(artifacts = artifacts)))!!
        val payload = context.evidence.single().payloadJson
        assertFalse(context.currentSelection)
        assertTrue("\"includedArtifacts\":8" in payload)
        assertTrue("\"omittedArtifacts\":14" in payload)
        assertFalse("/private" in payload)
        assertFalse("secret notes" in payload)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
    }
}
