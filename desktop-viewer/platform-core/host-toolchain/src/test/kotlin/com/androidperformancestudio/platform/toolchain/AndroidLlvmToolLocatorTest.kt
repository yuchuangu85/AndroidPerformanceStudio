package com.androidperformancestudio.platform.toolchain

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidLlvmToolLocatorTest {
    @Test
    fun `configured NDK environment SDK versions and PATH have deterministic precedence`() {
        val root = Files.createTempDirectory("aps-llvm-locator-")
        val configured = root.resolve("configured")
        val environmentNdk = root.resolve("environment")
        val sdk = root.resolve("sdk")
        val pathDirectory = root.resolve("path").createDirectories()
        val configuredBin = ndkBin(configured)
        val environmentBin = ndkBin(environmentNdk)
        val olderSdkBin = ndkBin(sdk.resolve("ndk/26.1.1"))
        val newerSdkBin = ndkBin(sdk.resolve("ndk/27.2.2"))
        listOf(configuredBin, environmentBin, olderSdkBin, newerSdkBin, pathDirectory).forEach(::createTools)
        val locator =
            AndroidLlvmToolLocator(
                configuredNdk = configured,
                environment =
                    mapOf(
                        "ANDROID_NDK_HOME" to environmentNdk.toString(),
                        "ANDROID_SDK_ROOT" to sdk.toString(),
                        "PATH" to pathDirectory.toString(),
                    ),
                hostTag = HOST_TAG,
            )

        assertLocatedIn(locator, configuredBin)
        deleteTools(configuredBin)
        assertLocatedIn(locator, environmentBin)
        deleteTools(environmentBin)
        assertLocatedIn(locator, newerSdkBin)
        deleteTools(newerSdkBin)
        deleteTools(olderSdkBin)
        assertLocatedIn(locator, pathDirectory)
    }
}

private fun assertLocatedIn(
    locator: AndroidLlvmToolLocator,
    directory: Path,
) {
    AndroidLlvmTool.entries.forEach { tool ->
        assertEquals(directory.resolve(tool.executableName), locator.locate(tool))
    }
}

private fun ndkBin(ndk: Path): Path = ndk.resolve("toolchains/llvm/prebuilt/$HOST_TAG/bin").createDirectories()

private fun createTools(directory: Path) {
    AndroidLlvmTool.entries.forEach { tool -> directory.resolve(tool.executableName).createFile() }
}

private fun deleteTools(directory: Path) {
    AndroidLlvmTool.entries.forEach { tool -> Files.delete(directory.resolve(tool.executableName)) }
}

private const val HOST_TAG = "test-host"
