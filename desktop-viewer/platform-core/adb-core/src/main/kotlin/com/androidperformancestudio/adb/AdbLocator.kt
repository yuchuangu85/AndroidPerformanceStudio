package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbExecutableLocator
import com.androidperformancestudio.platform.adb.AdbLocatorConfiguration
import com.androidperformancestudio.platform.adb.AdbNotExecutableException
import com.androidperformancestudio.platform.adb.AdbNotFoundException
import com.androidperformancestudio.platform.toolchain.HostOperatingSystem
import com.androidperformancestudio.platform.toolchain.HostPlatform
import com.androidperformancestudio.platform.toolchain.SystemHostPlatformDetector
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import com.androidperformancestudio.platform.adb.AdbLocationSource as CoreAdbLocationSource

data class AdbConfiguration(
    val userConfiguredPath: Path? = null,
    val androidSdkPath: Path? = null,
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

/**
 * Simpleperf-facing compatibility adapter. ADB discovery itself lives in adb-core.
 */
class SystemAdbLocator(
    platform: HostPlatform,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
    pathSeparator: String = File.pathSeparator,
    isUsableExecutable: (Path) -> Boolean = { path ->
        Files.isRegularFile(path) &&
            (platform.operatingSystem == HostOperatingSystem.WINDOWS || Files.isExecutable(path))
    },
) {
    private val delegate =
        AdbExecutableLocator(
            environment = environment,
            userHome = userHome,
            osName = platform.operatingSystem.toOsName(),
            pathSeparator = pathSeparator,
            isUsableExecutable = isUsableExecutable,
        )

    fun locate(configuration: AdbConfiguration = AdbConfiguration()): StudioResult<AdbLocation> =
        try {
            val location =
                delegate.locate(
                    AdbLocatorConfiguration(
                        executablePath = configuration.userConfiguredPath,
                        androidSdkPath = configuration.androidSdkPath,
                    ),
                )
            StudioResult.Success(
                AdbLocation(
                    executable = location.executable,
                    source =
                        when (location.source) {
                            CoreAdbLocationSource.EXPLICIT_EXECUTABLE,
                            CoreAdbLocationSource.EXPLICIT_SDK,
                            CoreAdbLocationSource.ADB_ENVIRONMENT,
                            -> AdbLocationSource.USER_CONFIGURATION
                            CoreAdbLocationSource.ANDROID_HOME -> AdbLocationSource.ANDROID_HOME
                            CoreAdbLocationSource.ANDROID_SDK_ROOT -> AdbLocationSource.ANDROID_SDK_ROOT
                            CoreAdbLocationSource.PATH -> AdbLocationSource.PATH
                            CoreAdbLocationSource.DEFAULT_SDK -> AdbLocationSource.DEFAULT_SDK
                        },
                ),
            )
        } catch (error: AdbNotExecutableException) {
            val configuredSdk = configuration.androidSdkPath
            failure(
                code = if (configuredSdk == null) "ADB_CONFIGURED_PATH_INVALID" else "ADB_CONFIGURED_SDK_INVALID",
                message =
                    if (configuredSdk == null) {
                        "Configured adb executable is not usable: ${error.path}"
                    } else {
                        "Configured Android SDK doesn't contain a usable platform-tools adb: $configuredSdk"
                    },
                cause = error,
            )
        } catch (error: AdbNotFoundException) {
            failure(
                code = "ADB_NOT_FOUND",
                message = "adb wasn't found in Android SDK, PATH, or the default SDK directory",
                cause = error,
            )
        }

    private fun failure(
        code: String,
        message: String,
        cause: Throwable,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = code,
                message = message,
                cause = cause,
            ),
        )
}

/** Resolves the current host's ADB executable through the single adb-core locator. */
public fun resolveSystemAdb(
    configuration: AdbConfiguration = AdbConfiguration(),
): StudioResult<AdbLocation> {
    val platform = SystemHostPlatformDetector().detect()
    return when (platform) {
        is StudioResult.Success -> SystemAdbLocator(platform.value).locate(configuration)
        is StudioResult.Failure -> StudioResult.Failure(platform.error)
    }
}

/** Returns the current host's resolved ADB path, or `null` when ADB is unavailable. */
public fun defaultAdbExecutable(
    configuration: AdbConfiguration = AdbConfiguration(),
): Path? = (resolveSystemAdb(configuration) as? StudioResult.Success)?.value?.executable

/** Returns a command path even when discovery fails, preserving a typed adb-core fallback. */
public fun defaultAdbCommandExecutable(
    configuration: AdbConfiguration = AdbConfiguration(),
): Path =
    defaultAdbExecutable(configuration)
        ?: Path.of(if (System.getProperty("os.name").contains("windows", ignoreCase = true)) "adb.exe" else "adb")

private fun HostOperatingSystem.toOsName(): String =
    when (this) {
        HostOperatingSystem.MACOS -> "Mac OS X"
        HostOperatingSystem.LINUX -> "Linux"
        HostOperatingSystem.WINDOWS -> "Windows"
    }
