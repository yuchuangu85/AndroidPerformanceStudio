package com.androidperformancestudio.platform.toolchain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolchainManifestTest {
    private val macArm64 = HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64)

    @Test
    fun `tool support is constrained by declared platforms`() {
        val tool =
            ToolDescriptor(
                id = "simpleperf",
                version = "1.0",
                executable = "simpleperf",
                sha256 = "0".repeat(64),
                source = "https://android.googlesource.com/",
                license = "Apache-2.0",
                supportedPlatforms = setOf(macArm64),
            )

        assertTrue(tool.supports(macArm64))
        assertFalse(tool.supports(HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)))
    }
}
