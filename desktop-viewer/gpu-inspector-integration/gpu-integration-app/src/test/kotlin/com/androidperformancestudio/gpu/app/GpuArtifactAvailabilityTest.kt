package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.artifact.AgiArtifactIndexer
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.gpu.presentation.ArtifactPrimaryAction
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class GpuArtifactAvailabilityTest {
    @Test
    fun `uses route and runtime capability without changing evidence`() {
        val directory = createTempDirectory()
        val perfettoFile = directory.resolve("capture.perfetto-trace").also { it.writeText("trace") }
        val agiFile = directory.resolve("capture.gfxtrace").also { it.writeText("trace") }
        val indexer = AgiArtifactIndexer()
        val perfetto = indexer.import(perfettoFile)
        val agi = indexer.import(agiFile)
        val guiOnly =
            AgiCapability(
                executable = directory.resolve("agi"),
                version = "3.3.3",
                launchSupported = true,
                artifactOpenSupported = false,
                launchMode = AgiLaunchMode.GUI_ONLY,
                supportedArguments = emptySet(),
                warnings = emptyList(),
            )

        assertEquals(
            ArtifactPrimaryAction.OPEN_PERFETTO,
            artifactAvailability(perfetto, null, indexer).primaryAction,
        )
        assertEquals(
            ArtifactPrimaryAction.LAUNCH_AGI,
            artifactAvailability(agi, guiOnly, indexer).primaryAction,
        )

        agiFile.deleteExisting()
        assertEquals(ArtifactPrimaryAction.NONE, artifactAvailability(agi, guiOnly, indexer).primaryAction)
    }
}
