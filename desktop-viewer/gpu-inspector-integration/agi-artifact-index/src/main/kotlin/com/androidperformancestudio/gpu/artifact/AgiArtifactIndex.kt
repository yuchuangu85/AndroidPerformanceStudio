@file:Suppress("LongMethod")

package com.androidperformancestudio.gpu.artifact

import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuCaptureContext
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.model.GraphicsApi
import com.androidperformancestudio.gpu.model.GraphicsImplementationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Instant

public data class ArtifactLocationResolution(
    val status: ArtifactLocationStatus,
    val path: Path?,
)

public class AgiArtifactIndexer(
    private val maxBytes: Long = 8L * 1024L * 1024L * 1024L,
) {
    public fun import(
        path: Path,
        context: GpuCaptureContext? = null,
        agiVersion: String? = null,
        notes: String? = null,
    ): GpuArtifact {
        require(Files.isRegularFile(path)) { "Artifact does not exist: $path" }
        val size = Files.size(path)
        require(size <= maxBytes) { "Artifact exceeds the configured $maxBytes byte limit" }
        val kind = detectKind(path)
        return GpuArtifact(
            kind = kind,
            path = path.toAbsolutePath().normalize(),
            sha256 = sha256(path),
            sizeBytes = size,
            agiVersion = agiVersion,
            device = context?.device,
            packageName = context?.packageName,
            graphicsApi = context?.graphicsApi,
            graphicsImplementation = context?.graphicsImplementation,
            capturedAt = Files.getLastModifiedTime(path).toInstant(),
            notes = notes,
            openRoute =
                when (kind) {
                    GpuArtifactKind.PERFETTO_TRACE -> ArtifactOpenRoute.PERFETTO
                    GpuArtifactKind.AGI_FRAME_PROFILE, GpuArtifactKind.AGI_SYSTEM_PROFILE -> ArtifactOpenRoute.AGI
                    GpuArtifactKind.SCREENSHOT, GpuArtifactKind.EXTERNAL_REPORT -> ArtifactOpenRoute.DESKTOP
                    GpuArtifactKind.UNKNOWN -> ArtifactOpenRoute.NONE
                },
            warnings =
                if (kind == GpuArtifactKind.UNKNOWN) {
                    listOf("Unknown artifact format; it is indexed as opaque evidence.")
                } else {
                    emptyList()
                },
        )
    }

    public fun resolveLocation(artifact: GpuArtifact): ArtifactLocationResolution {
        var foundRegularFile = false
        artifact.locations.forEach { path ->
            val size = runCatching { if (Files.isRegularFile(path)) Files.size(path) else null }.getOrNull()
            if (size != null) {
                foundRegularFile = true
                if (size == artifact.sizeBytes) {
                    return ArtifactLocationResolution(ArtifactLocationStatus.AVAILABLE, path)
                }
            }
        }
        return ArtifactLocationResolution(
            if (foundRegularFile) ArtifactLocationStatus.SIZE_CHANGED else ArtifactLocationStatus.MISSING,
            null,
        )
    }

    public fun verify(artifact: GpuArtifact): Boolean =
        resolveLocation(artifact).path?.let { path -> sha256(path) == artifact.sha256 } == true

    public fun mergeLocation(
        artifacts: List<GpuArtifact>,
        imported: GpuArtifact,
    ): List<GpuArtifact> {
        val existing = artifacts.firstOrNull { it.sha256 == imported.sha256 } ?: return listOf(imported) + artifacts
        val merged =
            existing.copy(
                path = imported.path,
                alternativePaths =
                    (imported.alternativePaths + existing.locations)
                        .filter { it != imported.path }
                        .distinct(),
                importedAt = imported.importedAt,
            )
        return listOf(merged) + artifacts.filterNot { it.id == existing.id }
    }

    public fun relocate(
        artifact: GpuArtifact,
        newPath: Path,
    ): GpuArtifact {
        require(Files.isRegularFile(newPath)) { "Artifact does not exist: $newPath" }
        require(Files.size(newPath) == artifact.sizeBytes) { "Selected file size does not match the indexed artifact" }
        require(sha256(newPath) == artifact.sha256) { "Selected file content does not match the indexed artifact" }
        val normalized = newPath.toAbsolutePath().normalize()
        return artifact.copy(
            path = normalized,
            alternativePaths = artifact.locations.filter { it != normalized }.distinct(),
        )
    }

    private fun detectKind(path: Path): GpuArtifactKind {
        val name = path.fileName.toString().lowercase()
        val header = Files.newInputStream(path).use { input -> ByteArray(32).also { input.read(it) }.decodeToString() }
        return when {
            name.endsWith(".perfetto-trace") || name.endsWith(".pftrace") || header.contains("PERFETTO") -> GpuArtifactKind.PERFETTO_TRACE
            name.endsWith(".gfxtrace") || name.endsWith(".agi") -> GpuArtifactKind.AGI_FRAME_PROFILE
            name.endsWith(".trace") -> GpuArtifactKind.AGI_SYSTEM_PROFILE
            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") -> GpuArtifactKind.SCREENSHOT
            name.endsWith(".html") || name.endsWith(".pdf") -> GpuArtifactKind.EXTERNAL_REPORT
            else -> GpuArtifactKind.UNKNOWN
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

public class JsonAgiArtifactStore(
    private val indexFile: Path,
) {
    private val json = Json { prettyPrint = true }

    public fun load(): List<GpuArtifact> {
        if (!Files.isRegularFile(indexFile)) return emptyList()
        try {
            val root = json.parseToJsonElement(Files.readString(indexFile))
            val artifacts = if (root is JsonArray) root else root.jsonObject["artifacts"]!!.jsonArray
            return artifacts.map { element -> decodeArtifact(element.jsonObject) }
        } catch (error: Exception) {
            throw IllegalStateException("Unable to read GPU artifact index: $indexFile", error)
        }
    }

    public fun save(artifacts: List<GpuArtifact>) {
        val absoluteFile = indexFile.toAbsolutePath()
        val parent = requireNotNull(absoluteFile.parent)
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "${absoluteFile.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                json.encodeToString(
                    buildJsonObject {
                        put("schemaVersion", 2)
                        put("artifacts", buildJsonArray { artifacts.forEach { add(encodeArtifact(it)) } })
                    },
                ),
            )
            try {
                Files.move(temporary, absoluteFile, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absoluteFile, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun encodeArtifact(artifact: GpuArtifact): JsonObject =
        buildJsonObject {
            put("id", artifact.id)
            put("kind", artifact.kind.name)
            put("path", artifact.path.toString())
            put("alternativePaths", buildJsonArray { artifact.alternativePaths.forEach { add(JsonPrimitive(it.toString())) } })
            put("sha256", artifact.sha256)
            put("sizeBytes", artifact.sizeBytes)
            artifact.agiVersion?.let { put("agiVersion", it) }
            artifact.device?.let { put("device", encodeDevice(it)) }
            artifact.packageName?.let { put("packageName", it) }
            artifact.graphicsApi?.let { put("graphicsApi", it.name) }
            artifact.graphicsImplementation?.let { put("graphicsImplementation", encodeGraphicsImplementation(it)) }
            artifact.capturedAt?.let { put("capturedAt", it.toString()) }
            put("importedAt", artifact.importedAt.toString())
            artifact.notes?.let { put("notes", it) }
            put("openRoute", artifact.openRoute.name)
            put("warnings", buildJsonArray { artifact.warnings.forEach { add(JsonPrimitive(it)) } })
        }

    private fun decodeArtifact(value: JsonObject): GpuArtifact {
        val rawGraphicsApi = value["graphicsApi"]?.jsonPrimitive?.contentOrNull
        return GpuArtifact(
            id = value["id"]!!.jsonPrimitive.content,
            kind = GpuArtifactKind.valueOf(value["kind"]!!.jsonPrimitive.content),
            path = Path.of(value["path"]!!.jsonPrimitive.content),
            alternativePaths = value["alternativePaths"]?.jsonArray?.map { Path.of(it.jsonPrimitive.content) }.orEmpty(),
            sha256 = value["sha256"]!!.jsonPrimitive.content,
            sizeBytes = value["sizeBytes"]!!.jsonPrimitive.longOrNull ?: 0,
            agiVersion = value["agiVersion"]?.jsonPrimitive?.contentOrNull,
            device = value["device"]?.jsonObject?.let(::decodeDevice),
            packageName = value["packageName"]?.jsonPrimitive?.contentOrNull,
            graphicsApi = rawGraphicsApi?.let(::decodeGraphicsApi),
            graphicsImplementation =
                value["graphicsImplementation"]?.jsonObject?.let(::decodeGraphicsImplementation)
                    ?: rawGraphicsApi?.takeIf { it == "OPENGL_ON_ANGLE" }?.let {
                        GraphicsImplementationContext("ANGLE", null, GraphicsApi.VULKAN, "legacy graphicsApi")
                    },
            capturedAt = value["capturedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
            importedAt = value["importedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse) ?: Instant.now(),
            notes = value["notes"]?.jsonPrimitive?.contentOrNull,
            openRoute =
                ArtifactOpenRoute.valueOf(
                    value["openRoute"]?.jsonPrimitive?.contentOrNull
                        ?: value["openCapability"]!!.jsonPrimitive.content,
                ),
            warnings = value["warnings"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
    }

    private fun encodeDevice(device: GpuDeviceContext): JsonObject =
        buildJsonObject {
            device.serial?.let { put("serial", it) }
            device.model?.let { put("model", it) }
            device.apiLevel?.let { put("apiLevel", it) }
            device.gpuVendor?.let { put("gpuVendor", it) }
            device.gpuRenderer?.let { put("gpuRenderer", it) }
            device.driverVersion?.let { put("driverVersion", it) }
            put("evidenceSources", buildJsonObject { device.evidenceSources.forEach { (field, source) -> put(field, source) } })
        }

    private fun decodeDevice(value: JsonObject): GpuDeviceContext =
        GpuDeviceContext(
            serial = value["serial"]?.jsonPrimitive?.contentOrNull,
            model = value["model"]?.jsonPrimitive?.contentOrNull,
            apiLevel = value["apiLevel"]?.jsonPrimitive?.intOrNull,
            gpuVendor = value["gpuVendor"]?.jsonPrimitive?.contentOrNull,
            gpuRenderer = value["gpuRenderer"]?.jsonPrimitive?.contentOrNull,
            driverVersion = value["driverVersion"]?.jsonPrimitive?.contentOrNull,
            evidenceSources = value["evidenceSources"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
        )

    private fun encodeGraphicsImplementation(implementation: GraphicsImplementationContext): JsonObject =
        buildJsonObject {
            implementation.name?.let { put("name", it) }
            implementation.version?.let { put("version", it) }
            implementation.backendApi?.let { put("backendApi", it.name) }
            implementation.evidenceSource?.let { put("evidenceSource", it) }
        }

    private fun decodeGraphicsImplementation(value: JsonObject): GraphicsImplementationContext =
        GraphicsImplementationContext(
            name = value["name"]?.jsonPrimitive?.contentOrNull,
            version = value["version"]?.jsonPrimitive?.contentOrNull,
            backendApi = value["backendApi"]?.jsonPrimitive?.contentOrNull?.let(::decodeGraphicsApi),
            evidenceSource = value["evidenceSource"]?.jsonPrimitive?.contentOrNull,
        )

    private fun decodeGraphicsApi(value: String): GraphicsApi =
        if (value == "OPENGL_ON_ANGLE") GraphicsApi.OPENGL_ES else GraphicsApi.valueOf(value)
}
