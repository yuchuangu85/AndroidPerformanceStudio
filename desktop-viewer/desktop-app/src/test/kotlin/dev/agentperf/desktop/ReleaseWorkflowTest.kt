package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseWorkflowTest {
    private val workflowPath = Path.of("../../.github/workflows/release.yml")

    @Test
    fun `release workflow supports tag and manual releases`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("tags:"))
        assertTrue(workflow.contains("- \"v*\""))
        assertTrue(workflow.contains("workflow_dispatch:"))
        assertTrue(workflow.contains("version:"))
        assertTrue(workflow.contains("""^v?[0-9]+\.[0-9]+\.[0-9]+${'$'}"""))
    }

    @Test
    fun `tests gate both native package jobs and publishing`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("test:"))
        assertTrue(workflow.contains("./desktop-viewer/gradlew -p desktop-viewer test --no-daemon"))
        assertTrue(workflow.contains("package-windows:"))
        assertTrue(workflow.contains("runs-on: windows-latest"))
        assertTrue(workflow.contains(":desktop-app:packageMsi"))
        assertTrue(workflow.contains("package-macos:"))
        assertTrue(workflow.contains("runs-on: macos-14"))
        assertTrue(workflow.contains(":desktop-app:packageDmg"))
        assertTrue(workflow.contains("needs: [resolve, test]"))
        assertTrue(
            workflow.contains(
                "needs: [resolve, test, package-windows, package-macos]",
            ),
        )
    }

    @Test
    fun `publish job has write access and stable native asset names`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("permissions:\n  contents: read"))
        assertTrue(workflow.contains("permissions:\n      contents: write"))
        assertTrue(workflow.contains("AgentPerf-Inspector-${'$'}VERSION-windows-x64.msi"))
        assertTrue(workflow.contains("AgentPerf-Inspector-${'$'}VERSION-macos-x64.dmg"))
        assertTrue(workflow.contains("gh release create"))
        assertTrue(workflow.contains("gh release upload"))
        assertTrue(workflow.contains("--clobber"))
    }

    @Test
    fun `workflow uses JDK 21 Gradle caching and official artifact actions`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("uses: actions/checkout@v6"))
        assertTrue(workflow.contains("uses: actions/setup-java@v4"))
        assertTrue(workflow.contains("java-version: \"21\""))
        assertTrue(workflow.contains("cache: gradle"))
        assertTrue(workflow.contains("uses: actions/upload-artifact@v4"))
        assertTrue(workflow.contains("uses: actions/download-artifact@v5"))
    }
}
