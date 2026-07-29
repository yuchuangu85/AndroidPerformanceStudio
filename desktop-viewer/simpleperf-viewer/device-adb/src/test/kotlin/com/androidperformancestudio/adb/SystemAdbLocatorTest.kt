package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.CpuArchitecture
import com.androidperformancestudio.toolchain.HostOperatingSystem
import com.androidperformancestudio.toolchain.HostPlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SystemAdbLocatorTest {
    @Test
    fun `user configured adb has highest priority`() {
        withTempDirectory { root ->
            val configured = createAdb(root.resolve("configured"), "adb")
            createAdb(root.resolve("sdk/platform-tools"), "adb")
            val locator = locator(root, mapOf("ANDROID_HOME" to root.resolve("sdk").toString()))

            val result =
                assertIs<StudioResult.Success<AdbLocation>>(
                    locator.locate(AdbConfiguration(userConfiguredPath = configured)),
                )

            assertEquals(configured, result.value.executable)
            assertEquals(AdbLocationSource.USER_CONFIGURATION, result.value.source)
        }
    }

    @Test
    fun `invalid user configuration fails instead of silently falling back`() {
        withTempDirectory { root ->
            createAdb(root.resolve("sdk/platform-tools"), "adb")
            val locator = locator(root, mapOf("ANDROID_HOME" to root.resolve("sdk").toString()))

            val result =
                assertIs<StudioResult.Failure>(
                    locator.locate(AdbConfiguration(userConfiguredPath = root.resolve("missing-adb"))),
                )

            assertEquals(ErrorCategory.CONFIGURATION, result.error.category)
            assertEquals("ADB_CONFIGURED_PATH_INVALID", result.error.code)
        }
    }

    @Test
    fun `user configured sdk resolves platform tools adb`() {
        withTempDirectory { root ->
            val sdk = root.resolve("custom-sdk")
            val adb = createAdb(sdk.resolve("platform-tools"), "adb.exe")
            val locator =
                SystemAdbLocator(
                    platform = HostPlatform(HostOperatingSystem.WINDOWS, CpuArchitecture.X64),
                    environment = emptyMap(),
                    userHome = root,
                    pathSeparator = ";",
                    isUsableExecutable = Files::isRegularFile,
                )

            val result =
                assertIs<StudioResult.Success<AdbLocation>>(
                    locator.locate(AdbConfiguration(androidSdkPath = sdk)),
                )

            assertEquals(adb, result.value.executable)
            assertEquals(AdbLocationSource.USER_CONFIGURATION, result.value.source)
        }
    }

    @Test
    fun `android home is preferred over path`() {
        withTempDirectory { root ->
            val androidHomeAdb = createAdb(root.resolve("sdk/platform-tools"), "adb")
            val pathDirectory = root.resolve("path-bin")
            createAdb(pathDirectory, "adb")
            val locator =
                locator(
                    root,
                    mapOf(
                        "ANDROID_HOME" to root.resolve("sdk").toString(),
                        "PATH" to pathDirectory.toString(),
                    ),
                )

            val result = assertIs<StudioResult.Success<AdbLocation>>(locator.locate())

            assertEquals(androidHomeAdb, result.value.executable)
            assertEquals(AdbLocationSource.ANDROID_HOME, result.value.source)
        }
    }

    @Test
    fun `macos default sdk directory is discovered without shell path`() {
        withTempDirectory { root ->
            val defaultAdb = createAdb(root.resolve("Library/Android/sdk/platform-tools"), "adb")
            val locator = locator(root, emptyMap())

            val result = assertIs<StudioResult.Success<AdbLocation>>(locator.locate())

            assertEquals(defaultAdb, result.value.executable)
            assertEquals(AdbLocationSource.DEFAULT_SDK, result.value.source)
        }
    }

    @Test
    fun `windows searches for adb exe using windows path separator`() {
        withTempDirectory { root ->
            val secondPath = root.resolve("second-bin")
            val adb = createAdb(secondPath, "adb.exe")
            val locator =
                SystemAdbLocator(
                    platform = HostPlatform(HostOperatingSystem.WINDOWS, CpuArchitecture.X64),
                    environment = mapOf("PATH" to "${root.resolve("first-bin")};$secondPath"),
                    userHome = root,
                    pathSeparator = ";",
                    isUsableExecutable = Files::isRegularFile,
                )

            val result = assertIs<StudioResult.Success<AdbLocation>>(locator.locate())

            assertEquals(adb, result.value.executable)
            assertEquals(AdbLocationSource.PATH, result.value.source)
        }
    }

    @Test
    fun `windows resolves case insensitive environment names and expanded sdk variables`() {
        withTempDirectory { root ->
            val localAppData = root.resolve("Local App Data")
            val adb = createAdb(localAppData.resolve("Android/Sdk/platform-tools"), "adb.exe")
            val locator =
                SystemAdbLocator(
                    platform = HostPlatform(HostOperatingSystem.WINDOWS, CpuArchitecture.X64),
                    environment =
                        mapOf(
                            "localappdata" to localAppData.toString(),
                            "android_sdk_root" to "%LOCALAPPDATA%/Android/Sdk",
                        ),
                    userHome = root,
                    pathSeparator = ";",
                    isUsableExecutable = Files::isRegularFile,
                )

            val result = assertIs<StudioResult.Success<AdbLocation>>(locator.locate())

            assertEquals(adb, result.value.executable)
            assertEquals(AdbLocationSource.ANDROID_SDK_ROOT, result.value.source)
        }
    }

    @Test
    fun `windows accepts quoted path entries containing spaces`() {
        withTempDirectory { root ->
            val platformTools = root.resolve("Android SDK/platform-tools")
            val adb = createAdb(platformTools, "adb.exe")
            val locator =
                SystemAdbLocator(
                    platform = HostPlatform(HostOperatingSystem.WINDOWS, CpuArchitecture.X64),
                    environment = mapOf("Path" to "\"$platformTools\""),
                    userHome = root,
                    pathSeparator = ";",
                    isUsableExecutable = Files::isRegularFile,
                )

            val result = assertIs<StudioResult.Success<AdbLocation>>(locator.locate())

            assertEquals(adb, result.value.executable)
            assertEquals(AdbLocationSource.PATH, result.value.source)
        }
    }

    private fun locator(
        userHome: Path,
        environment: Map<String, String>,
    ): SystemAdbLocator =
        SystemAdbLocator(
            platform = HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64),
            environment = environment,
            userHome = userHome,
            isUsableExecutable = Files::isRegularFile,
        )

    private fun createAdb(
        directory: Path,
        executableName: String,
    ): Path {
        directory.createDirectories()
        return directory.resolve(executableName).createFile()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("aps-adb-locator-")
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
