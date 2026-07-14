package com.androidperformancestudio.toolchain

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SystemHostPlatformDetectorTest {
    @Test
    fun `detects Apple Silicon macOS`() {
        val detector = SystemHostPlatformDetector(osName = { "Mac OS X" }, osArch = { "aarch64" })

        val result = assertIs<StudioResult.Success<HostPlatform>>(detector.detect())

        assertEquals(HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64), result.value)
        assertEquals("macos-arm64", result.value.resourceDirectory)
    }

    @Test
    fun `detects x64 Windows`() {
        val detector = SystemHostPlatformDetector(osName = { "Windows 11" }, osArch = { "amd64" })

        val result = assertIs<StudioResult.Success<HostPlatform>>(detector.detect())

        assertEquals(HostPlatform(HostOperatingSystem.WINDOWS, CpuArchitecture.X64), result.value)
    }

    @Test
    fun `rejects unsupported architectures with a structured error`() {
        val detector = SystemHostPlatformDetector(osName = { "Linux" }, osArch = { "riscv64" })

        val result = assertIs<StudioResult.Failure>(detector.detect())

        assertEquals(ErrorCategory.UNSUPPORTED_PLATFORM, result.error.category)
        assertEquals("UNSUPPORTED_ARCHITECTURE", result.error.code)
    }
}
