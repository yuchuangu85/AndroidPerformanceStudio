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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `attach stores native agent at app data root and cleans only session paths`() {
        val bundle = createBundle(tempDir.resolve("bundle"))
        val commands = mutableListOf<List<String>>()
        var socketVisible = false
        val runner = ProcessRunner { arguments ->
            commands += arguments
            when {
                arguments.takeLast(2) == listOf("getprop", "ro.build.version.sdk") -> ProcessResult(0, "35\n", "")
                arguments.takeLast(2) == listOf("getprop", "ro.product.cpu.abi") -> ProcessResult(0, "arm64-v8a\n", "")
                arguments.contains("pwd") -> ProcessResult(0, "/data/user/0/dev.sample\n", "")
                arguments.contains("pidof") -> ProcessResult(0, "1234\n", "")
                arguments.takeLast(2) == listOf("cat", "/proc/net/unix") -> ProcessResult(
                    0,
                    if (socketVisible) "@ui_inspector_1234\n" else "Num RefCount Protocol Flags Type St Inode Path\n",
                    "",
                )
                arguments.contains("pm") -> ProcessResult(0, "package:/data/app/~~abc==/dev.sample/base.apk\n", "")
                arguments.contains("pull") -> {
                    Files.write(Path.of(arguments.last()), apkWithComposeVersion("1.11.4"))
                    ProcessResult(0, "1 file pulled", "")
                }
                arguments.contains("attach-agent") -> {
                    socketVisible = true
                    ProcessResult(0, "", "")
                }
                else -> ProcessResult(0, "", "")
            }
        }
        val manager = ComposeInjectionManager(
            processRunner = runner,
            portAllocator = { 43210 },
            sleeper = {},
        )
        val prepared = manager.preflight("device", "dev.sample", bundle)
        val token = InspectorSessionToken.parse("a".repeat(64))

        val session = manager.attach(
            prepared,
            ComposeInspectionAuthorization.authorize(prepared.preflight),
            token,
        )

        val agentPath = "/data/user/0/dev.sample/lib_ui_inspector_agent.so"
        val servicePath = "/data/user/0/dev.sample/lib_ui_inspector_service.jar"
        val payloadPath = "/data/user/0/dev.sample/lib_ui_inspector_payload.jar"
        val privateDirectory = "/data/user/0/dev.sample/code_cache/aps-ui-inspector-${token.value.take(32)}"
        val attachArgument = commands.single { it.contains("attach-agent") }.last().removeSurrounding("'")
        assertEquals(agentPath, attachArgument.substringBefore('='))
        assertFalse(attachArgument.substringBefore('=').contains("code_cache"))
        assertTrue(attachArgument.contains(servicePath))
        assertTrue(attachArgument.contains(payloadPath))
        assertTrue(commands.any { it.takeLast(3) == listOf("cp", "/data/local/tmp/aps-ui-inspector-${token.value.take(32)}-agent.so", "lib_ui_inspector_agent.so") })

        session.close()

        val removedPaths = commands
            .filter { it.contains("run-as") && it.contains("rm") && it.contains("-rf") }
            .map(List<String>::last)
        assertEquals(listOf(agentPath, servicePath, payloadPath, privateDirectory), removedPaths.takeLast(4))
    }

    @Test
    fun `attach reports agent bootstrap errors before socket timeout`() {
        val bundle = createBundle(tempDir.resolve("bundle-with-logcat-error"))
        val commands = mutableListOf<List<String>>()
        val runner = ProcessRunner { arguments ->
            commands += arguments
            when {
                arguments.takeLast(2) == listOf("getprop", "ro.build.version.sdk") -> ProcessResult(0, "35\n", "")
                arguments.takeLast(2) == listOf("getprop", "ro.product.cpu.abi") -> ProcessResult(0, "arm64-v8a\n", "")
                arguments.contains("pwd") -> ProcessResult(0, "/data/user/0/dev.sample\n", "")
                arguments.contains("pidof") -> ProcessResult(0, "1234\n", "")
                arguments.takeLast(2) == listOf("cat", "/proc/net/unix") ->
                    ProcessResult(0, "Num RefCount Protocol Flags Type St Inode Path\n", "")
                arguments.contains("pm") -> ProcessResult(0, "package:/data/app/~~abc==/dev.sample/base.apk\n", "")
                arguments.contains("pull") -> {
                    Files.write(Path.of(arguments.last()), apkWithComposeVersion("1.11.4"))
                    ProcessResult(0, "1 file pulled", "")
                }
                arguments.contains("logcat") -> ProcessResult(
                    0,
                    "10-29 19:00:01.100 1234 1234 E studio.ui-inspector.native: Failed to find InspectorService initialize method\n" +
                        "10-29 19:00:01.100 1234 1234 E nativeloader: dlopen failed: library \"lib_ui_inspector_agent.so\" not found\n",
                    "",
                )
                else -> ProcessResult(0, "", "")
            }
        }
        val manager = ComposeInjectionManager(processRunner = runner, sleeper = {})
        val prepared = manager.preflight("device", "dev.sample", bundle)

        val error = assertThrows(IllegalStateException::class.java) {
            manager.attach(
                prepared,
                ComposeInspectionAuthorization.authorize(prepared.preflight),
                InspectorSessionToken.parse("b".repeat(64)),
            )
        }

        assertTrue(error.message.orEmpty().contains("Failed to attach Compose inspector agent"))
        assertTrue(error.message.orEmpty().contains("Failed to find InspectorService initialize method"))
        assertTrue(error.message.orEmpty().contains("nativeloader: dlopen failed"))
        assertTrue(commands.any { it.contains("logcat") })
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
