package com.androidperformancestudio.platform.adb

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class AdbLocatorConfiguration(
    val executablePath: Path? = null,
    val androidSdkPath: Path? = null,
)

enum class AdbLocationSource {
    EXPLICIT_EXECUTABLE,
    EXPLICIT_SDK,
    ADB_ENVIRONMENT,
    ANDROID_HOME,
    ANDROID_SDK_ROOT,
    PATH,
    DEFAULT_SDK,
}

data class AdbLocation(
    val executable: Path,
    val source: AdbLocationSource,
)

class AdbExecutableLocator(
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val osName: String = System.getProperty("os.name"),
    private val pathSeparator: String = File.pathSeparator,
    private val isUsableExecutable: (Path) -> Boolean = { path ->
        Files.isRegularFile(path) && (isWindows(osName) || Files.isExecutable(path))
    },
) {
    fun locate(configuration: AdbLocatorConfiguration = AdbLocatorConfiguration()): AdbLocation =
        when {
            configuration.executablePath != null -> locateConfiguredExecutable(configuration.executablePath)
            configuration.androidSdkPath != null -> locateConfiguredSdk(configuration.androidSdkPath)
            else ->
                candidates()
                    .firstOrNull { isUsableExecutable(it.executable) }
                    ?: throw AdbNotFoundException()
        }

    private fun locateConfiguredExecutable(executable: Path): AdbLocation {
        if (!isUsableExecutable(executable)) throw AdbNotExecutableException(executable)
        return AdbLocation(executable, AdbLocationSource.EXPLICIT_EXECUTABLE)
    }

    private fun locateConfiguredSdk(sdk: Path): AdbLocation {
        val executable = sdk.resolve("platform-tools").resolve(executableName)
        if (!isUsableExecutable(executable)) throw AdbNotExecutableException(executable)
        return AdbLocation(executable, AdbLocationSource.EXPLICIT_SDK)
    }

    private fun candidates(): Sequence<AdbLocation> =
        sequence {
            environmentValue("ADB")?.toPath()?.let {
                yield(AdbLocation(it, AdbLocationSource.ADB_ENVIRONMENT))
            }
            environmentValue("ADB_PATH")?.toPath()?.let {
                yield(AdbLocation(it, AdbLocationSource.ADB_ENVIRONMENT))
            }
            sdkCandidate("ANDROID_HOME", AdbLocationSource.ANDROID_HOME)?.let { yield(it) }
            sdkCandidate("ANDROID_SDK_ROOT", AdbLocationSource.ANDROID_SDK_ROOT)?.let { yield(it) }
            environmentValue("PATH")
                .orEmpty()
                .splitToSequence(pathSeparator)
                .filter(String::isNotBlank)
                .mapNotNull { it.toPath() }
                .map { AdbLocation(it.resolve(executableName), AdbLocationSource.PATH) }
                .forEach { yield(it) }
            defaultSdkDirectories()
                .map { it.resolve("platform-tools").resolve(executableName) }
                .map { AdbLocation(it, AdbLocationSource.DEFAULT_SDK) }
                .forEach { yield(it) }
        }.distinctBy { it.executable.normalize() }

    private fun sdkCandidate(
        name: String,
        source: AdbLocationSource,
    ): AdbLocation? =
        environmentValue(name)
            ?.toPath()
            ?.resolve("platform-tools")
            ?.resolve(executableName)
            ?.let { AdbLocation(it, source) }

    private fun defaultSdkDirectories(): List<Path> =
        when {
            isWindows(osName) ->
                listOf(
                    environmentValue("LOCALAPPDATA")
                        ?.toPath()
                        ?.resolve("Android/Sdk")
                        ?: userHome.resolve("AppData/Local/Android/Sdk"),
                )
            osName.contains("mac", ignoreCase = true) ->
                listOf(userHome.resolve("Library/Android/sdk"))
            else -> listOf(userHome.resolve("Android/Sdk"))
        }

    private fun environmentValue(name: String): String? =
        if (isWindows(osName)) {
            environment.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
        } else {
            environment[name]
        }?.takeIf(String::isNotBlank)

    private fun String.toPath(): Path? {
        val unquoted = trim().removeSurrounding("\"")
        val expanded =
            if (isWindows(osName)) {
                WINDOWS_VARIABLE.replace(unquoted) { match ->
                    environmentValue(match.groupValues[1]) ?: match.value
                }
            } else {
                unquoted
            }
        return runCatching { Path.of(expanded) }.getOrNull()
    }

    private val executableName: String
        get() = if (isWindows(osName)) "adb.exe" else "adb"

    private companion object {
        val WINDOWS_VARIABLE = Regex("%([^%]+)%")

        fun isWindows(osName: String): Boolean = osName.contains("windows", ignoreCase = true)
    }
}
