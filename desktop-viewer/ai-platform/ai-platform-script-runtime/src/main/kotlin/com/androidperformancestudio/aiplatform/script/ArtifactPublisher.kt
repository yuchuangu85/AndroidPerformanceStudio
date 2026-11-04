package com.androidperformancestudio.aiplatform.script

import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.requireStableId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

@Serializable
public data class ArtifactProvenance(
    val sessionId: String,
    val generation: Long,
    val agentId: String,
    val agentVersion: String,
    val evidenceIds: Set<String>,
    val candidateIds: Set<String>,
    val inputHash: String,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(sessionId.length in 1..128 && generation >= 0)
        requireStableId(agentId)
        require(agentVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(evidenceIds.size <= 100 && candidateIds.size <= 100)
        require((evidenceIds + candidateIds).all { it.length in 1..256 })
        require(inputHash.matches(Regex("[a-f0-9]{64}")))
        require(warnings.size <= 32 && warnings.all { it.length <= 2048 })
    }
}

@Serializable
public data class ArtifactSection(
    val heading: String,
    val text: String,
) {
    init {
        require(heading.length <= 256 && text.length <= 32_768)
    }
}

public data class ArtifactRequest(
    val title: String,
    val sections: List<ArtifactSection>,
    val provenance: ArtifactProvenance,
    val grantedPermissions: Set<Permission>,
    val allowedEvidenceIds: Set<String>,
    val allowedCandidateIds: Set<String>,
    val format: ArtifactFormat = ArtifactFormat.HTML,
) {
    init {
        require(title.length <= 256 && sections.size <= 64)
    }
}

@Serializable
public data class PublishedArtifact(
    val hash: String,
    val contentHash: String,
    val format: ArtifactFormat,
    val byteCount: Int,
    val provenance: ArtifactProvenance,
)

/** No raw HTML/JS input API. Provider text becomes escaped text inside a fixed inert document. */
public class ArtifactPublisher(
    root: Path,
    private val maxBytes: Int = 262_144,
) {
    private val root = root.toAbsolutePath().normalize()

    init {
        require(maxBytes in 1..1_048_576)
    }

    @Synchronized
    public fun publish(request: ArtifactRequest): PublishedArtifact =
        sanitized {
            scriptRequire(Permission.WRITE_ARTIFACT in request.grantedPermissions, "E_ARTIFACT_PERMISSION")
            scriptRequire(request.provenance.evidenceIds.all { it in request.allowedEvidenceIds }, "E_ARTIFACT_REFERENCE")
            scriptRequire(request.provenance.candidateIds.all { it in request.allowedCandidateIds }, "E_ARTIFACT_REFERENCE")
            val content = renderArtifact(request).toByteArray()
            scriptRequire(content.size <= maxBytes, "E_ARTIFACT_LIMIT")
            val contentHash = digest(content)
            val identity = PlatformJson.codec.encodeToString(request.provenance).toByteArray() + request.format.name.toByteArray() + content
            val hash = digest(identity)
            val published = PublishedArtifact(hash, contentHash, request.format, content.size, request.provenance)
            privateDirectory(root)
            val destination = root.resolve(hash)
            checkPath(destination)
            if (Files.exists(destination)) {
                scriptRequire(read(hash).contentEquals(content), "E_ARTIFACT_HASH")
                return@sanitized published
            }
            val staging = Files.createTempDirectory(root, ".artifact-")
            privateDirectory(staging)
            try {
                privateWrite(staging.resolve("content"), content)
                privateWrite(staging.resolve("metadata.json"), PlatformJson.codec.encodeToString(published).toByteArray())
                checkPath(destination)
                Files.move(staging, destination, ATOMIC_MOVE)
            } finally {
                if (Files.exists(staging)) {
                    Files.deleteIfExists(staging.resolve("content"))
                    Files.deleteIfExists(staging.resolve("metadata.json"))
                    Files.delete(staging)
                }
            }
            published
        }

    public fun metadata(hash: String): PublishedArtifact =
        sanitized {
            requireHash(hash)
            val directory = root.resolve(hash)
            val bytes = safeRead(directory.resolve("metadata.json"), 65_536)
            val metadata = PlatformJson.codec.decodeFromString<PublishedArtifact>(bytes.decodeToString(throwOnInvalidSequence = true))
            scriptRequire(metadata.hash == hash, "E_ARTIFACT_HASH")
            metadata
        }

    /** Revalidate identity/content on every preview/export; never trust a path from serialized data. */
    public fun read(hash: String): ByteArray =
        sanitized {
            val metadata = metadata(hash)
            val content = safeRead(root.resolve(hash).resolve("content"), maxBytes)
            scriptRequire(content.size == metadata.byteCount && digest(content) == metadata.contentHash, "E_ARTIFACT_HASH")
            val identity =
                PlatformJson.codec.encodeToString(metadata.provenance).toByteArray() + metadata.format.name.toByteArray() + content
            scriptRequire(digest(identity) == hash, "E_ARTIFACT_HASH")
            content
        }
}

internal fun renderArtifact(request: ArtifactRequest): String {
    val provenance = request.provenance
    val details =
        listOf(
            "Session ${provenance.sessionId}; generation ${provenance.generation}",
            "Agent ${provenance.agentId}@${provenance.agentVersion}",
            "Input SHA256 ${provenance.inputHash}",
            "Evidence: ${provenance.evidenceIds.sorted().joinToString(", ")}",
            "Source candidates: ${provenance.candidateIds.sorted().joinToString(", ")}",
        ) + provenance.warnings.map { "Warning: $it" }
    return if (request.format == ArtifactFormat.HTML) {
        buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append(
                "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\">",
            )
            append("<title>${escape(request.title)}</title></head><body><h1>${escape(request.title)}</h1>")
            request.sections.forEach { append("<section><h2>${escape(it.heading)}</h2><pre>${escape(it.text)}</pre></section>") }
            append("<section><h2>Provenance</h2><pre>${escape(details.joinToString("\n"))}</pre></section></body></html>\n")
        }
    } else {
        // Raw text in fenced blocks cannot create links, images, scripts or HTML in Markdown renderers.
        buildString {
            append("# ${markdownText(request.title)}\n\n")
            request.sections.forEach { append("## ${markdownText(it.heading)}\n\n${fenced(it.text)}\n\n") }
            append("## Provenance\n\n${fenced(details.joinToString("\n"))}\n")
        }
    }
}

private fun escape(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun markdownText(text: String): String =
    escape(text)
        .replace("\n", " ")
        .replace("\r", " ")
        .map {
            if (it in "\\`*_{}[]()#+-.!|~") "\\$it" else it.toString()
        }.joinToString("")

private fun fenced(text: String): String {
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longest + 1))
    return "$fence\n$text\n$fence"
}

private fun requireHash(hash: String) {
    scriptRequire(hash.matches(Regex("[a-f0-9]{64}")), "E_ARTIFACT_HASH")
}

private inline fun <T> sanitized(block: () -> T): T =
    try {
        block()
    } catch (failure: ScriptException) {
        throw failure
    } catch (_: Exception) {
        throw ScriptException("E_ARTIFACT_IO")
    }
