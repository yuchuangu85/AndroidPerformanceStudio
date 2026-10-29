package com.androidperformancestudio.desktop

import com.androidperformancestudio.platform.perfetto.TraceQuerySchema
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseWorkflowTest {
    private val workflowPath = Path.of("../../.github/workflows/release.yml")

    @TempDir
    lateinit var directory: Path

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
        assertTrue(workflow.contains("node-version: \"24.19.0\""))
        assertTrue(workflow.contains("npm install --global yarn@1"))
        assertTrue(workflow.contains("./scripts/firefox-profiler.sh all"))
        assertTrue(workflow.contains("./scripts/build-perfetto-ui.sh download"))
        assertTrue(workflow.contains("./scripts/install-trace-processor.sh"))
        assertTrue(workflow.contains("package-windows:"))
        assertTrue(workflow.contains("runs-on: windows-latest"))
        assertTrue(workflow.contains(":desktop-app:packageMsi"))
        assertTrue(workflow.contains(":desktop-app:packageExe"))
        assertTrue(Regex(Regex.escape("\"-Ptarget.arch=x64\"")).findAll(workflow).count() == 2)
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
        assertTrue(workflow.contains("needs: [resolve, test, prepare-release, build-compose-agent]"))
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
        assertTrue(workflow.contains("AndroidPerfermanceStudio-${'$'}VERSION-linux-${'$'}ARCH.deb"))
        assertTrue(workflow.contains("AndroidPerfermanceStudio-${'$'}VERSION-linux-${'$'}ARCH.rpm"))
        assertTrue(workflow.contains("linux-x64.deb"))
        assertTrue(workflow.contains("linux-arm64.deb"))
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
        assertTrue(workflow.contains("uses: actions/upload-artifact@v7"))
        assertTrue(workflow.contains("uses: actions/download-artifact@v8"))
    }

    @Test
    fun `verified Compose runtime is built once and included in every native package`() {
        val workflow = Files.readString(workflowPath)
        val agent = workflowJob(workflow, "build-compose-agent")

        assertTrue(agent.contains("needs: [resolve, test]"))
        assertTrue(agent.contains("runs-on: ubuntu-24.04"))
        assertTrue(agent.contains("ref: ${'$'}{{ needs.resolve.outputs.checkout_ref }}"))
        assertTrue(agent.contains("java-version: \"21\""))
        assertTrue(agent.contains("sdk_root=\"${'$'}RUNNER_TEMP/compose-agent-sdk\""))
        assertTrue(agent.contains("--sdk_root=\"${'$'}sdk_root\" --install"))
        listOf("platforms;android-35", "build-tools;35.0.0", "ndk;27.1.12297006", "cmake;3.22.1").forEach {
            assertTrue(agent.contains("\"$it\""), "The Compose runtime must pin $it")
        }
        assertTrue(agent.contains("ANDROID_HOME=${'$'}sdk_root"))
        assertTrue(agent.contains("ANDROID_SDK_ROOT=${'$'}sdk_root"))
        val verify = agent.indexOf(":layout-inspector:compose-agent-build:verifyComposeAgentBundle")
        val upload = agent.indexOf("uses: actions/upload-artifact@v7")
        assertTrue(verify >= 0 && upload > verify, "Only a verified bundle may be shared with packaging jobs")
        assertTrue(agent.contains("--no-configuration-cache --no-daemon"))
        assertTrue(agent.contains("if-no-files-found: error"))
        assertTrue(agent.contains("name: compose-agent-bundle"))

        listOf("package-windows", "package-macos", "package-linux").forEach { name ->
            val packaging = workflowJob(workflow, name)
            assertTrue(packaging.contains("needs: [resolve, test, prepare-release, build-compose-agent]"))
            assertTrue(packaging.contains("uses: actions/download-artifact@v8"))
            assertTrue(packaging.contains("name: compose-agent-bundle"))
            assertTrue(
                packaging.contains(
                    "path: desktop-viewer/layout-inspector/compose-agent-build/build/compose-agent-bundle",
                ),
            )
            assertTrue(packaging.contains("digest-mismatch: error"))
            assertTrue(packaging.indexOf("uses: actions/download-artifact@v8") < packaging.indexOf(":desktop-app:package"))
            assertTrue(!packaging.contains("sdkmanager"), "Android tools are needed only by the shared bundle producer")
            assertTrue(packaging.contains("gh release upload"), "Installers must still upload directly to the release")
        }
        assertTrue(Regex("uses: actions/upload-artifact@").findAll(workflow).count() == 1)
        assertTrue(Regex("uses: actions/download-artifact@").findAll(workflow).count() == 3)
        assertTrue(!workflowJob(workflow, "publish").contains("compose-agent-bundle"))
    }

    @Test
    fun `Android build tools install when hosted SDK manager is not on PATH`() {
        assumeFalse(System.getProperty("os.name").contains("Windows", ignoreCase = true))
        val agent = workflowJob(Files.readString(workflowPath), "build-compose-agent")
        val install =
            agent.substringAfter("- name: Install pinned Android agent build tools")
                .substringAfter("run: |\n")
                .substringBefore("      - name:")
                .trimIndent()
        val sdk = directory.resolve("android-sdk")
        val licenses = Files.createDirectories(sdk.resolve("licenses"))
        Files.writeString(licenses.resolve("android-sdk-license"), "accepted-license")
        val sdkmanager = sdk.resolve("cmdline-tools/latest/bin/sdkmanager")
        Files.createDirectories(sdkmanager.parent)
        Files.writeString(sdkmanager, "#!/bin/bash\nprintf '%s\\n' \"${'$'}@\" > \"${'$'}SDKMANAGER_LOG\"\n")
        assertTrue(sdkmanager.toFile().setExecutable(true))
        val bin = Files.createDirectories(directory.resolve("bin"))
        listOf("mkdir", "cp").forEach { command ->
            Files.createSymbolicLink(bin.resolve(command), Path.of("/bin/$command"))
        }
        val runner = directory.resolve("runner")
        val githubEnv = directory.resolve("github-env")
        val invocation = directory.resolve("sdkmanager-args")
        val builder = ProcessBuilder("/bin/bash", "-c", install).redirectErrorStream(true)
        builder.environment().putAll(
            mapOf(
                "PATH" to bin.toString(),
                "ANDROID_HOME" to sdk.toString(),
                "RUNNER_TEMP" to runner.toString(),
                "GITHUB_ENV" to githubEnv.toString(),
                "SDKMANAGER_LOG" to invocation.toString(),
            ),
        )
        val process = builder.start()
        val completed = process.waitFor(10, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue(completed, "Android build tools installation did not finish")
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), output)
        val isolatedSdk = runner.resolve("compose-agent-sdk")
        assertEquals(
            listOf(
                "--sdk_root=$isolatedSdk",
                "--install",
                "platforms;android-35",
                "build-tools;35.0.0",
                "ndk;27.1.12297006",
                "cmake;3.22.1",
            ),
            Files.readAllLines(invocation),
        )
        assertEquals("accepted-license", Files.readString(isolatedSdk.resolve("licenses/android-sdk-license")))
        assertEquals(listOf("ANDROID_HOME=$isolatedSdk", "ANDROID_SDK_ROOT=$isolatedSdk"), Files.readAllLines(githubEnv))
    }

    @Test
    fun `trace processor installer downloads and verifies the pinned native binary`() {
        val installer = Files.readString(Path.of("../../scripts/install-trace-processor.sh"))
        val manifest = Files.readString(Path.of("../platform-perfetto/trace-processor-manifest.json"))

        assertTrue(installer.contains("version=\"${TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION}\""))
        assertTrue(installer.contains("manifest[\"version\"] != \"${TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION}\""))
        assertTrue(installer.contains("entry[\"url\"]"))
        assertTrue(installer.contains("curl --fail --location --retry 3"))
        assertTrue(installer.contains("actual_checksum"))
        assertTrue(installer.contains("--version"))
        assertTrue(!installer.contains("get.perfetto.dev"))
        assertTrue(manifest.contains("perfetto-luci-artifacts/v59.1"))
    }

    @Test
    fun `Perfetto UI download uses the processor pin and builds cannot reuse an old bundle`() {
        val script = Files.readString(Path.of("../../scripts/build-perfetto-ui.sh"))

        assertTrue(script.contains("perfetto_version=\"${TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION}\""))
        assertTrue(script.contains("releases/download/${'$'}perfetto_version/perfetto-ui.zip"))
        assertTrue(!script.contains("if [[ -f \"${'$'}perfetto_path/out/ui/dist/index.html\" ]]"))
        assertTrue(script.contains("ui/build --out out --no-wasm --no-depscheck"))
        assertTrue(script.contains("ui/build --out out)"))
    }

    private fun workflowJob(workflow: String, name: String): String =
        requireNotNull(
            Regex("(?ms)^  ${Regex.escape(name)}:\\n(.*?)(?=^  [a-z][a-z0-9-]*:\\n|\\z)")
                .find(workflow),
        ) { "Missing release job: $name" }.groupValues[1]
}
