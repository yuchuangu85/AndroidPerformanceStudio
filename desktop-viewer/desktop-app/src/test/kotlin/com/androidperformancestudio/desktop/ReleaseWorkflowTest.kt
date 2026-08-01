package com.androidperformancestudio.desktop

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
    fun `tests gate native packaging and profiler assets are built before packaging`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("test:"))
        assertTrue(
            workflow.contains(
                "./desktop-viewer/gradlew -p desktop-viewer test --no-daemon",
            ),
        )
        assertTrue(!workflow.contains("simpleperfCheck --no-daemon"))
        assertTrue(workflow.contains("prepare-release:"))
        assertTrue(workflow.contains("--draft"))
        assertTrue(workflow.contains("uses: actions/setup-node@v6"))
        assertTrue(workflow.contains("node-version: \"24\""))
        assertTrue(workflow.contains("npm install --global yarn@1"))
        assertTrue(workflow.contains("./scripts/firefox-profiler.sh all"))
        assertTrue(workflow.contains("./scripts/build-perfetto-ui.sh download"))
        assertTrue(workflow.contains("./scripts/install-trace-processor.sh"))
        assertTrue(!workflow.contains("actions/upload-artifact"))
        assertTrue(!workflow.contains("actions/download-artifact"))
        assertTrue(workflow.contains("package-windows:"))
        assertTrue(workflow.contains("runs-on: windows-latest"))
        assertTrue(workflow.contains(":desktop-app:packageMsi"))
        assertTrue(workflow.contains(":desktop-app:packageExe"))
        assertTrue(Regex(Regex.escape("-Ptarget.arch=x64")).findAll(workflow).count() >= 3)
        assertTrue(workflow.contains("package-macos:"))
        assertTrue(workflow.contains("runner: macos-15"))
        assertTrue(workflow.contains("runner: macos-15-intel"))
        assertTrue(workflow.contains("- arch: arm64"))
        assertTrue(workflow.contains("- arch: x64"))
        assertTrue(workflow.contains("runs-on: ${'$'}{{ matrix.runner }}"))
        assertTrue(workflow.contains("-Ptarget.arch=${'$'}{{ matrix.arch }}"))
        assertTrue(workflow.contains(":desktop-app:packageDmg"))
        assertTrue(workflow.contains(":desktop-app:packagePkg"))
        assertTrue(workflow.contains("package-linux:"))
        assertTrue(workflow.contains("runs-on: ubuntu-latest"))
        assertTrue(workflow.contains(":desktop-app:packageDeb"))
        assertTrue(workflow.contains(":desktop-app:packageRpm"))
        assertTrue(workflow.contains("needs: [resolve, test, prepare-release]"))
        assertTrue(
            workflow.contains(
                "needs: [resolve, test, prepare-release, package-windows, package-macos, package-linux]",
            ),
        )
    }

    @Test
    fun `publish job has write access and stable native asset names`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("permissions:\n  contents: read"))
        assertTrue(workflow.contains("permissions:\n      contents: write"))
        assertTrue(workflow.contains("AndroidPerfermanceStudio-${'$'}VERSION-windows-x64.msi"))
        assertTrue(workflow.contains("AndroidPerfermanceStudio-${'$'}VERSION-windows-x64.exe"))
        assertTrue(workflow.contains("AndroidPerfermanceStudio-${'$'}VERSION-macos-${'$'}ARCH.dmg"))
        assertTrue(workflow.contains("AndroidPerfermanceStudio-${'$'}VERSION-macos-${'$'}ARCH.pkg"))
        assertTrue(workflow.contains("macos-arm64.dmg"))
        assertTrue(workflow.contains("macos-arm64.pkg"))
        assertTrue(workflow.contains("macos-x64.dmg"))
        assertTrue(workflow.contains("macos-x64.pkg"))
        assertTrue(workflow.contains("AndroidPerfermanceStudio-${'$'}VERSION-linux-x64.deb"))
        assertTrue(workflow.contains("AndroidPerfermanceStudio-${'$'}VERSION-linux-x64.rpm"))
        assertTrue(workflow.contains("Release asset mismatch."))
        assertTrue(workflow.contains("gh release create"))
        assertTrue(workflow.contains("gh release upload"))
        assertTrue(workflow.contains("tag_exists=${'$'}{tag_exists}"))
        assertTrue(workflow.contains("TAG_EXISTS: ${'$'}{{ needs.resolve.outputs.tag_exists }}"))
        assertTrue(workflow.contains("""elif [[ "${'$'}TAG_EXISTS" == "true" ]]; then"""))
        assertTrue(workflow.contains("--clobber"))
    }

    @Test
    fun `workflow uses JDK 21 Gradle caching and official artifact actions`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("uses: actions/checkout@v6"))
        assertTrue(workflow.contains("uses: actions/setup-java@v5"))
        assertTrue(workflow.contains("java-version: \"21\""))
        assertTrue(workflow.contains("cache: gradle"))
        assertTrue(!workflow.contains("uses: actions/upload-artifact"))
        assertTrue(!workflow.contains("uses: actions/download-artifact"))
    }

    @Test
    fun `trace processor launcher comes from the pinned Perfetto submodule`() {
        val installer = Files.readString(Path.of("../../scripts/install-trace-processor.sh"))
        val gitmodules = Files.readString(Path.of("../../.gitmodules"))

        assertTrue(installer.contains("third_party/perfetto"))
        assertTrue(installer.contains("tools/trace_processor"))
        assertTrue(installer.contains("ls-files --stage -- third_party/perfetto"))
        assertTrue(!installer.contains("rev-list -n 1 \"${'$'}version\""))
        assertTrue(!installer.contains("get.perfetto.dev"))
        assertTrue(!gitmodules.contains("branch = v57.2"))
    }
}
