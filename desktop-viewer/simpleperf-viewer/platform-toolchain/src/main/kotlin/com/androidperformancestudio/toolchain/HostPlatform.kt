package com.androidperformancestudio.toolchain

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult

enum class HostOperatingSystem(
    val resourceName: String,
) {
    WINDOWS("windows"),
    LINUX("linux"),
    MACOS("macos"),
}

enum class CpuArchitecture(
    val resourceName: String,
) {
    X64("x64"),
    ARM64("arm64"),
}

data class HostPlatform(
    val operatingSystem: HostOperatingSystem,
    val architecture: CpuArchitecture,
) {
    val resourceDirectory: String = "${operatingSystem.resourceName}-${architecture.resourceName}"
}

fun interface HostPlatformDetector {
    fun detect(): StudioResult<HostPlatform>
}

class SystemHostPlatformDetector(
    private val osName: () -> String = { System.getProperty("os.name").orEmpty() },
    private val osArch: () -> String = { System.getProperty("os.arch").orEmpty() },
) : HostPlatformDetector {
    override fun detect(): StudioResult<HostPlatform> {
        val rawOsName = osName()
        val rawArchitecture = osArch()
        val operatingSystem =
            when {
                rawOsName.contains("mac", ignoreCase = true) -> HostOperatingSystem.MACOS
                rawOsName.contains("windows", ignoreCase = true) -> HostOperatingSystem.WINDOWS
                rawOsName.contains("linux", ignoreCase = true) -> HostOperatingSystem.LINUX
                else -> null
            }

        val architecture =
            when (rawArchitecture.lowercase()) {
                "amd64", "x86_64" -> CpuArchitecture.X64
                "aarch64", "arm64" -> CpuArchitecture.ARM64
                else -> null
            }

        return when {
            operatingSystem == null ->
                unsupported(
                    code = "UNSUPPORTED_OPERATING_SYSTEM",
                    message = "Unsupported operating system: $rawOsName",
                )
            architecture == null ->
                unsupported(
                    code = "UNSUPPORTED_ARCHITECTURE",
                    message = "Unsupported CPU architecture: $rawArchitecture",
                )
            else -> StudioResult.Success(HostPlatform(operatingSystem, architecture))
        }
    }

    private fun unsupported(
        code: String,
        message: String,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.UNSUPPORTED_PLATFORM,
                code = code,
                message = message,
            ),
        )
}
