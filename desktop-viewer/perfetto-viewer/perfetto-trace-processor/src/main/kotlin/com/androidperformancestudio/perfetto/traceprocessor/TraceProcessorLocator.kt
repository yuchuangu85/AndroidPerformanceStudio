package com.androidperformancestudio.perfetto.traceprocessor

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.HostOperatingSystem
import com.androidperformancestudio.toolchain.HostPlatform
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

data class TraceProcessorBinary(
    val path: Path,
    val version: String,
    val sha256: String,
)

class TraceProcessorLocator(
    private val hostPlatformDetector: SystemHostPlatformDetector = SystemHostPlatformDetector(),
    private val extractionRoot: Path = defaultExtractionRoot(),
    private val configuredPath: String? = System.getenv("PERFETTO_TRACE_PROCESSOR_PATH"),
    private val applicationResourcesPath: String? = System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY),
    private val pathEnvironment: String = System.getenv("PATH").orEmpty(),
) {
    suspend fun locate(): StudioResult<TraceProcessorBinary> {
        // 1. Check user-configured path
        if (configuredPath != null) {
            val path = Paths.get(configuredPath)
            if (path.exists() && path.isExecutable()) {
                return StudioResult.Success(
                    TraceProcessorBinary(path, "configured", ""),
                )
            }
        }

        // 2. Prefer the pinned packaged/installed v57.2 binary over an arbitrary PATH version.
        val platform =
            when (val result = hostPlatformDetector.detect()) {
                is StudioResult.Success -> result.value
                is StudioResult.Failure -> return StudioResult.Failure(result.error)
            }

        val packagedPath =
            applicationResourcesPath
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?.resolve("perfetto-tools")
                ?.resolve(binaryName(platform))
        if (packagedPath != null && packagedPath.exists() && packagedPath.isExecutable()) {
            return StudioResult.Success(TraceProcessorBinary(packagedPath, "packaged-v57.2", ""))
        }

        for (name in listOf(binaryName(platform), LAUNCHER_NAME)) {
            val bundledPath = extractionRoot.resolve(name)
            if (bundledPath.exists() && bundledPath.isExecutable()) {
                return StudioResult.Success(
                    TraceProcessorBinary(bundledPath, "installed-v57.2", ""),
                )
            }
        }

        // 3. Fall back to a compatible user-installed PATH binary.
        for (dir in pathEnvironment.split(File.pathSeparator).filter(String::isNotBlank)) {
            for (name in TRACE_PROCESSOR_NAMES) {
                val candidate = Paths.get(dir, name)
                if (candidate.exists() && candidate.isExecutable()) {
                    return StudioResult.Success(TraceProcessorBinary(candidate, "system", ""))
                }
            }
        }

        return StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = "TRACE_PROCESSOR_NOT_FOUND",
                message = "trace_processor v57.2 not found. Run ./scripts/install-trace-processor.sh",
            ),
        )
    }

    companion object {
        fun defaultExtractionRoot(): Path =
            Paths.get(
                System.getProperty("user.home"),
                ".android-performance-studio",
                "tools",
                "perfetto",
                "v57.2",
            )

        private val TRACE_PROCESSOR_NAMES = listOf("trace_processor", "trace_processor_shell", "trace_processor_shell.exe")

        fun binaryName(platform: HostPlatform): String =
            when (platform.operatingSystem) {
                HostOperatingSystem.WINDOWS -> "trace_processor_shell.exe"
                else -> "trace_processor_shell"
            }

        private const val LAUNCHER_NAME = "trace_processor"
        private const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"
    }
}
