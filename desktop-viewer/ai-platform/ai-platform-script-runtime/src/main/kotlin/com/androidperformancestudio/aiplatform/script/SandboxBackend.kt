package com.androidperformancestudio.aiplatform.script

import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Host-authored argv only. No platform may fall back to an ordinary interpreter process. */
internal class SandboxBackend(
    private val tools: SandboxToolchain,
) {
    val name: String get() = if (tools.os.startsWith("Linux")) "linux-bubblewrap-prlimit-seccomp-v1" else "unavailable"

    fun executable(runtime: ScriptRuntime): Path {
        val explicit = if (runtime == ScriptRuntime.PYTHON) tools.python else tools.node
        val candidates =
            if (runtime ==
                ScriptRuntime.PYTHON
            ) {
                listOf("/usr/bin/python3")
            } else {
                listOf("/usr/bin/node")
            }
        val path = explicit ?: candidates.map(Path::of).firstOrNull { Files.isExecutable(it) }
        scriptRequire(path != null && Files.isExecutable(path), "E_SCRIPT_RUNTIME_UNAVAILABLE")
        val canonical = path!!.toRealPath()
        // Only the declared OS runtime trees are mounted; no implicit home/nvm/virtualenv read grants.
        scriptRequire(canonical.startsWith("/usr/bin/") || canonical.startsWith("/bin/"), "E_RUNTIME_SCOPE")
        return canonical
    }

    fun requireSupported() {
        if (tools.os.startsWith("Mac") || tools.os.startsWith("Darwin")) {
            // See native-sandbox-contracts.md: deny-default startup and hard resource limits are unqualified.
            throw ScriptException("E_MACOS_SANDBOX_UNQUALIFIED")
        }
        if (tools.os.startsWith("Windows")) throw ScriptException("E_WINDOWS_SANDBOX_UNQUALIFIED")
        scriptRequire(tools.os.startsWith("Linux"), "E_PLATFORM_SANDBOX_UNAVAILABLE")
        scriptRequire(Files.isExecutable(tools.bubblewrap) && Files.isExecutable(tools.prlimit), "E_SANDBOX_RUNTIME_UNAVAILABLE")
        scriptRequire(Files.isExecutable(tools.linuxLauncher), "E_LINUX_SANDBOX_HELPER_UNAVAILABLE")
    }

    fun command(
        executable: Path,
        runtime: ScriptRuntime,
        script: Path,
        budget: ResourceBudget,
    ): List<String> {
        requireSupported()
        // RLIMIT_CPU has whole-second precision. Never silently round a maximum upward.
        val cpuSeconds = budget.maxCpuMillis / 1000
        scriptRequire(cpuSeconds >= 1, "E_CPU_BUDGET_PRECISION")
        val command =
            mutableListOf(
                tools.prlimit.toString(),
                "--as=${budget.maxMemoryBytes}:${budget.maxMemoryBytes}",
                "--cpu=$cpuSeconds:$cpuSeconds",
                "--fsize=${budget.maxOutputBytes}:${budget.maxOutputBytes}",
                "--nofile=64:64",
                "--core=0:0",
                "--",
                tools.bubblewrap.toString(),
                "--die-with-parent",
                "--new-session",
                "--unshare-all",
                "--cap-drop",
                "ALL",
                "--clearenv",
            )
        // In particular, do not expose /usr/local, /usr/etc, home directories or a host-root bind.
        listOf("/usr/bin", "/usr/lib", "/usr/lib64", "/lib", "/lib64").forEach { path ->
            command += listOf("--ro-bind-try", path, path)
        }
        if (runtime == ScriptRuntime.NODE) {
            // Debian/Ubuntu externalize these builtins. Never mount the containing package trees.
            listOf(
                "/usr/share/nodejs/cjs-module-lexer/lexer.js",
                "/usr/share/nodejs/cjs-module-lexer/dist/lexer.js",
                "/usr/share/nodejs/undici/undici-fetch.js",
                "/usr/share/nodejs/acorn/dist/acorn.js",
                "/usr/share/nodejs/acorn-walk/dist/walk.js",
            ).forEach { name ->
                val path = Path.of(name)
                checkPath(path)
                scriptRequire(
                    !Files.exists(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS),
                    "E_NODE_SYSTEM_RUNTIME_SCOPE",
                )
                command += listOf("--ro-bind-try", name, name)
            }
        }
        command +=
            listOf(
                "--dir",
                "/input",
                "--dir",
                "/home",
                "--dev",
                "/dev",
                "--remount-ro",
                "/dev",
                "--ro-bind",
                script.toString(),
                "/input/skill",
                "--ro-bind",
                tools.linuxLauncher.toString(),
                "/input/launcher",
                "--dir",
                "/work",
                "--dir",
                "/tmp",
                "--remount-ro",
                "/",
                "--size",
                budget.maxOutputBytes.toString(),
                "--perms",
                "0700",
                "--tmpfs",
                "/work",
                "--size",
                budget.maxOutputBytes.toString(),
                "--perms",
                "0700",
                "--tmpfs",
                "/tmp",
                "--setenv",
                "HOME",
                "/home",
                "--setenv",
                "TMPDIR",
                "/tmp",
                "--setenv",
                "LANG",
                "C.UTF-8",
                "--chdir",
                "/work",
                "--",
                "/input/launcher",
                executable.toString(),
            )
        command +=
            if (runtime ==
                ScriptRuntime.PYTHON
            ) {
                listOf("-I", "-B", "/input/skill")
            } else {
                listOf("--jitless", "--no-addons", "--max-old-space-size=64", "/input/skill")
            }
        return command
    }
}
