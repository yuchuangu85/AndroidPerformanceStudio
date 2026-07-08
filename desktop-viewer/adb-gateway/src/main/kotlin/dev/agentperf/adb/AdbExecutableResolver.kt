package dev.agentperf.adb

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

internal class AdbExecutableResolver(
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: String? = System.getProperty("user.home"),
    private val osName: String = System.getProperty("os.name"),
    private val pathSeparator: String = File.pathSeparator,
) {
    fun resolve(): String =
        candidatePaths()
            .firstOrNull(::isUsableExecutable)
            ?.toString()
            ?: adbFileName()

    private fun candidatePaths(): Sequence<Path> = sequence {
        explicitAdbPath("ADB")?.let { yield(it) }
        explicitAdbPath("ADB_PATH")?.let { yield(it) }

        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
            .mapNotNull(environment::get)
            .filter(String::isNotBlank)
            .map { Path(it).resolve("platform-tools").resolve(adbFileName()) }
            .forEach { yield(it) }

        environment["PATH"]
            ?.split(pathSeparator)
            ?.asSequence()
            ?.filter(String::isNotBlank)
            ?.map { Path(it).resolve(adbFileName()) }
            ?.forEach { yield(it) }

        commonSdkLocations().forEach { yield(it) }
    }.distinct()

    private fun explicitAdbPath(name: String): Path? =
        environment[name]
            ?.takeIf(String::isNotBlank)
            ?.let(::Path)

    private fun commonSdkLocations(): Sequence<Path> = sequence {
        userHome?.takeIf(String::isNotBlank)?.let { home ->
            yield(Path(home).resolve("Library/Android/sdk/platform-tools").resolve(adbFileName()))
            yield(Path(home).resolve("Android/Sdk/platform-tools").resolve(adbFileName()))
            environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let { localAppData ->
                yield(Path(localAppData).resolve("Android/Sdk/platform-tools").resolve(adbFileName()))
            }
        }
        if (isMacOs()) {
            yield(Path("/opt/homebrew/bin").resolve(adbFileName()))
            yield(Path("/usr/local/bin").resolve(adbFileName()))
        }
        if (!isWindows()) {
            yield(Path("/usr/bin").resolve(adbFileName()))
            yield(Path("/usr/local/bin").resolve(adbFileName()))
        }
    }

    private fun isUsableExecutable(path: Path): Boolean =
        Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path))

    private fun adbFileName(): String = if (isWindows()) "adb.exe" else "adb"

    private fun isMacOs(): Boolean = osName.lowercase().contains("mac")

    private fun isWindows(): Boolean = osName.lowercase().contains("windows")

    companion object {
        fun resolveDefault(): String = AdbExecutableResolver().resolve()

        fun missingExecutableMessage(executable: String, causeMessage: String?): String = buildString {
            append("ADB executable not found: ")
            append(executable)
            append('.')
            append(' ')
            append("Install Android SDK Platform Tools, set ANDROID_HOME or ANDROID_SDK_ROOT, ")
            append("or add platform-tools to PATH.")
            if (!causeMessage.isNullOrBlank()) {
                append(" Original error: ")
                append(causeMessage)
            }
        }
    }
}
