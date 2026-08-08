package com.androidperformancestudio.gpu.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuIntegrationWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationMainPage.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactButton"))
        assertTrue(source.contains("HomeButton("))
        assertTrue(source.contains("GpuIntegrationScreen("))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
    }

    @Test
    fun `artifact IO runs outside the compose thread`() {
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("indexer.import("))
        assertTrue(source.contains("indexer.verify(artifact)"))
        assertTrue(source.contains("indexer.relocate(artifact"))
        assertTrue(source.contains("store.save(updated)"))
    }

    @Test
    fun `runtime availability controls each opening route`() {
        assertTrue(source.contains("artifactAvailability(artifact"))
        assertTrue(source.contains("ArtifactPrimaryAction.OPEN_PERFETTO -> onOpenTrace(path)"))
        assertTrue(source.contains("ArtifactPrimaryAction.OPEN_AGI -> locator.launchArtifact"))
        assertTrue(source.contains("ArtifactPrimaryAction.LAUNCH_AGI -> locator.launch"))
        assertTrue(source.contains("ArtifactPrimaryAction.OPEN_DESKTOP -> Desktop.getDesktop().open"))
        assertTrue(source.contains("onRevealArtifact = ::reveal"))
        assertTrue(source.contains("onRelocateArtifact = ::relocate"))
    }
}
