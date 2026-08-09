package com.androidperformancestudio.platform.toolchain

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SystemHostPlatformDetectorTest {
    @Test
    fun `detects supported host and reports unsupported architecture`() {
        val supported = SystemHostPlatformDetector(osName = { "Mac OS X" }, osArch = { "aarch64" }).detect()
        val unsupported = SystemHostPlatformDetector(osName = { "Linux" }, osArch = { "riscv64" }).detect()

        assertEquals(
            HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64),
            assertIs<StudioResult.Success<HostPlatform>>(supported).value,
        )
        assertEquals(
            ErrorCategory.UNSUPPORTED_PLATFORM,
            assertIs<StudioResult.Failure>(unsupported).error.category,
        )
    }
}
