package com.androidperformancestudio.gpu.model

import java.nio.file.Path
import java.time.Instant
import java.util.UUID

public enum class GpuArtifactKind { AGI_SYSTEM_PROFILE, AGI_FRAME_PROFILE, PERFETTO_TRACE, SCREENSHOT, EXTERNAL_REPORT, UNKNOWN }

public enum class GraphicsApi { VULKAN, OPENGL_ES, WEBGPU, UNKNOWN }

public enum class ArtifactOpenRoute { AGI, PERFETTO, DESKTOP, NONE }

public enum class ArtifactLocationStatus { AVAILABLE, MISSING, SIZE_CHANGED }

public enum class AgiLaunchMode { VERIFIED_CLI, GUI_ONLY, UNSUPPORTED }

public data class AgiCapability(
    val executable: Path?,
    val version: String?,
    val launchSupported: Boolean,
    val artifactOpenSupported: Boolean,
    val launchMode: AgiLaunchMode,
    val supportedArguments: Set<String>,
    val warnings: List<String>,
)

public data class GpuDeviceContext(
    val serial: String?,
    val model: String?,
    val apiLevel: Int?,
    val gpuVendor: String?,
    val gpuRenderer: String?,
    val driverVersion: String?,
    val evidenceSources: Map<String, String> = emptyMap(),
)

public data class GraphicsImplementationContext(
    val name: String?,
    val version: String?,
    val backendApi: GraphicsApi?,
    val evidenceSource: String?,
)

public data class GpuCaptureContext(
    val id: String = UUID.randomUUID().toString(),
    val device: GpuDeviceContext?,
    val packageName: String?,
    val graphicsApi: GraphicsApi,
    val graphicsImplementation: GraphicsImplementationContext? = null,
    val frameCapture: Boolean,
    val createdAt: Instant = Instant.now(),
    val warnings: List<String> = emptyList(),
)

public data class GpuArtifact(
    val id: String = UUID.randomUUID().toString(),
    val kind: GpuArtifactKind,
    val path: Path,
    val sha256: String,
    val sizeBytes: Long,
    val agiVersion: String?,
    val device: GpuDeviceContext?,
    val packageName: String?,
    val graphicsApi: GraphicsApi?,
    val graphicsImplementation: GraphicsImplementationContext? = null,
    val capturedAt: Instant?,
    val importedAt: Instant = Instant.now(),
    val notes: String?,
    val openRoute: ArtifactOpenRoute,
    val alternativePaths: List<Path> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    public val locations: List<Path>
        get() = (listOf(path) + alternativePaths).distinct()
}
