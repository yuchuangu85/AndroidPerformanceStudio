package com.androidperformancestudio.adb

import com.androidperformancestudio.platform.adb.AdbExecutableLocator
import com.androidperformancestudio.platform.adb.AdbLocationSource
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AdbExecutableResolverTest {
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
            executable = "/definitely/missing/adb",
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
}
