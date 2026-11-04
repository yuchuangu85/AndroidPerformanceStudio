package com.androidperformancestudio.aiplatform.script

import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

/** Gradle-free focused runner and actual-backend argv exporter for an existing Linux VM. */
public object NativeSandboxChecks {
    @JvmStatic
    public fun main(args: Array<String>) {
        for (type in listOf(SandboxedScriptRuntimeTest::class.java, ArtifactPublisherTest::class.java)) {
            val instance = type.getDeclaredConstructor().newInstance()
            for (method in type.declaredMethods.sortedBy { it.name }) {
                if (method.getAnnotation(org.junit.jupiter.api.Test::class.java) != null) {
                    method.invoke(instance)
                    println("PASS ${type.simpleName}.${method.name}")
                }
            }
        }
        val root = Path.of(args.first())
        if (args.size == 3) {
            for (language in listOf(ScriptRuntime.PYTHON, ScriptRuntime.NODE)) {
                NativeLinuxAcceptance.verify(
                    Files.createDirectory(root.resolve(language.name.lowercase())),
                    Path.of(args[1]),
                    Path.of(args[2]),
                    language,
                )
            }
        }
        val fakeTool = Files.createFile(root.resolve("host-owned-fixture-tool"))
        check(fakeTool.toFile().setExecutable(true))
        val backend =
            SandboxBackend(
                SandboxToolchain(os = "Linux", bubblewrap = fakeTool, prlimit = fakeTool, linuxLauncher = fakeTool),
            )
        val commands =
            ScriptRuntime.entries.filter { it != ScriptRuntime.NONE }.associate { runtime ->
                val executable = Path.of(if (runtime == ScriptRuntime.PYTHON) "/usr/bin/python3" else "/usr/bin/node")
                val argv = backend.command(executable, runtime, Path.of("/__APS_SCRIPT__"), ResourceBudget(maxCpuMillis = 1000))
                // Substitute only known tool/input positions, never shell-expand command text.
                runtime.name.lowercase() to
                    argv.mapIndexed { index, value ->
                        when {
                            index == 0 -> "__APS_PRLIMIT__"
                            index > 0 && argv[index - 1] == "--" && value == fakeTool.toString() -> "__APS_BWRAP__"
                            value == fakeTool.toString() -> "__APS_HELPER__"
                            else -> value
                        }
                    }
            }
        Files.writeString(root.resolve("commands.json"), PlatformJson.codec.encodeToString(commands))
        println("EXPORTED_NATIVE_ARGV=${root.resolve("commands.json")}")
    }
}
