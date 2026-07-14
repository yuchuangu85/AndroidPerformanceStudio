package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.HostOperatingSystem
import com.androidperformancestudio.toolchain.HostPlatform
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class AdbConfiguration(
    val userConfiguredPath: Path? = null,
)

enum class AdbLocationSource {
    USER_CONFIGURATION,
    ANDROID_HOME,
    ANDROID_SDK_ROOT,
    PATH,
    DEFAULT_SDK,
}

data class AdbLocation(
    val executable: Path,
    val source: AdbLocationSource,
)

class SystemAdbLocator(
    private val platform: HostPlatform,
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val pathSeparator: String = File.pathSeparator,
    private val isUsableExecutable: (Path) -> Boolean = { path ->
        Files.isRegularFile(path) &&
            (platform.operatingSystem == HostOperatingSystem.WINDOWS || Files.isExecutable(path))
    },
) {
    fun locate(configuration: AdbConfiguration = AdbConfiguration()): StudioResult<AdbLocation> =
        configuration.userConfiguredPath?.let(::locateConfigured) ?: locateAutomatically()

    private fun locateConfigured(configured: Path): StudioResult<AdbLocation> =
        if (isUsableExecutable(configured)) {
            success(configured, AdbLocationSource.USER_CONFIGURATION)
        } else {
            failure(
                code = "ADB_CONFIGURED_PATH_INVALID",
                message = "Configured adb executable is not usable: $configured",
            )
        }

    private fun locateAutomatically(): StudioResult<AdbLocation> {
        val candidate = candidates().firstOrNull { isUsableExecutable(it.path) }
        return candidate?.let { success(it.path, it.source) }
            ?: failure(
                code = "ADB_NOT_FOUND",
                message = "adb wasn't found in Android SDK, PATH, or the default SDK directory",
            )
    }

    private fun candidates(): Sequence<AdbCandidate> =
        sequence {
            sdkCandidate("ANDROID_HOME", AdbLocationSource.ANDROID_HOME)?.let { yield(it) }
            sdkCandidate("ANDROID_SDK_ROOT", AdbLocationSource.ANDROID_SDK_ROOT)?.let { yield(it) }
            pathCandidates().forEach { yield(it) }
            defaultSdkDirectories().forEach { sdkDirectory ->
                yield(
                    AdbCandidate(
                        sdkDirectory.resolve("platform-tools").resolve(executableName),
                        AdbLocationSource.DEFAULT_SDK,
                    ),
                )
            }
        }

    private fun sdkCandidate(
        variable: String,
        source: AdbLocationSource,
    ): AdbCandidate? =
        environment[variable]
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.resolve("platform-tools")
            ?.resolve(executableName)
            ?.let { AdbCandidate(it, source) }

    private fun pathCandidates(): Sequence<AdbCandidate> =
        environment["PATH"]
            .orEmpty()
            .splitToSequence(pathSeparator)
            .filter(String::isNotBlank)
            .map { directory -> AdbCandidate(Path.of(directory).resolve(executableName), AdbLocationSource.PATH) }

    private fun defaultSdkDirectories(): List<Path> =
        when (platform.operatingSystem) {
            HostOperatingSystem.MACOS -> listOf(userHome.resolve("Library/Android/sdk"))
            HostOperatingSystem.LINUX -> listOf(userHome.resolve("Android/Sdk"))
            HostOperatingSystem.WINDOWS ->
                listOf(
                    environment["LOCALAPPDATA"]
                        ?.takeIf(String::isNotBlank)
                        ?.let(Path::of)
                        ?.resolve("Android/Sdk")
                        ?: userHome.resolve("AppData/Local/Android/Sdk"),
                )
        }

    private val executableName: String
        get() = if (platform.operatingSystem == HostOperatingSystem.WINDOWS) "adb.exe" else "adb"

    private fun success(
        path: Path,
        source: AdbLocationSource,
    ): StudioResult.Success<AdbLocation> =
        StudioResult.Success(
            AdbLocation(
                executable = path.toAbsolutePath().normalize(),
                source = source,
            ),
        )

    private fun failure(
        code: String,
        message: String,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = code,
                message = message,
            ),
        )

    private data class AdbCandidate(
        val path: Path,
        val source: AdbLocationSource,
    )
}
