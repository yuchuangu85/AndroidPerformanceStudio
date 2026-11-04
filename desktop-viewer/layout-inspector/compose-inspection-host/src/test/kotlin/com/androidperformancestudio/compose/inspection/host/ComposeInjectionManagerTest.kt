package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.adb.ProcessResult
import com.androidperformancestudio.adb.ProcessRunner
import com.androidperformancestudio.compose.inspection.ComposeInspectorArtifact
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
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
    fun `attach reports agent bootstrap errors after bounded socket wait`() {
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
                arguments.contains("date") -> ProcessResult(0, "10-29 19:00:00.000\n", "")
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

    @Test
    fun `framework classloader failure does not abort a successful fallback attach`() {
        val fixture = delayedAttachFixture(
            logcat = "10-30 10:00:01.100 1234 1234 E ActivityThread: Attaching agent with " +
                "dalvik.system.PathClassLoader[DexPathList[[zip file \"/data/app/~~abc==/dev.sample/base.apk\"]]] " +
                "failed with message: No native library found; attempting with null classloader",
            socketAfterLogcatReads = 1,
        )

        fixture.attach().use { assertEquals(1234, it.pid) }

        assertEquals(1, fixture.logcatReads)
        assertTrue(fixture.commands.any { it.contains("forward") })
    }

    @Test
    fun `ART warning explains failed attach instead of only dumping the classloader`() {
        val fixture = delayedAttachFixture(
            logcat = "10-30 10:00:01.100 1234 1234 E ActivityThread: Attaching agent with " +
                "dalvik.system.PathClassLoader[DexPathList[[zip file \"/data/app/~~abc==/dev.sample/base.apk\"]]] failed\n" +
                "10-30 10:00:01.101 1234 1234 W dev.sample: Agent attach failed " +
                "(error opening library): dlopen failed: wrong ELF class: ELFCLASS64\n",
        )

        val error = assertThrows(IllegalStateException::class.java) { fixture.attach() }

        assertTrue(error.message.orEmpty().contains("Agent attach failed"))
        assertTrue(error.message.orEmpty().contains("wrong ELF class: ELFCLASS64"))
        assertTrue(fixture.commands.filter { it.contains("logcat") }.all { it.last() == "*:W" })
        assertFalse(fixture.commands.any { it.contains("forward") })
        assertTrue(fixture.commands.any { it.contains("run-as") && it.contains("rm") })
    }

    @Test
    fun `logcat failure does not prevent waiting for a successful agent socket`() {
        val fixture = delayedAttachFixture(logcat = "", socketAfterLogcatReads = 1, logcatExitCode = 1)

        fixture.attach().close()

        assertEquals(1, fixture.logcatReads)
        assertTrue(fixture.commands.any { it.contains("forward") })
    }

    @Test
    fun `unrelated ActivityThread errors do not abort attachment`() {
        val fixture = delayedAttachFixture(
            logcat = "10-30 10:00:01.100 1234 1234 E ActivityThread: Failed to find provider info for dev.sample.provider\n",
            socketAfterLogcatReads = 1,
        )

        fixture.attach().close()

        assertTrue(fixture.commands.any { it.contains("forward") })
    }

    @Test
    fun `unscoped old bootstrap errors do not abort a new attach`() {
        val fixture = delayedAttachFixture(
            logcat = "10-29 19:00:01.100 1234 1234 E studio.ui-inspector.native: Failed to find InspectorService\n",
            socketAfterLogcatReads = 2,
            deviceTime = "",
        )

        fixture.attach().close()

        assertEquals(2, fixture.logcatReads)
        assertTrue(fixture.commands.any { it.contains("forward") })
    }

    @Test
    fun `same second old bootstrap errors do not abort a delayed new socket`() {
        val fixture = delayedAttachFixture(
            logcat = "10-30 10:00:00.100 1234 1234 E studio.ui-inspector.native: Failed to find InspectorService\n",
            socketAfterLogcatReads = 2,
            deviceTime = "10-30 10:00:00.000",
        )

        fixture.attach().close()

        assertEquals(2, fixture.logcatReads)
        assertTrue(fixture.commands.any { it.contains("forward") })
    }

    @Test
    fun `failed attach command redacts options and never retains a secret bearing cause`() {
        val token = InspectorSessionToken.parse("c".repeat(64))
        val fixture = delayedAttachFixture(logcat = "", token = token) { arguments ->
            if (arguments.contains("attach-agent")) {
                ProcessResult(1, "", "permission denied: ${arguments.last()}")
            } else {
                null
            }
        }

        val error = assertThrows(IllegalStateException::class.java) { fixture.attach() }

        assertTrue(error.message.orEmpty().contains("permission denied"))
        assertRedacted(error, token)
        assertFalse(fixture.commands.any { it.contains("forward") })
    }

    @Test
    fun `staging runner throwable redacts token path prefix and drops its unsafe cause`() {
        val token = InspectorSessionToken.parse("d".repeat(64))
        val fixture = delayedAttachFixture(logcat = "", token = token) { arguments ->
            if (arguments.contains("push")) {
                throw IllegalStateException("push failed: ${arguments.last()}", RuntimeException(token.value))
            }
            null
        }

        val error = assertThrows(IllegalStateException::class.java) { fixture.attach() }

        assertTrue(error.message.orEmpty().contains("push failed"))
        assertRedacted(error, token)
        assertEquals(4, fixture.commands.count { it.contains("run-as") && it.contains("rm") })
    }

    @Test
    fun `failed attach attempts every cleanup path without replacing its sanitized primary error`() {
        val token = InspectorSessionToken.parse("e".repeat(64))
        var attachFailed = false
        val fixture = delayedAttachFixture(logcat = "", token = token) { arguments ->
            if (arguments.contains("attach-agent")) {
                attachFailed = true
                throw IllegalStateException("bootstrap failed: ${token.value}")
            }
            if (attachFailed && arguments.contains("rm")) {
                throw IllegalStateException("cleanup failed: ${arguments.last()}")
            }
            null
        }

        val error = assertThrows(IllegalStateException::class.java) { fixture.attach() }

        assertTrue(error.message.orEmpty().contains("bootstrap failed"))
        assertRedacted(error, token)
        assertEquals(5, error.suppressed.size)
        assertEquals(4, fixture.commands.filter { it.contains("run-as") && it.contains("rm") }.takeLast(4).size)
        assertTrue(fixture.commands.last().last().contains("/code_cache/aps-ui-inspector-"))
    }

    @Test
    fun `session close attempts all cleanup after a runner failure and is idempotent`() {
        val token = InspectorSessionToken.parse("f".repeat(64))
        val fixture = delayedAttachFixture(logcat = "", token = token, socketAfterLogcatReads = 0) { arguments ->
            if (arguments.contains("--remove")) {
                throw IllegalStateException("forward cleanup failed: ${token.value}")
            }
            null
        }
        val session = fixture.attach()
        val commandsBeforeClose = fixture.commands.size

        val error = assertThrows(IllegalStateException::class.java) { session.close() }

        assertTrue(error.message.orEmpty().contains("forward cleanup failed"))
        assertRedacted(error, token)
        assertEquals(6, fixture.commands.size - commandsBeforeClose)
        session.close()
        assertEquals(6, fixture.commands.size - commandsBeforeClose)
    }

    @Test
    fun `cleanup cancellation and fatal errors propagate after all owned paths are attempted`() {
        for (failure in listOf(CancellationException("cancelled"), InterruptedException("interrupted"), OutOfMemoryError("fatal"))) {
            val fixture = delayedAttachFixture(logcat = "", socketAfterLogcatReads = 0) { arguments ->
                if (arguments.contains("--remove")) throw failure
                null
            }
            val session = fixture.attach()
            val commandsBeforeClose = fixture.commands.size

            assertSame(failure, assertThrows(failure.javaClass) { session.close() })
            assertEquals(6, fixture.commands.size - commandsBeforeClose)
        }
    }

    @Test
    fun `close ignores nonzero cleanup results while attempting all owned paths`() {
        val token = InspectorSessionToken.parse("a".repeat(64))
        var closing = false
        val fixture = delayedAttachFixture(logcat = "", token = token, socketAfterLogcatReads = 0) { arguments ->
            if (closing) ProcessResult(1, "", "cleanup rejected: ${arguments.last()}") else null
        }
        val session = fixture.attach()
        val commandsBeforeClose = fixture.commands.size
        closing = true

        session.close()

        assertEquals(6, fixture.commands.size - commandsBeforeClose)
    }

    @Test
    fun `deploy copy failure redacts session prefix and is preserved when staged cleanup throws`() {
        for (cleanupThrows in listOf(false, true)) {
            val token = InspectorSessionToken.parse("b".repeat(64))
            var deploying = false
            val fixture = delayedAttachFixture(logcat = "", token = token, socketAfterLogcatReads = 0) { arguments ->
                when {
                    deploying && arguments.contains("cp") -> ProcessResult(1, "", "copy refused: ${arguments.last()}")
                    deploying && cleanupThrows && arguments.contains("rm") -> throw IllegalStateException(
                        "staged cleanup failed: ${token.value}",
                        RuntimeException(token.value),
                    )
                    else -> null
                }
            }
            val session = fixture.attach()
            val resolved = resolvedInspector()
            deploying = true

            val error = assertThrows(IllegalStateException::class.java) { session.deployComposeInspector(resolved) }

            assertTrue(error.message.orEmpty().contains("copy refused"))
            assertRedacted(error, token)
            assertEquals(if (cleanupThrows) 1 else 0, error.suppressed.size)
            assertTrue(fixture.commands.last().contains("rm"))
            assertTrue(fixture.commands.last().last().startsWith("/data/local/tmp/aps-compose-"))
            deploying = false
            session.close()
        }
    }

    @Test
    fun `successful deploy retains prior nonzero staged cleanup semantics`() {
        var deploying = false
        val fixture = delayedAttachFixture(logcat = "", socketAfterLogcatReads = 0) { arguments ->
            if (deploying && arguments.contains("rm")) ProcessResult(1, "", "device disconnected") else null
        }
        val session = fixture.attach()
        deploying = true

        val destination = session.deployComposeInspector(resolvedInspector())

        assertTrue(destination.endsWith(".jar"))
        assertTrue(fixture.commands.last().contains("rm"))
        deploying = false
        session.close()
    }

    @Test
    fun `failed forwarding cleans a possibly allocated forward as well as session files`() {
        val fixture = delayedAttachFixture(logcat = "", socketAfterLogcatReads = 0) { arguments ->
            if (arguments.contains("forward") && !arguments.contains("--remove")) {
                ProcessResult(1, "", "forward failed")
            } else {
                null
            }
        }

        val error = assertThrows(IllegalStateException::class.java) { fixture.attach() }

        assertTrue(error.message.orEmpty().contains("forward failed"))
        assertTrue(fixture.commands.any { it.contains("forward") && it.contains("--remove") })
        assertTrue(fixture.commands.last().last().contains("/code_cache/aps-ui-inspector-"))
    }

    @Test
    fun `attach cancellation and fatal errors retain identity while owned cleanup is attempted`() {
        for (failure in listOf(CancellationException("cancelled"), InterruptedException("interrupted"), OutOfMemoryError("fatal"))) {
            val fixture = delayedAttachFixture(logcat = "") { arguments ->
                if (arguments.contains("attach-agent")) throw failure
                null
            }

            val error = assertThrows(failure.javaClass) { fixture.attach() }

            assertSame(failure, error)
            assertTrue(fixture.commands.last().last().contains("/code_cache/aps-ui-inspector-"))
        }
    }

    @Test
    fun `diagnostic reads do not swallow cancellation or fatal errors`() {
        for (command in listOf("date", "logcat")) {
            for (failure in listOf(CancellationException("cancelled"), InterruptedException("interrupted"), OutOfMemoryError("fatal"))) {
                val fixture = delayedAttachFixture(logcat = "") { arguments ->
                    if (arguments.contains(command)) throw failure
                    null
                }

                assertSame(failure, assertThrows(failure.javaClass) { fixture.attach() })
            }
        }
    }

    @Test
    fun `socket appearing while logcat is read wins over a bootstrap error`() {
        val fixture = delayedAttachFixture(
            logcat = "10-30 10:00:01.100 1234 1234 E studio.ui-inspector.native: Failed to find InspectorService\n",
            socketAfterLogcatReads = 1,
        )

        fixture.attach().close()

        assertTrue(fixture.commands.any { it.contains("forward") })
    }

    @Test
    fun `missing socket without agent diagnostics still fails as a timeout`() {
        val fixture = delayedAttachFixture(logcat = "")

        val error = assertThrows(IllegalStateException::class.java) { fixture.attach() }

        assertTrue(error.message.orEmpty().contains("Timed out waiting for Compose inspector agent socket"))
        assertFalse(fixture.commands.any { it.contains("forward") })
    }

    private fun delayedAttachFixture(
        logcat: String,
        socketAfterLogcatReads: Int = Int.MAX_VALUE,
        logcatExitCode: Int = 0,
        deviceTime: String = "10-30 10:00:00.000",
        token: InspectorSessionToken = InspectorSessionToken.generate(),
        commandOverride: (List<String>) -> ProcessResult? = { null },
    ): AttachFixture {
        val bundle = createBundle(tempDir.resolve("delayed-bundle"))
        val fixture = AttachFixture()
        val runner = ProcessRunner { arguments ->
            fixture.commands += arguments
            commandOverride(arguments) ?: when {
                arguments.takeLast(2) == listOf("getprop", "ro.build.version.sdk") -> ProcessResult(0, "35\n", "")
                arguments.takeLast(2) == listOf("getprop", "ro.product.cpu.abi") -> ProcessResult(0, "arm64-v8a\n", "")
                arguments.contains("pwd") -> ProcessResult(0, "/data/user/0/dev.sample\n", "")
                arguments.contains("pidof") -> ProcessResult(0, "1234\n", "")
                arguments.takeLast(2) == listOf("cat", "/proc/net/unix") -> ProcessResult(
                    0,
                    if (fixture.attachIssued && fixture.logcatReads >= socketAfterLogcatReads) "@ui_inspector_1234\n" else "Num RefCount Protocol Flags Type St Inode Path\n",
                    "",
                )
                arguments.contains("pm") -> ProcessResult(0, "package:/data/app/~~abc==/dev.sample/base.apk\n", "")
                arguments.contains("pull") -> {
                    Files.write(Path.of(arguments.last()), apkWithComposeVersion("1.11.4"))
                    ProcessResult(0, "1 file pulled", "")
                }
                arguments.contains("date") -> ProcessResult(0, "$deviceTime\n", "")
                arguments.contains("attach-agent") -> {
                    fixture.attachIssued = true
                    ProcessResult(0, "", "")
                }
                arguments.contains("logcat") -> {
                    fixture.logcatReads++
                    val captured = if (arguments.last() == "*:E") logcat.lineSequence().filter { it.contains(" E ") }.joinToString("\n") else logcat
                    ProcessResult(logcatExitCode, captured, "")
                }
                else -> ProcessResult(0, "", "")
            }
        }
        val manager = ComposeInjectionManager(runner, portAllocator = { 43210 }, sleeper = {})
        val prepared = manager.preflight("device", "dev.sample", bundle)
        fixture.attach = {
            manager.attach(prepared, ComposeInspectionAuthorization.authorize(prepared.preflight), token)
        }
        return fixture
    }

    private class AttachFixture {
        val commands = mutableListOf<List<String>>()
        var logcatReads = 0
        var attachIssued = false
        lateinit var attach: () -> InjectedComposeSession
    }

    private fun assertRedacted(error: Throwable, token: InspectorSessionToken) {
        val rendered = error.stackTraceToString()
        assertFalse(rendered.contains(token.value))
        assertFalse(rendered.contains(token.value.take(32)))
        assertTrue(rendered.contains("[redacted]"))
    }

    private fun resolvedInspector(): ResolvedComposeInspector {
        val jar = tempDir.resolve("compose-inspector.jar")
        Files.writeString(jar, "inspector fixture")
        return ResolvedComposeInspector(
            jar,
            ComposeInspectorArtifact(
                artifact = "ui-android",
                version = "1.11.4",
                sha256 = jar.sha256(),
                source = "test",
                certified = false,
            ),
        )
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
