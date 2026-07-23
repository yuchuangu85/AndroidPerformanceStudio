package com.androidperformancestudio.perfetto.traceprocessor

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.CpuArchitecture
import com.androidperformancestudio.toolchain.HostOperatingSystem
import com.androidperformancestudio.toolchain.HostPlatform
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import java.nio.file.Files
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
) {
    suspend fun locate(): StudioResult<TraceProcessorBinary> {
        // 1. Check user-configured path
        if (configuredPath != null) {
            val path = Paths.get(configuredPath)
            if (path.exists() && path.isExecutable()) {
                return StudioResult.Success(
                    TraceProcessorBinary(path, "configured", "")
                )
            }
        }

        // 2. Check PATH
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(Path.of(":").toString())) {
            val candidate = Paths.get(dir, "trace_processor")
            if (candidate.exists() && candidate.isExecutable()) {
                return StudioResult.Success(
                    TraceProcessorBinary(candidate, "system", "")
                )
            }
        }

        // 3. Check bundled extraction root
        val platform = when (val result = hostPlatformDetector.detect()) {
            is StudioResult.Success -> result.value
            is StudioResult.Failure -> return StudioResult.Failure(result.error)
        }

        val bundledPath = extractionRoot.resolve(binaryName(platform))
        if (bundledPath.exists() && bundledPath.isExecutable()) {
            return StudioResult.Success(
                TraceProcessorBinary(bundledPath, "bundled", "")
            )
        }

        return StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = "TRACE_PROCESSOR_NOT_FOUND",
                message = "trace_processor not found. Install via: curl -LO https://get.perfetto.dev/trace_processor",
            )
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

        fun binaryName(platform: HostPlatform): String =
            when (platform.operatingSystem) {
                HostOperatingSystem.WINDOWS -> "trace_processor_shell.exe"
                else -> "trace_processor"
            }
    }
}
