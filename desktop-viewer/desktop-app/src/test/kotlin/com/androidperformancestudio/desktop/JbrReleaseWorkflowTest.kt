package com.androidperformancestudio.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JbrReleaseWorkflowTest {
    private val manifestPath = Path.of("../jbr-runtime-manifest.json")
    private val installerPath = Path.of("../../scripts/install-jbr.ps1").toAbsolutePath().normalize()
    private val manifest get() = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
    private val artifact get() =
        manifest
            .getValue("artifacts")
            .jsonObject
            .getValue("windows-x64")
            .jsonObject

    @TempDir
    lateinit var directory: Path

    @Test
    fun `manifest pins the official no JCEF Windows SDK and records checksum provenance`() {
        assertEquals("1", manifest.text("schemaVersion"))
        assertEquals("jbr-release-21.0.11b1163.125", manifest.text("releaseTag"))
        assertEquals("a166c74c11a09930f6b8ddf0e75a867c0b1a5cc6", manifest.text("sourceCommit"))
        assertEquals("jbrsdk", artifact.text("variant"))
        assertEquals("jbrsdk-21.0.11-windows-x64-b1163.125", artifact.text("archiveRoot"))
        assertEquals(
            "https://cache-redirector.jetbrains.com/intellij-jbr/${artifact.text("archiveRoot")}.zip",
            artifact.text("url"),
        )
        assertTrue(artifact.text("sha256").matches(Regex("[a-f0-9]{64}")))
        val checksum = artifact.getValue("upstreamChecksum").jsonObject
        assertEquals("SHA512", checksum.text("algorithm"))
        assertEquals("${artifact.text("url")}.checksum", checksum.text("url"))
        assertTrue(checksum.text("value").matches(Regex("[a-f0-9]{128}")))
        assertTrue(artifact.text("sha256Provenance").contains("upstream does not publish SHA-256"))
        val release = artifact.getValue("release").jsonObject
        assertEquals("JBRSDK-21.0.11+1-1163.125-nomod", release.text("IMPLEMENTOR_VERSION"))
        assertEquals("JetBrains s.r.o.", release.text("IMPLEMENTOR"))
        assertEquals("21.0.11+1-b1163.125", release.text("JAVA_RUNTIME_VERSION"))
        assertEquals("Windows", release.text("OS_NAME"))
        assertEquals("x86_64", release.text("OS_ARCH"))
    }

    @Test
    fun `release shrinking retains JBR service names and annotation metadata`() {
        val rules = Files.readString(Path.of("proguard-rules.pro"))
        assertTrue(rules.contains("-keep class com.jetbrains.** { *; }"))
        assertTrue(rules.contains("-keepattributes *Annotation*,InnerClasses,EnclosingMethod"))
    }

    @Test
    fun `only Windows packaging installs JBR and tests it before both installers`() {
        val workflow = Files.readString(Path.of("../../.github/workflows/release.yml"))
        val windows = workflowJob(workflow, "package-windows")
        val setup = windows.indexOf("distribution: temurin")
        val install = windows.indexOf("run: ./scripts/install-jbr.ps1")
        val tests = windows.indexOf("- name: Test Windows title bar on the packaged JBRSDK")
        val packaging = windows.indexOf("- name: Build MSI and EXE")
        assertTrue(setup >= 0 && install > setup && tests > install && packaging > tests)
        val testStep = windows.substring(tests, packaging)
        assertTrue(testStep.contains("APS_REQUIRE_JBR_CHROME: \"true\""))
        assertTrue(testStep.contains("${'$'}env:JAVA_HOME = ${'$'}env:APS_JBR_HOME"))
        assertTrue(testStep.contains("-Ptarget.javaHome=${'$'}env:APS_JBR_HOME"))
        assertTrue(testStep.contains("-Porg.gradle.java.installations.paths=${'$'}env:APS_JBR_HOME"))
        assertTrue(testStep.contains("-Porg.gradle.java.installations.auto-detect=false"))
        assertTrue(testStep.contains("-Porg.gradle.java.installations.auto-download=false"))
        listOf("JbrReleaseWorkflowTest", "WindowsWindowChromeTest", "WindowsWindowChromeIntegrationTest").forEach {
            assertTrue(testStep.contains("--tests com.androidperformancestudio.desktop.$it"))
        }
        assertTrue(testStep.contains("if (${'$'}LASTEXITCODE -ne 0)"))
        val packageStep = windows.substring(packaging).substringBefore("      - name: Prepare Windows assets")
        listOf("packageMsi", "packageExe").forEach { task ->
            val invocation = packageStep.lineSequence().single { it.contains(":desktop-app:$task") }
            assertTrue(invocation.contains("\"-Ptarget.javaHome=${'$'}env:APS_JBR_HOME\""))
        }
        listOf("test", "build-compose-agent", "package-macos", "package-linux").forEach { job ->
            val other = workflowJob(workflow, job)
            assertFalse(other.contains("install-jbr.ps1"), job)
            assertFalse(other.contains("APS_JBR_HOME"), job)
        }
    }

    @Test
    fun `workflow jobs parse identically for LF and CRLF checkouts`() {
        val lf = Files.readString(Path.of("../../.github/workflows/release.yml")).replace("\r\n", "\n")
        val crlf = lf.replace("\n", "\r\n")
        val jobs =
            listOf(
                "test",
                "build-compose-agent",
                "prepare-release",
                "package-windows",
                "package-macos",
                "package-linux",
                "publish",
            )
        jobs.forEach { job ->
            assertEquals(workflowJob(lf, job), workflowJob(crlf, job).replace("\r\n", "\n"), job)
        }
    }

    @Test
    fun `checksum failure never reuses an existing JDK or exports its home`() {
        val archive = Files.writeString(directory.resolve("bad.zip"), "not the pinned archive")
        val destination = Files.createDirectory(directory.resolve("existing-jdk"))
        val sentinel = Files.writeString(destination.resolve("sentinel"), "preserve existing files")
        val result = runInstaller(archive, manifest, destination)

        assertNotEquals(0, result.first, result.second)
        assertTrue(result.second.contains("SHA256 checksum mismatch"), result.second)
        assertEquals("preserve existing files", Files.readString(sentinel))
        assertNoExport()
    }

    @Test
    fun `installer also rejects a mismatched upstream SHA512 before extraction`() {
        val archive = Files.writeString(directory.resolve("bad.zip"), "not a zip")
        val fixtureManifest = withChecksums(archive)
        val entry =
            fixtureManifest
                .getValue("artifacts")
                .jsonObject
                .getValue("windows-x64")
                .jsonObject
        val invalidChecksum = JsonObject(entry.getValue("upstreamChecksum").jsonObject + ("value" to JsonPrimitive("0".repeat(128))))
        val result = runInstaller(archive, withArtifact(entry + ("upstreamChecksum" to invalidChecksum)), directory.resolve("sdk"))

        assertNotEquals(0, result.first, result.second)
        assertTrue(result.second.contains("upstream SHA512 checksum mismatch"), result.second)
        assertFalse(Files.exists(directory.resolve("sdk")))
        assertNoExport()
    }

    @Test
    fun `correct checksums cannot hide the wrong vendor or runtime build`() {
        listOf("IMPLEMENTOR", "JAVA_RUNTIME_VERSION", "IMPLEMENTOR_VERSION", "OS_ARCH").forEach { key ->
            val archive = sdkArchive("$key.zip", releaseOverride = key to "unexpected")
            val result = runInstaller(archive, withChecksums(archive), directory.resolve("sdk-$key"))

            assertNotEquals(0, result.first, result.second)
            assertTrue(result.second.contains("release identity mismatch for $key"), result.second)
            assertFalse(Files.exists(directory.resolve("sdk-$key")))
            assertNoExport()
        }
    }

    @Test
    fun `installer rejects a runtime without SDK packaging tools`() {
        val archive = sdkArchive("runtime.zip", omitted = "bin/jpackage.exe")
        val result = runInstaller(archive, withChecksums(archive), directory.resolve("sdk"))

        assertNotEquals(0, result.first, result.second)
        assertTrue(result.second.contains("missing bin/jpackage.exe"), result.second)
        assertFalse(Files.exists(directory.resolve("sdk")))
        assertNoExport()
    }

    @Test
    fun `Windows release test JVM is the pinned SDK and exposes custom title bars`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val installedHome = System.getenv("APS_JBR_HOME")
        assumeTrue(!installedHome.isNullOrBlank(), "Only the Windows JBR release gate requires this runtime")
        assertEquals(Path.of(installedHome).toRealPath(), Path.of(System.getProperty("java.home")).toRealPath())
        val release = artifact.getValue("release").jsonObject
        assertEquals(release.text("IMPLEMENTOR"), System.getProperty("java.vendor"))
        assertEquals(release.text("JAVA_RUNTIME_VERSION"), System.getProperty("java.runtime.version"))
        val jbr = Class.forName("com.jetbrains.JBR")
        assertEquals(true, jbr.getMethod("isWindowDecorationsSupported").invoke(null))
        val decorations = jbr.getMethod("getWindowDecorations").invoke(null)
        assertNotNull(decorations)
        val api = Class.forName("com.jetbrains.WindowDecorations")
        val titleBar = api.getMethod("createCustomTitleBar").invoke(decorations)
        assertNotNull(titleBar)
        val titleBarApi = Class.forName("com.jetbrains.WindowDecorations${'$'}CustomTitleBar")
        titleBarApi.getMethod("setHeight", Float::class.javaPrimitiveType).invoke(titleBar, 36f)
        assertEquals(36f, titleBarApi.getMethod("getHeight").invoke(titleBar))
        assertTrue((titleBarApi.getMethod("getLeftInset").invoke(titleBar) as Float) >= 0f)
        assertTrue((titleBarApi.getMethod("getRightInset").invoke(titleBar) as Float) >= 0f)
        titleBarApi.getMethod("forceHitTest", Boolean::class.javaPrimitiveType).invoke(titleBar, false)
    }

    @Test
    fun `jlinked Windows JBR runtime retains custom title bar support`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val installedHome = System.getenv("APS_JBR_HOME")
        assumeTrue(!installedHome.isNullOrBlank(), "Only the Windows JBR release gate requires this runtime")
        val sdk = Path.of(installedHome).toRealPath()
        assertEquals(sdk, Path.of(System.getProperty("java.home")).toRealPath())
        val apiJar =
            Path.of(
                Class
                    .forName("com.jetbrains.JBR")
                    .protectionDomain.codeSource.location
                    .toURI(),
            )
        assertTrue(Files.isRegularFile(apiJar), "The probe must use the resolved jbr-api JAR: $apiJar")
        val runtime = directory.resolve("linked-jbr")
        runSdkCommand(
            listOf(
                sdk.resolve("bin/jlink.exe").toString(),
                "--module-path",
                sdk.resolve("jmods").toString(),
                "--add-modules",
                "java.desktop",
                "--no-header-files",
                "--no-man-pages",
                "--output",
                runtime.toString(),
            ),
            "jlink",
            timeoutSeconds = 90,
        )
        val source =
            Files.writeString(
                directory.resolve("JbrTitleBarProbe.java"),
                """
                import com.jetbrains.JBR;
                import com.jetbrains.WindowDecorations;
                import java.nio.file.Path;

                public final class JbrTitleBarProbe {
                    public static void main(String[] args) throws Exception {
                        if (!System.getProperty("java.runtime.version").equals(args[0]) ||
                            !Path.of(System.getProperty("java.home")).toRealPath().equals(Path.of(args[1]).toRealPath())) {
                            throw new AssertionError("Probe is not running on the pinned linked runtime");
                        }
                        if (!JBR.isAvailable() || !JBR.isWindowDecorationsSupported()) {
                            throw new AssertionError("Linked runtime lost JBR WindowDecorations support");
                        }
                        WindowDecorations.CustomTitleBar titleBar = JBR.getWindowDecorations().createCustomTitleBar();
                        titleBar.setHeight(36f);
                        if (titleBar.getHeight() != 36f || titleBar.getLeftInset() < 0f || titleBar.getRightInset() < 0f) {
                            throw new AssertionError("Invalid linked runtime custom title bar");
                        }
                        titleBar.forceHitTest(false);
                        System.out.println("JBR_CUSTOM_TITLE_BAR_OK");
                    }
                }
                """.trimIndent(),
            )
        runSdkCommand(
            listOf(
                sdk.resolve("bin/javac.exe").toString(),
                "-classpath",
                apiJar.toString(),
                "-d",
                directory.toString(),
                source.toString(),
            ),
            "javac",
        )
        val output =
            runSdkCommand(
                listOf(
                    runtime.resolve("bin/java.exe").toString(),
                    "-classpath",
                    "$apiJar${File.pathSeparator}$directory",
                    "JbrTitleBarProbe",
                    artifact.getValue("release").jsonObject.text("JAVA_RUNTIME_VERSION"),
                    runtime.toString(),
                ),
                "linked-runtime-probe",
            )
        assertTrue(output.contains("JBR_CUSTOM_TITLE_BAR_OK"), output)
    }

    private fun runSdkCommand(
        command: List<String>,
        name: String,
        timeoutSeconds: Long = 30,
    ): String {
        val log = directory.resolve("$name.log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val output = Files.readString(log)
        assertTrue(completed, "$name timed out after ${timeoutSeconds}s; log: $log\n$output")
        assertEquals(0, process.exitValue(), "$name failed; log: $log\n$output")
        return output
    }

    private fun sdkArchive(
        name: String,
        releaseOverride: Pair<String, String>? = null,
        omitted: String? = null,
    ): Path {
        val release =
            artifact
                .getValue("release")
                .jsonObject
                .mapValues { it.value.jsonPrimitive.content }
                .toMutableMap()
        release["MODULES"] = "java.base java.desktop jdk.compiler jdk.jlink jdk.jpackage"
        releaseOverride?.let { release[it.first] = it.second }
        val releaseText = release.entries.joinToString("\n") { "${it.key}=\"${it.value}\"" }
        val path = directory.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            listOf("release", "bin/java.exe", "bin/javac.exe", "bin/jlink.exe", "bin/jpackage.exe", "jmods/java.desktop.jmod")
                .filter { it != omitted }
                .forEach { relative ->
                    zip.putNextEntry(ZipEntry("${artifact.text("archiveRoot")}/$relative"))
                    val content = if (relative == "release") releaseText else "fixture"
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
        }
        return path
    }

    private fun withChecksums(archive: Path): JsonObject {
        val bytes = Files.readAllBytes(archive)

        fun checksum(algorithm: String) = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes))
        val upstream = JsonObject(artifact.getValue("upstreamChecksum").jsonObject + ("value" to JsonPrimitive(checksum("SHA-512"))))
        return withArtifact(artifact + mapOf("sha256" to JsonPrimitive(checksum("SHA-256")), "upstreamChecksum" to upstream))
    }

    private fun withArtifact(entry: Map<String, JsonElement>): JsonObject =
        JsonObject(manifest + ("artifacts" to JsonObject(mapOf("windows-x64" to JsonObject(entry)))))

    private fun runInstaller(
        archive: Path,
        fixtureManifest: JsonObject,
        destination: Path,
    ): Pair<Int, String> {
        val fixture = Files.writeString(directory.resolve("manifest.json"), fixtureManifest.toString())
        Files.writeString(directory.resolve("github-env"), "existing-env\n")
        Files.writeString(directory.resolve("github-output"), "existing-output\n")
        val command =
            """
            function Invoke-WebRequest {
                param(${'$'}Uri, ${'$'}OutFile)
                Copy-Item -LiteralPath ${'$'}env:JBR_FIXTURE_ARCHIVE -Destination ${'$'}OutFile
            }
            & ${'$'}env:JBR_INSTALLER -ManifestPath ${'$'}env:JBR_FIXTURE_MANIFEST -Destination ${'$'}env:JBR_DESTINATION -OutputName home
            """.trimIndent()
        val log = directory.resolve("powershell.log")
        val builder =
            ProcessBuilder("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
        builder.environment().putAll(
            mapOf(
                "JBR_INSTALLER" to installerPath.toString(),
                "JBR_FIXTURE_ARCHIVE" to archive.toString(),
                "JBR_FIXTURE_MANIFEST" to fixture.toString(),
                "JBR_DESTINATION" to destination.toString(),
                "GITHUB_ENV" to directory.resolve("github-env").toString(),
                "GITHUB_OUTPUT" to directory.resolve("github-output").toString(),
                "JAVA_HOME" to destination.toString(),
            ),
        )
        val process =
            try {
                builder.start()
            } catch (exception: IOException) {
                assumeTrue(false, "PowerShell 7 is required for installer behavior tests: ${exception.message}")
                throw exception
            }
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue(completed, "JBRSDK installer did not finish")
        return process.exitValue() to Files.readString(log)
    }

    private fun assertNoExport() {
        assertEquals("existing-env\n", Files.readString(directory.resolve("github-env")))
        assertEquals("existing-output\n", Files.readString(directory.resolve("github-output")))
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun workflowJob(
        workflow: String,
        name: String,
    ): String =
        requireNotNull(
            Regex("(?ms)^  ${Regex.escape(name)}:\\r?\\n(.*?)(?=^  [a-z][a-z0-9-]*:\\r?\\n|\\z)").find(workflow),
        ) { "Missing release job: $name" }.groupValues[1]
}
