@file:Suppress("LongMethod")

package com.androidperformancestudio.gpu.artifact

import com.androidperformancestudio.gpu.model.ArtifactOpenCapability
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuCaptureContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

public class AgiArtifactIndexer(
    private val maxBytes: Long = 8L * 1024L * 1024L * 1024L,
) {
    public fun import(path: Path, context: GpuCaptureContext? = null, agiVersion: String? = null, notes: String? = null): GpuArtifact {
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
            capturedAt = Files.getLastModifiedTime(path).toInstant(),
            notes = notes,
            openCapability = when (kind) {
                GpuArtifactKind.PERFETTO_TRACE -> ArtifactOpenCapability.PERFETTO
                GpuArtifactKind.AGI_FRAME_PROFILE, GpuArtifactKind.AGI_SYSTEM_PROFILE, GpuArtifactKind.UNKNOWN -> ArtifactOpenCapability.AGI
                GpuArtifactKind.SCREENSHOT, GpuArtifactKind.EXTERNAL_REPORT -> ArtifactOpenCapability.DESKTOP
            },
            warnings = if (kind == GpuArtifactKind.UNKNOWN) listOf("Unknown artifact format; it is indexed as opaque evidence.") else emptyList(),
        )
    }

    public fun verify(artifact: GpuArtifact): Boolean = Files.isRegularFile(artifact.path) && Files.size(artifact.path) == artifact.sizeBytes && sha256(artifact.path) == artifact.sha256

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
        return runCatching {
            json.parseToJsonElement(Files.readString(indexFile)).jsonArray.map { element ->
                val value = element.jsonObject
                GpuArtifact(
                    id = value["id"]!!.jsonPrimitive.content,
                    kind = GpuArtifactKind.valueOf(value["kind"]!!.jsonPrimitive.content),
                    path = Path.of(value["path"]!!.jsonPrimitive.content),
                    sha256 = value["sha256"]!!.jsonPrimitive.content,
                    sizeBytes = value["sizeBytes"]!!.jsonPrimitive.longOrNull ?: 0,
                    agiVersion = value["agiVersion"]?.jsonPrimitive?.contentOrNull,
                    device = null,
                    packageName = value["packageName"]?.jsonPrimitive?.contentOrNull,
                    graphicsApi = null,
                    capturedAt = value["capturedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
                    importedAt = value["importedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse) ?: Instant.now(),
                    notes = value["notes"]?.jsonPrimitive?.contentOrNull,
                    openCapability = ArtifactOpenCapability.valueOf(value["openCapability"]!!.jsonPrimitive.content),
                    warnings = (value["warnings"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
            }
        }.getOrElse { emptyList() }
    }

    public fun save(artifacts: List<GpuArtifact>) {
        indexFile.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(
            indexFile,
            json.encodeToString(
                buildJsonArray {
                    artifacts.forEach { artifact ->
                        add(
                            buildJsonObject {
                                put("id", artifact.id)
                                put("kind", artifact.kind.name)
                                put("path", artifact.path.toString())
                                put("sha256", artifact.sha256)
                                put("sizeBytes", artifact.sizeBytes)
                                artifact.agiVersion?.let { put("agiVersion", it) }
                                artifact.packageName?.let { put("packageName", it) }
                                artifact.capturedAt?.let { put("capturedAt", it.toString()) }
                                put("importedAt", artifact.importedAt.toString())
                                artifact.notes?.let { put("notes", it) }
                                put("openCapability", artifact.openCapability.name)
                                put("warnings", buildJsonArray { artifact.warnings.forEach { warning -> add(JsonPrimitive(warning)) } })
                            },
                        )
                    }
                },
            ),
        )
    }
}
