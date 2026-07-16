@file:Suppress("MaxLineLength")

package com.androidperformancestudio.toolchain

import com.androidperformancestudio.model.StudioResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

enum class AndroidLlvmTool(
    val executableName: String,
) {
    SYMBOLIZER("llvm-symbolizer"),
    OBJDUMP("llvm-objdump"),
    READELF("llvm-readelf"),
}

fun interface AndroidLlvmToolProvider {
    fun locate(tool: AndroidLlvmTool): Path?
}

class AndroidLlvmToolLocator(
    private val configuredNdk: Path? = null,
    private val environment: Map<String, String> = System.getenv(),
    private val hostTag: String = defaultNdkHostTag(),
) : AndroidLlvmToolProvider {
    override fun locate(tool: AndroidLlvmTool): Path? =
        candidateDirectories()
            .asSequence()
            .map { directory -> directory.resolve(tool.fileName(hostTag)) }
            .firstOrNull(Files::isRegularFile)

    private fun candidateDirectories(): List<Path> =
        buildList {
            configuredNdk?.let { ndk -> add(ndk.toolBin(hostTag)) }
            environment["ANDROID_NDK_HOME"]?.takeIf(String::isNotBlank)?.let { ndk ->
                add(Path.of(ndk).toolBin(hostTag))
            }
            sdkRoot()?.let { sdk -> addAll(sdkNdkBins(sdk, hostTag)) }
            environment["PATH"]
                ?.split(File.pathSeparatorChar)
                ?.filter(String::isNotBlank)
                ?.mapTo(this, Path::of)
        }.distinct()

    private fun sdkRoot(): Path? =
        environment["ANDROID_SDK_ROOT"]
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: environment["ANDROID_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
}

private fun sdkNdkBins(
    sdk: Path,
    hostTag: String,
): List<Path> {
    val ndkDirectory = sdk.resolve("ndk")
    if (!ndkDirectory.isDirectory()) return emptyList()
    return Files.list(ndkDirectory).use { versions ->
        versions
            .filter(Files::isDirectory)
            .sorted(descendingNdkVersionComparator)
            .map { ndk -> ndk.toolBin(hostTag) }
            .toList()
    }
}

private val descendingNdkVersionComparator =
    Comparator<Path> { left, right ->
        compareVersionParts(versionParts(right.fileName.toString()), versionParts(left.fileName.toString()))
    }

private fun versionParts(version: String): List<Int> = version.split('.', '-', '_').map { part -> part.toIntOrNull() ?: -1 }

private fun compareVersionParts(
    left: List<Int>,
    right: List<Int>,
): Int {
    val length = maxOf(left.size, right.size)
    for (index in 0 until length) {
        val leftPart = left.getOrElse(index) { 0 }
        val rightPart = right.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }
    return 0
}

private fun Path.toolBin(hostTag: String): Path = resolve("toolchains/llvm/prebuilt/$hostTag/bin")

private fun AndroidLlvmTool.fileName(hostTag: String): String = if (hostTag.startsWith("windows")) "$executableName.exe" else executableName

private fun defaultNdkHostTag(): String =
    when (val result = SystemHostPlatformDetector().detect()) {
        is StudioResult.Success ->
            when (result.value.operatingSystem) {
                HostOperatingSystem.MACOS -> "darwin-x86_64"
                HostOperatingSystem.LINUX -> "linux-x86_64"
                HostOperatingSystem.WINDOWS -> "windows-x86_64"
            }
        is StudioResult.Failure -> "unsupported-host"
    }
