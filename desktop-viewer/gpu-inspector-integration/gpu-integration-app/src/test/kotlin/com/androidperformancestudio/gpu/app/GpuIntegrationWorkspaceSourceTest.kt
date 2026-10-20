package com.androidperformancestudio.gpu.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuIntegrationWorkspaceSourceTest {
    private val mainPage =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationMainPage.kt"),
        )
    private val controller =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationController.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome`() {
        assertTrue(mainPage.contains("HeaderToolbar("))
        assertFalse(mainPage.contains("ProfilerMacOsToolbar"))
        assertTrue(mainPage.contains("ProfilerCompactButton"))
        assertTrue(mainPage.contains("onNavigateHome = onBack"))
        assertTrue(mainPage.contains("GpuAnalysisHubScreen("))
        assertFalse(mainPage.contains("import androidx.compose.material3.Button"))
        assertTrue(mainPage.contains("GpuAnalysisToolbar("))
        val header = mainPage.substringAfter("HeaderToolbar(").substringBefore("HorizontalDivider(")
        assertTrue(header.contains("GpuAnalysisToolbar("))
        assertTrue(header.indexOf("GpuAnalysisToolbar(") < header.indexOf("refresh_agi"))
        assertTrue(header.indexOf("refresh_agi") < header.indexOf("configure_agi"))
        assertFalse(mainPage.contains("Spacer(Modifier.weight(1f))"))
    }

    @Test
    fun `artifact IO runs outside the compose thread`() {
        assertTrue(controller.contains("withContext(io)"))
        assertTrue(controller.contains("indexer.import("))
        assertTrue(controller.contains("indexer.verify(artifact)"))
        assertTrue(controller.contains("indexer.relocate(artifact"))
        assertTrue(controller.contains(".also(store::save)"))
    }

    @Test
    fun `runtime availability controls each opening route`() {
        assertTrue(mainPage.contains("controller.openArtifact("))
        assertTrue(mainPage.contains("val location = indexer.resolveLocation(artifact)"))
        assertTrue(controller.contains("artifactAvailability(indexedArtifact"))
        assertTrue(controller.contains("availability.locationStatus == ArtifactLocationStatus.AVAILABLE"))
        assertTrue(mainPage.contains("ArtifactPrimaryAction.OPEN_PERFETTO -> onOpenTrace(path)"))
        assertTrue(mainPage.contains("ArtifactPrimaryAction.OPEN_AGI -> withContext(Dispatchers.IO) { locator.launchArtifact"))
        assertTrue(mainPage.contains("ArtifactPrimaryAction.LAUNCH_AGI -> withContext(Dispatchers.IO) { locator.launch"))
        assertTrue(mainPage.contains("ArtifactPrimaryAction.OPEN_DESKTOP -> withContext(Dispatchers.IO) { Desktop.getDesktop().open"))
        assertTrue(mainPage.contains("onRevealArtifact = ::reveal"))
        assertTrue(mainPage.contains("onRelocateArtifact = ::relocate"))
    }
}
