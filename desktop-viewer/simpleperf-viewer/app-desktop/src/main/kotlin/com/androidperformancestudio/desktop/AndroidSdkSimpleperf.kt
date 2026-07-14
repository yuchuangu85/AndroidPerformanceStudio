package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

internal fun defaultAndroidSdkRoots(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
    osName: String = System.getProperty("os.name").orEmpty(),
): List<Path> =
    buildList {
        environment["ANDROID_SDK_ROOT"]?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
        environment["ANDROID_HOME"]?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
        when {
            osName.contains("mac", ignoreCase = true) -> add(userHome.resolve("Library/Android/sdk"))
            osName.contains("windows", ignoreCase = true) ->
                environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let {
                    add(Path.of(it).resolve("Android/Sdk"))
                }
            else -> add(userHome.resolve("Android/Sdk"))
        }
    }.map { it.toAbsolutePath().normalize() }
        .distinct()

internal fun findAndroidNdkSimpleperf(
    sdkRoots: List<Path> = defaultAndroidSdkRoots(),
    osName: String = System.getProperty("os.name").orEmpty(),
): Path? {
    val relativeExecutable = hostSimpleperfRelativePath(osName) ?: return null
    return sdkRoots
        .asSequence()
        .flatMap { sdk -> ndkDirectories(sdk).asSequence() }
        .sortedWith { left, right -> compareNdkVersions(right.fileName.toString(), left.fileName.toString()) }
        .map { it.resolve(relativeExecutable) }
        .firstOrNull(Path::isRegularFile)
}

private fun ndkDirectories(sdk: Path): List<Path> =
    buildList {
        val sideBySide = sdk.resolve("ndk")
        if (Files.isDirectory(sideBySide)) {
            Files.list(sideBySide).use { paths -> addAll(paths.filter(Files::isDirectory).toList()) }
        }
        sdk.resolve("ndk-bundle").takeIf(Files::isDirectory)?.let(::add)
    }

private fun hostSimpleperfRelativePath(osName: String): Path? =
    when {
        osName.contains("mac", ignoreCase = true) -> Path.of("simpleperf/bin/darwin/x86_64/simpleperf")
        osName.contains("windows", ignoreCase = true) -> Path.of("simpleperf/bin/windows/x86_64/simpleperf.exe")
        osName.contains("linux", ignoreCase = true) -> Path.of("simpleperf/bin/linux/x86_64/simpleperf")
        else -> null
    }

private fun compareNdkVersions(
    left: String,
    right: String,
): Int {
    val leftParts = left.split('.', '-').map { it.toIntOrNull() ?: Int.MIN_VALUE }
    val rightParts = right.split('.', '-').map { it.toIntOrNull() ?: Int.MIN_VALUE }
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val compared = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (compared != 0) return compared
    }
    return left.compareTo(right)
}
