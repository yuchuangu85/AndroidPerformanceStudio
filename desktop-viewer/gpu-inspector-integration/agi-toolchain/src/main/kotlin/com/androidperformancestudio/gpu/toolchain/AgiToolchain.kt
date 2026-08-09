@file:Suppress("MagicNumber", "ReturnCount", "SwallowedException")

package com.androidperformancestudio.gpu.toolchain

import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessTimeoutException
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.time.toKotlinDuration
import com.androidperformancestudio.platform.toolchain.HostProcessRunner as SharedHostProcessRunner

public data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

public interface HostProcessRunner {
    public fun run(arguments: List<String>, timeout: Duration): ProcessResult

    public fun launch(arguments: List<String>): RunningHostProcess
}

public class DefaultHostProcessRunner(
    private val delegate: SharedHostProcessRunner = JvmHostProcessRunner(),
) : HostProcessRunner {
    override fun run(arguments: List<String>, timeout: Duration): ProcessResult =
        try {
            val result =
                runBlocking {
                    delegate.executeText(
                        HostProcessRequest(
                            executable = Path.of(requireNotNull(arguments.firstOrNull()) { "Host command is empty" }),
                            arguments = arguments.drop(1),
                            timeout = timeout.toKotlinDuration(),
                        ),
                    )
                }
            ProcessResult(result.exitCode, result.stdout, result.stderr, false)
        } catch (_: HostProcessTimeoutException) {
            ProcessResult(-1, "", "", true)
        }

    override fun launch(arguments: List<String>): RunningHostProcess =
        delegate.launch(
            HostProcessLaunchRequest(
                executable = Path.of(requireNotNull(arguments.firstOrNull()) { "Host command is empty" }),
                arguments = arguments.drop(1),
            ),
        )
}

public class AgiLocator(
    private val runner: HostProcessRunner = DefaultHostProcessRunner(),
    private val environment: Map<String, String> = System.getenv(),
    private val osName: String = System.getProperty("os.name"),
) {
    public fun locate(configuredPath: Path? = null): AgiCapability {
        val candidates = buildList {
            configuredPath?.let(::add)
            environment["PATH"].orEmpty().split(java.io.File.pathSeparator).filter(String::isNotBlank).forEach { directory ->
                executableNames().forEach { name -> add(Path.of(directory, name)) }
            }
            addAll(defaultCandidates())
        }.distinct().filter { Files.isRegularFile(it) && Files.isExecutable(it) }
        if (candidates.isEmpty()) {
            return AgiCapability(
                executable = null,
                version = null,
                launchSupported = false,
                artifactOpenSupported = false,
                launchMode = AgiLaunchMode.UNSUPPORTED,
                supportedArguments = emptySet(),
                warnings = listOf("Android GPU Inspector executable was not found. Configure its local path."),
            )
        }
        val executable = candidates.first()
        val versionResult = runner.run(listOf(executable.toString(), "--version"), Duration.ofSeconds(3))
        val helpResult = runner.run(listOf(executable.toString(), "--help"), Duration.ofSeconds(3))
        val version = (versionResult.stdout + versionResult.stderr).lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val help = helpResult.stdout + helpResult.stderr
        val supported = listOf("--device", "--package", "--activity", "--capture").filter(help::contains).toSet()
        val artifactOpenSupported = executable.fileName.toString().substringBeforeLast('.').lowercase() in setOf("agi", "gapic")
        val warnings = buildList {
            if (versionResult.timedOut) add("AGI version probe timed out; GUI launch remains available.")
            if (versionResult.exitCode != 0 && version.isNullOrBlank()) add("AGI version could not be determined.")
            if (supported.isEmpty()) add("No stable automation arguments were detected; AGI will be launched in GUI-only mode.")
            if (!artifactOpenSupported) add("Opening an artifact through this configured executable was not verified.")
        }
        return AgiCapability(
            executable = executable,
            version = version,
            launchSupported = true,
            artifactOpenSupported = artifactOpenSupported,
            launchMode = if (supported.isEmpty()) AgiLaunchMode.GUI_ONLY else AgiLaunchMode.VERIFIED_CLI,
            supportedArguments = supported,
            warnings = warnings,
        )
    }

    public fun launch(capability: AgiCapability, arguments: List<String> = emptyList()): RunningHostProcess {
        val executable = requireNotNull(capability.executable) { "AGI executable is not configured" }
        require(capability.launchSupported) { "AGI launch is unavailable" }
        val safeArguments =
            if (capability.launchMode == AgiLaunchMode.VERIFIED_CLI) {
                arguments.filter { argument -> argument.startsWith("--") && argument.substringBefore('=') in capability.supportedArguments }
            } else {
                emptyList()
            }
        return runner.launch(listOf(executable.toString()) + safeArguments)
    }

    public fun launchArtifact(
        capability: AgiCapability,
        artifact: Path,
    ): RunningHostProcess {
        val executable = requireNotNull(capability.executable) { "AGI executable is not configured" }
        require(capability.artifactOpenSupported) { "Opening artifacts with this AGI executable was not verified" }
        require(Files.isRegularFile(artifact)) { "Artifact does not exist: $artifact" }
        return runner.launch(listOf(executable.toString(), artifact.toAbsolutePath().normalize().toString()))
    }

    private fun executableNames(): List<String> =
        if (osName.startsWith("Windows", true)) listOf("agi.exe", "gapic.exe") else listOf("agi", "gapic")

    private fun defaultCandidates(): List<Path> = when {
        osName.startsWith("Mac", true) -> listOf(
            Path.of("/Applications/Android GPU Inspector.app/Contents/MacOS/agi"),
            Path.of(System.getProperty("user.home"), "Applications", "Android GPU Inspector.app", "Contents", "MacOS", "agi"),
        )
        osName.startsWith("Windows", true) -> listOf(
            Path.of(environment["LOCALAPPDATA"].orEmpty(), "Android GPU Inspector", "agi.exe"),
            Path.of(environment["ProgramFiles"].orEmpty(), "Android GPU Inspector", "agi.exe"),
        )
        else -> listOf(
            Path.of("/opt/android-gpu-inspector/agi"),
            Path.of("/opt/android-gpu-inspector/gapic"),
            Path.of("/usr/local/bin/agi"),
            Path.of("/usr/local/bin/gapic"),
        )
    }
}
