package com.androidperformancestudio.aiplatform.script

import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.PlatformDirectories
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxedScriptRuntimeTest {
    @Test
    fun `bounded process harness terminates on output overflow timeout and cancellation`() =
        runBlocking {
            // These fixed local utilities test process supervision, not sandbox qualification or script execution.
            if (!Files.isExecutable(Path.of("/usr/bin/yes")) || !Files.isExecutable(Path.of("/bin/sleep"))) return@runBlocking
            val overflow = ProcessBuilder("/usr/bin/yes").start()
            val failure = assertFailsWith<ScriptException> { capture(overflow, ByteArray(0), 1024, 3000) }
            assertEquals("E_SCRIPT_OUTPUT_LIMIT", failure.code)
            assertFalse(overflow.isAlive)
            val slow = ProcessBuilder("/bin/sleep", "30").start()
            assertEquals("E_SCRIPT_TIMEOUT", assertFailsWith<ScriptException> { capture(slow, ByteArray(0), 1024, 25) }.code)
            assertFalse(slow.isAlive)
            val cancelled = ProcessBuilder("/bin/sleep", "30").start()
            val job = async { capture(cancelled, ByteArray(0), 1024, 30_000) }
            delay(50)
            job.cancelAndJoin()
            assertFalse(cancelled.isAlive)
        }

    @Test
    fun `stdout and stderr share one output budget`() =
        runBlocking {
            if (!Files.isExecutable(Path.of("/bin/sh"))) return@runBlocking
            val process = ProcessBuilder("/bin/sh", "-c", "printf 123456; printf 123456 >&2").start()
            val failure = assertFailsWith<ScriptException> { capture(process, ByteArray(0), 10, 3000) }
            assertEquals("E_SCRIPT_OUTPUT_LIMIT", failure.code)
            assertFalse(process.isAlive)
        }

    @Test
    fun `supervision terminates observed descendants without a process group kill`() =
        runBlocking {
            if (!Files.isExecutable(Path.of("/bin/sleep"))) return@runBlocking
            val process = ProcessBuilder("/bin/sh", "-c", "/bin/sleep 30 & wait").start()
            try {
                delay(100)
                val children = process.descendants().use { it.toList() }
                assertTrue(children.isNotEmpty())
                assertEquals("E_SCRIPT_TIMEOUT", assertFailsWith<ScriptException> { capture(process, ByteArray(0), 1024, 100) }.code)
                assertFalse(process.isAlive)
                repeat(20) { if (children.any { it.isAlive }) delay(25) }
                assertTrue(children.none { it.isAlive })
            } finally {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }

    @Test
    fun `Mac Windows and missing Linux tools fail closed without host process fallback`() =
        runBlocking {
            val temp = Files.createTempDirectory("aps-sandbox-test-").toRealPath()
            try {
                val registry = ExtensionRegistry(PlatformDirectories(temp.resolve("profile")))
                for ((os, code) in listOf(
                    "Mac OS X" to "E_MACOS_SANDBOX_UNQUALIFIED",
                    "Windows 11" to "E_WINDOWS_SANDBOX_UNQUALIFIED",
                    "Plan 9" to "E_PLATFORM_SANDBOX_UNAVAILABLE",
                    "Linux" to "E_SANDBOX_RUNTIME_UNAVAILABLE",
                )) {
                    val runtime =
                        SandboxedScriptRuntime(
                            registry,
                            temp.resolve("work"),
                            SandboxToolchain(os = os, bubblewrap = temp.resolve("missing"), prlimit = temp.resolve("missing")),
                        )
                    assertEquals(SandboxAvailability.Unavailable(code), runtime.discover(ScriptRuntime.PYTHON))
                    assertFalse(Files.exists(temp.resolve("work")))
                }
            } finally {
                temp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `Linux command uses namespaces read-only declared file no host credentials mounts and hard limits`() {
        val temp = Files.createTempDirectory("aps-sandbox-cmd-").toRealPath()
        try {
            val fakeBwrap = Files.writeString(temp.resolve("bwrap"), "fixture")
            val fakePrlimit = Files.writeString(temp.resolve("prlimit"), "fixture")
            fakeBwrap.toFile().setExecutable(true)
            fakePrlimit.toFile().setExecutable(true)
            val backend =
                SandboxBackend(
                    SandboxToolchain(os = "Linux", bubblewrap = fakeBwrap, prlimit = fakePrlimit, linuxLauncher = fakePrlimit),
                )
            val args =
                backend.command(
                    Path.of("/usr/bin/python3"),
                    ScriptRuntime.PYTHON,
                    temp.resolve("declared-script"),
                    ResourceBudget(),
                )
            assertTrue("--unshare-all" in args)
            assertTrue("--clearenv" in args)
            assertTrue("--cap-drop" in args)
            assertTrue("--die-with-parent" in args)
            assertTrue("--size" in args)
            assertTrue("--tmpfs" in args)
            assertFalse("--bind" in args)
            assertTrue(args.any { it.startsWith("--as=") })
            assertTrue(args.any { it.startsWith("--cpu=") })
            assertTrue(args.any { it.startsWith("--fsize=") })
            assertFalse(args.any { it.startsWith("--nproc=") })
            assertTrue("/input/launcher" in args)
            assertFalse("--share-net" in args)
            assertFalse("--proc" in args)
            assertTrue(args.windowed(2).any { it == listOf("--remount-ro", "/") })
            assertTrue(args.windowed(2).any { it == listOf("--remount-ro", "/dev") })
            val rootRemount = args.windowed(2).indexOf(listOf("--remount-ro", "/"))
            val setup = args.take(rootRemount).windowed(2)
            assertTrue(listOf("--dir", "/work") in setup)
            assertTrue(listOf("--dir", "/tmp") in setup)
            assertFalse("/Users" in args)
            val mounts = args.windowed(3).filter { it.first() in setOf("--ro-bind", "--ro-bind-try") }
            assertFalse("/home" in mounts.flatten())
            assertFalse("/usr" in mounts.flatten())
            assertFalse("/usr/local" in mounts.flatten())
            assertTrue(args.takeLast(3) == listOf("-I", "-B", "/input/skill"))
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Node alone receives five readonly distro builtin files without wider roots or larger budgets`() {
        val temp = Files.createTempDirectory("aps-node-system-files-").toRealPath()
        try {
            val tool = Files.writeString(temp.resolve("tool"), "fixture")
            tool.toFile().setExecutable(true)
            val backend = SandboxBackend(SandboxToolchain(os = "Linux", bubblewrap = tool, prlimit = tool, linuxLauncher = tool))
            val python = backend.command(Path.of("/usr/bin/python3"), ScriptRuntime.PYTHON, temp.resolve("skill"), ResourceBudget())
            val node = backend.command(Path.of("/usr/bin/node"), ScriptRuntime.NODE, temp.resolve("skill"), ResourceBudget())
            val expected =
                listOf(
                    "/usr/share/nodejs/cjs-module-lexer/lexer.js",
                    "/usr/share/nodejs/cjs-module-lexer/dist/lexer.js",
                    "/usr/share/nodejs/undici/undici-fetch.js",
                    "/usr/share/nodejs/acorn/dist/acorn.js",
                    "/usr/share/nodejs/acorn-walk/dist/walk.js",
                )
            val nodeMounts = node.windowed(3).filter { it.first() in setOf("--ro-bind", "--ro-bind-try") }
            assertEquals(expected, nodeMounts.map { it[1] }.filter { it.startsWith("/usr/share/") })
            expected.forEach { path -> assertTrue(listOf("--ro-bind-try", path, path) in nodeMounts) }
            assertFalse(python.any { it.startsWith("/usr/share/") })
            assertFalse(nodeMounts.any { it[1] in setOf("/usr", "/usr/share", "/usr/share/nodejs", "/home", "/Users") })
            val limits = listOf("--as=", "--cpu=", "--fsize=", "--nofile=", "--core=")
            assertEquals(python.filter { value -> limits.any(value::startsWith) }, node.filter { value -> limits.any(value::startsWith) })
            assertTrue("--max-old-space-size=64" in node)
            assertTrue(node.takeLast(4) == listOf("--jitless", "--no-addons", "--max-old-space-size=64", "/input/skill"))
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing native helper and subsecond CPU limits fail closed`() {
        val temp = Files.createTempDirectory("aps-sandbox-helper-").toRealPath()
        try {
            val tool = Files.writeString(temp.resolve("tool"), "fixture")
            tool.toFile().setExecutable(true)
            val tools = SandboxToolchain(os = "Linux", bubblewrap = tool, prlimit = tool, linuxLauncher = temp.resolve("missing"))
            assertCode("E_LINUX_SANDBOX_HELPER_UNAVAILABLE") { SandboxBackend(tools).requireSupported() }
            val backend = SandboxBackend(tools.copy(linuxLauncher = tool, python = tool))
            assertCode("E_CPU_BUDGET_PRECISION") {
                backend.command(
                    Path.of("/usr/bin/python3"),
                    ScriptRuntime.PYTHON,
                    temp.resolve("skill"),
                    ResourceBudget(maxCpuMillis = 999),
                )
            }
            val command =
                backend.command(
                    Path.of("/usr/bin/python3"),
                    ScriptRuntime.PYTHON,
                    temp.resolve("skill"),
                    ResourceBudget(maxCpuMillis = 1999),
                )
            assertTrue("--cpu=1:1" in command)
            assertCode("E_RUNTIME_SCOPE") { backend.executable(ScriptRuntime.PYTHON) }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `structured script output only references supplied IDs`() {
        val input = ScriptInput("input.v1", setOf("known-evidence"), setOf("known-candidate"), JsonObject(emptyMap()))
        val valid = output("known-evidence", "known-candidate")
        assertEquals("output.v1", validateOutput(valid, "output.v1", input)["schema"].toString().trim('"'))
        assertCode("E_SCRIPT_REFERENCE") { validateOutput(output("unknown", "known-candidate"), "output.v1", input) }
        assertCode("E_SCRIPT_REFERENCE") { validateOutput(output("known-evidence", "unknown"), "output.v1", input) }
        assertCode("E_SCRIPT_OUTPUT_SCHEMA") { validateOutput(valid, "other.v1", input) }
        assertCode("E_SCRIPT_OUTPUT_SCHEMA") { validateOutput(byteArrayOf(0xff.toByte()), "output.v1", input) }
        assertCode("E_SCRIPT_OUTPUT_SCHEMA") { validateOutput("[]".toByteArray(), "output.v1", input) }
    }

    private fun output(
        evidence: String,
        candidate: String,
    ) = buildJsonObject {
        put("schema", "output.v1")
        put("evidenceIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(evidence)) })
        put("candidateIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(candidate)) })
        put("result", JsonObject(emptyMap()))
    }.toString().toByteArray()

    private fun assertCode(
        code: String,
        block: () -> Unit,
    ) = assertEquals(code, assertFailsWith<ScriptException>(block = block).code)
}
