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
    fun `workspace uses shared compact chrome without changing artifact screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactButton"))
        assertTrue(source.contains("GpuIntegrationScreen("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
    }

    @Test
    fun `toolbar preserves locator launch importer persistence and enabled wiring`() {
        val homeBlock =
            source.substring(
                source.indexOf("ProfilerHomeButton("),
                source.indexOf("ProfilerCompactButton("),
            )
        assertTrue(homeBlock.contains("onClick = onBack"))

        assertButtonContains(
            "text = if (chinese) \"刷新 AGI\" else \"Refresh AGI\"",
            "capability = locator.locate()",
        )
        assertButtonContains(
            "text = if (chinese) \"配置 AGI\" else \"Configure AGI\"",
            "chooseExecutable(window)",
            "capability = locator.locate(file.toPath())",
        )
        assertButtonContains(
            "text = if (chinese) \"启动 AGI\" else \"Launch AGI\"",
            "enabled = state.capability?.launchSupported == true",
            "locator.launch(requireNotNull(state.capability))",
        )
        assertButtonContains(
            "text = if (chinese) \"导入产物\" else \"Import Artifact\"",
            "chooseArtifact(window)",
            "indexer.import(",
            "persist(updated)",
        )
    }

    @Test
    fun `artifact actions preserve viewer navigation and verification calls`() {
        assertTrue(source.contains("ArtifactOpenCapability.PERFETTO -> onOpenTrace(artifact.path)"))
        assertTrue(source.contains("ArtifactOpenCapability.AGI ->"))
        assertTrue(source.contains("locator.launch("))
        assertTrue(source.contains("ArtifactOpenCapability.DESKTOP -> Desktop.getDesktop().open(artifact.path.toFile())"))
        assertTrue(source.contains("onOpenArtifact = ::open"))
        assertTrue(source.contains("if (indexer.verify(artifact))"))
    }

    private fun assertButtonContains(
        anchor: String,
        vararg invariants: String,
    ) {
        val anchorIndex = source.indexOf(anchor)
        assertTrue(anchorIndex >= 0, "Missing anchor: $anchor")
        val blockStart = source.lastIndexOf("ProfilerCompactButton(", anchorIndex)
        val blockEnd = source.indexOf("ProfilerCompactButton(", anchorIndex + anchor.length).let {
            if (it >= 0) it else source.indexOf("Spacer(", anchorIndex)
        }
        val block = source.substring(blockStart, blockEnd)

        invariants.forEach { invariant ->
            assertTrue(block.contains(invariant), "Missing `$invariant` near `$anchor`")
        }
    }
}
