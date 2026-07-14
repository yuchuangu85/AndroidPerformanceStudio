package com.androidperformancestudio.toolchain

data class ToolchainManifest(
    val schemaVersion: Int,
    val tools: List<ToolDescriptor>,
)

data class ToolDescriptor(
    val id: String,
    val version: String,
    val executable: String,
    val sha256: String,
    val source: String,
    val license: String,
    val supportedPlatforms: Set<HostPlatform>,
) {
    fun supports(platform: HostPlatform): Boolean = platform in supportedPlatforms
}
