package com.androidperformancestudio.platform.adb

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdbExecutableLocatorTest {
    @Test
    fun `explicit executable wins over sdk and path`() {
        val root = Files.createTempDirectory("adb-locator")
        val explicit = createExecutable(root.resolve("configured/adb"))
        val sdkAdb = createExecutable(root.resolve("sdk/platform-tools/adb"))
        val pathAdb = createExecutable(root.resolve("bin/adb"))
        try {
            val result =
                locator(
                    root,
                    mapOf(
                        "ANDROID_HOME" to sdkAdb.parent.parent.toString(),
                        "PATH" to pathAdb.parent.toString(),
                    ),
                ).locate(AdbLocatorConfiguration(executablePath = explicit))

            assertEquals(explicit, result.executable)
            assertEquals(AdbLocationSource.EXPLICIT_EXECUTABLE, result.source)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid explicit executable does not silently fall back`() {
        val root = Files.createTempDirectory("adb-locator")
        try {
            assertFailsWith<AdbNotExecutableException> {
                locator(root, emptyMap())
                    .locate(AdbLocatorConfiguration(executablePath = root.resolve("missing")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `windows environment lookup is case insensitive and expands variables`() {
        val root = Files.createTempDirectory("adb-locator")
        val localAppData = root.resolve("Local App Data")
        val adb = createExecutable(localAppData.resolve("Android/Sdk/platform-tools/adb.exe"))
        try {
            val result =
                AdbExecutableLocator(
                    environment =
                        mapOf(
                            "localappdata" to localAppData.toString(),
                            "android_sdk_root" to "%LOCALAPPDATA%/Android/Sdk",
                        ),
                    userHome = root,
                    osName = "Windows 11",
                    pathSeparator = ";",
                    isUsableExecutable = Files::isRegularFile,
                ).locate()

            assertEquals(adb, result.executable)
            assertEquals(AdbLocationSource.ANDROID_SDK_ROOT, result.source)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun locator(
        root: Path,
        environment: Map<String, String>,
    ): AdbExecutableLocator =
        AdbExecutableLocator(
            environment = environment,
            userHome = root,
            osName = "Mac OS X",
            pathSeparator = ":",
            isUsableExecutable = Files::isRegularFile,
        )

    private fun createExecutable(path: Path): Path {
        Files.createDirectories(path.parent)
        return Files.createFile(path)
    }
}
