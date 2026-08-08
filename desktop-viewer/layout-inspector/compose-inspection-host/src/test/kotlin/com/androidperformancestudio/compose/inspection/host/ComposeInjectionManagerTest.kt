package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.adb.ProcessResult
import com.androidperformancestudio.adb.ProcessRunner
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ComposeInjectionManagerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `preflight detects exact APK version without injecting and reports artifact download`() {
        val bundle = createBundle(tempDir.resolve("bundle"))
        val commands = mutableListOf<List<String>>()
        val runner = ProcessRunner { arguments ->
            commands += arguments
            when {
                arguments.takeLast(2) == listOf("getprop", "ro.build.version.sdk") -> ProcessResult(0, "34\n", "")
                arguments.takeLast(2) == listOf("getprop", "ro.product.cpu.abi") -> ProcessResult(0, "arm64-v8a\n", "")
                arguments.contains("pwd") -> ProcessResult(0, "/data/user/0/dev.sample\n", "")
                arguments.contains("pidof") -> ProcessResult(0, "1234\n", "")
                arguments.takeLast(2) == listOf("cat", "/proc/net/unix") -> ProcessResult(0, "Num RefCount Protocol Flags Type St Inode Path\n", "")
                arguments.contains("pm") -> ProcessResult(0, "package:/data/app/~~abc==/dev.sample/base.apk\n", "")
                arguments.contains("pull") -> {
                    Files.write(Path.of(arguments.last()), apkWithComposeVersion("1.11.4"))
                    ProcessResult(0, "1 file pulled", "")
                }
                else -> ProcessResult(1, "", "unexpected command")
            }
        }
        val resolver = ComposeInspectorArtifactResolver(
            cacheDir = tempDir.resolve("cache"),
            gradleUserHome = tempDir.resolve("gradle"),
            mavenLocal = tempDir.resolve("m2"),
            googleRepository = URI("https://example.test/maven/"),
        )

        val prepared = ComposeInjectionManager(runner, resolver).preflight("device", "dev.sample", bundle)

        assertEquals("1.11.4", prepared.preflight.composeVersion)
        assertEquals("https://example.test/maven/", prepared.preflight.inspectorSource)
        assertTrue(prepared.preflight.inspectorDownloadRequired)
        assertTrue(commands.none { it.contains("attach-agent") })
    }

    private fun createBundle(root: Path): Path {
        val files = mapOf(
            "agent/arm64-v8a/lib_ui_inspector_agent.so" to "agent",
            "lib_ui_inspector_service.jar" to "service",
            "lib_ui_inspector_payload.jar" to "payload",
            "view-inspector.jar" to "view",
        )
        files.forEach { (relative, content) ->
            Files.createDirectories(root.resolve(relative).parent)
            Files.writeString(root.resolve(relative), content)
        }
        Properties().apply {
            setProperty("agent.arm64-v8a.sha256", root.resolve("agent/arm64-v8a/lib_ui_inspector_agent.so").sha256())
            setProperty("service.sha256", root.resolve("lib_ui_inspector_service.jar").sha256())
            setProperty("payload.sha256", root.resolve("lib_ui_inspector_payload.jar").sha256())
            setProperty("view.sha256", root.resolve("view-inspector.jar").sha256())
            Files.newOutputStream(root.resolve("manifest.properties")).use { store(it, null) }
        }
        return root
    }

    private fun apkWithComposeVersion(version: String): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/androidx.compose.ui_ui.version"))
            zip.write(version.toByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }
}
