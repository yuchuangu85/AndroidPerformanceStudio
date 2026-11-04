package com.androidperformancestudio.adb

import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbCommandExecutor
import com.androidperformancestudio.platform.adb.AdbExecutableLocator
import com.androidperformancestudio.platform.adb.AdbLocationSource
import com.androidperformancestudio.platform.adb.AdbProcessStartException
import com.androidperformancestudio.platform.adb.AdbTextResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AdbExecutableResolverTest {
    @Test
    fun `default process runner uses the shared adb executable in diagnostics`() {
        val executable = defaultAdbCommandExecutable()
        val result = AdbProcessRunner(delegate = FailingAdbCommandExecutor()).run(listOf("devices", "-l"))

        assertEquals(AdbProcessRunner.COMMAND_NOT_FOUND_EXIT_CODE, result.exitCode)
        assertTrue(result.stderr.startsWith("ADB executable not found: $executable. "), result.stderr)
    }

    @Test
    fun `process runner preserves an explicitly supplied adb executable`() {
        val executable = Path.of("/custom Android SDK/platform-tools/adb")
        val result = AdbProcessRunner(
            executable = executable,
            delegate = FailingAdbCommandExecutor(),
        ).run(listOf("devices", "-l"))

        assertEquals(AdbProcessRunner.COMMAND_NOT_FOUND_EXIT_CODE, result.exitCode)
        assertTrue(result.stderr.startsWith("ADB executable not found: $executable. "), result.stderr)
    }

    @Test
    fun `resolves adb from Android SDK home when GUI app has no useful PATH`(@TempDir sdk: Path) {
        val adb = createExecutable(sdk.resolve("platform-tools").resolve(adbFileName(osName = "Mac OS X")))

        val resolved = AdbExecutableLocator(
            environment = mapOf("ANDROID_HOME" to sdk.toString()),
            userHome = Path.of("/missing-home"),
            osName = "Mac OS X",
            pathSeparator = ":",
        ).locate()

        assertEquals(adb, resolved.executable)
        assertEquals(AdbLocationSource.ANDROID_HOME, resolved.source)
    }

    @Test
    fun `resolves adb from default macOS Android Studio SDK location`(@TempDir home: Path) {
        val adb = createExecutable(
            home.resolve("Library")
                .resolve("Android")
                .resolve("sdk")
                .resolve("platform-tools")
                .resolve(adbFileName(osName = "Mac OS X")),
        )

        val resolved = AdbExecutableLocator(
            environment = emptyMap(),
            userHome = home,
            osName = "Mac OS X",
            pathSeparator = ":",
        ).locate()

        assertEquals(adb, resolved.executable)
        assertEquals(AdbLocationSource.DEFAULT_SDK, resolved.source)
    }

    @Test
    fun `missing executable becomes actionable process result instead of IOException`() {
        val result = AdbProcessRunner(
            executable = Path.of("/definitely/missing/adb"),
            timeoutMillis = 50,
        ).run(listOf("devices", "-l"))

        assertEquals(AdbProcessRunner.COMMAND_NOT_FOUND_EXIT_CODE, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("ADB executable not found"), result.stderr)
        assertTrue(result.stderr.contains("ANDROID_HOME"), result.stderr)
    }

    private fun createExecutable(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "#!/bin/sh\n")
        path.toFile().setExecutable(true)
        return path
    }

    private fun adbFileName(osName: String): String =
        if (osName.lowercase().contains("windows")) "adb.exe" else "adb"

    private class FailingAdbCommandExecutor : AdbCommandExecutor {
        override suspend fun executeText(
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = error("Not used")

        override suspend fun executeBinary(
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbBinaryResult = throw AdbProcessStartException(arguments, IOException("Simulated process start failure"))
    }
}
