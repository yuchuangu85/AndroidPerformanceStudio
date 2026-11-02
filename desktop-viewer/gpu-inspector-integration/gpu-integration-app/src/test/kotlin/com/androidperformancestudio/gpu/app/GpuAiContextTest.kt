package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.presentation.GpuIntegrationState
import com.androidperformancestudio.gpu.model.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
