package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativePackagingArchitectureTest {
    private val buildScript = Files.readString(Path.of("build.gradle.kts"))

    @Test
    fun `desktop runtime dependencies are explicit for supported release architectures`() {
        assertTrue(buildScript.contains("compose.desktop.macos_arm64"))
        assertTrue(buildScript.contains("compose.desktop.macos_x64"))
        assertTrue(buildScript.contains("compose.desktop.windows_x64"))
        assertTrue(buildScript.contains("compose.desktop.linux_x64"))
    }

    @Test
    fun `packaging JDK is configurable without a developer-specific path`() {
        assertTrue(buildScript.contains("target.javaHome"))
        assertTrue(buildScript.contains("Cross-architecture packaging requires -Ptarget.javaHome"))
        assertFalse(buildScript.contains("System.getProperty(\"user.home\")"))
        assertFalse(buildScript.contains("/Downloads/zulu"))
    }

    @Test
    fun `unsupported 32 bit Windows target fails instead of publishing a mislabeled installer`() {
        assertTrue(buildScript.contains("Windows x86 is not supported"))
        assertTrue(buildScript.contains("targetArch == \"x64\" || targetArch == \"arm64\""))
    }
}
