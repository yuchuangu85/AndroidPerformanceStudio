package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposeInspectorArtifact
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

data class ResolvedComposeInspector(
    val jar: Path,
    val identity: ComposeInspectorArtifact,
)

data class ComposeInspectorArtifactPlan(
    val source: String,
    val downloadRequired: Boolean,
)

class ComposeInspectorArtifactResolver(
    private val cacheDir: Path,
    private val projectArtifacts: List<Path> = emptyList(),
    private val gradleUserHome: Path = Path.of(System.getProperty("user.home"), ".gradle"),
    private val mavenLocal: Path = Path.of(System.getProperty("user.home"), ".m2", "repository"),
    private val enterpriseRepositories: List<URI> = emptyList(),
    private val googleRepository: URI = URI("https://dl.google.com/dl/android/maven2/"),
    private val downloader: (URI) -> ByteArray = ::download,
) {
    fun plan(version: String, explicitLocalArtifact: Path? = null): ComposeInspectorArtifactPlan {
        val coordinate = composeCoordinate(version)
        explicitLocalArtifact?.let { artifact ->
            if (resolveLocal(artifact, coordinate, version, "explicit-local") != null) {
                return ComposeInspectorArtifactPlan("explicit-local", false)
            }
        }
        if (verifiedCache(version) != null) return ComposeInspectorArtifactPlan("aps-cache", false)
        if (projectArtifacts.any { it.fileName.toString() == "${coordinate.artifact}-$version.aar" && Files.isRegularFile(it) }) {
            return ComposeInspectorArtifactPlan("project-cache", false)
        }
        if (gradleArtifacts(coordinate, version).isNotEmpty()) return ComposeInspectorArtifactPlan("gradle-cache", false)
        if (Files.isRegularFile(mavenArtifact(mavenLocal, coordinate, version))) {
            return ComposeInspectorArtifactPlan("maven-local", false)
        }
        return ComposeInspectorArtifactPlan(
            source = (enterpriseRepositories.firstOrNull() ?: googleRepository).toString(),
            downloadRequired = true,
        )
    }

    fun resolve(version: String, explicitLocalArtifact: Path? = null): ResolvedComposeInspector {
        val coordinate = composeCoordinate(version)
        explicitLocalArtifact
            ?.let { resolveLocal(it, coordinate, version, "explicit-local") }
            ?.let { return cache(it) }
        verifiedCache(version)?.let { return it }
        projectArtifacts
            .filter { it.fileName.toString() == "${coordinate.artifact}-$version.aar" }
            .firstNotNullOfOrNull { resolveLocal(it, coordinate, version, "project-cache") }
            ?.let { return cache(it) }
        gradleArtifacts(coordinate, version)
            .firstNotNullOfOrNull { resolveLocal(it, coordinate, version, "gradle-cache") }
            ?.let { return cache(it) }
        resolveLocal(mavenArtifact(mavenLocal, coordinate, version), coordinate, version, "maven-local")
            ?.let { return cache(it) }
        (enterpriseRepositories + googleRepository).forEach { repository ->
            val uri = repository.resolve(mavenRelativePath(coordinate, version))
            runCatching { downloader(uri) }.getOrNull()?.let { bytes ->
                return cache(resolveBytes(bytes, coordinate, version, uri.toString(), isAar = true))
            }
        }
        throw IllegalStateException("No exact Compose inspector found for ${coordinate.group}:${coordinate.artifact}:$version")
    }

    private fun verifiedCache(version: String): ResolvedComposeInspector? {
        val versionDir = cacheDir.resolve(version)
        if (!Files.isDirectory(versionDir)) return null
        val hashDir = Files.list(versionDir).use { hashes ->
            hashes.filter(Files::isDirectory).filter { candidate ->
                val jar = candidate.resolve("inspector.jar")
                Files.isRegularFile(jar) &&
                    Files.size(jar) in 1..MAX_INSPECTOR_BYTES.toLong() &&
                    jar.sha256() == candidate.fileName.toString()
            }.findFirst().orElse(null)
        } ?: return null
        val jar = hashDir.resolve("inspector.jar")
        val hash = hashDir.fileName.toString()
        val coordinate = composeCoordinate(version)
        return ResolvedComposeInspector(
            jar,
            ComposeInspectorArtifact(
                artifact = coordinate.artifact,
                version = version,
                sha256 = hash,
                source = "aps-cache",
                certified = false,
            ),
        )
    }

    private fun cache(resolved: ResolvedBytes): ResolvedComposeInspector {
        val hash = sha256(resolved.jar)
        val destination = cacheDir.resolve(resolved.version).resolve(hash).resolve("inspector.jar")
        Files.createDirectories(destination.parent)
        if (!Files.isRegularFile(destination)) {
            val temporary = Files.createTempFile(destination.parent, "inspector-", ".tmp")
            Files.write(temporary, resolved.jar)
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        return ResolvedComposeInspector(
            destination,
            ComposeInspectorArtifact(
                artifact = resolved.coordinate.artifact,
                version = resolved.version,
                sha256 = hash,
                source = resolved.source,
                certified = false,
            ),
        )
    }

    private fun resolveLocal(
        path: Path,
        coordinate: Coordinate,
        version: String,
        source: String,
    ): ResolvedBytes? = path.takeIf(Files::isRegularFile)?.let {
        require(Files.size(it) in 1..MAX_ARTIFACT_BYTES.toLong()) { "Compose inspector artifact is too large" }
        resolveBytes(
            Files.readAllBytes(it),
            coordinate,
            version,
            source,
            isAar = it.fileName.toString().endsWith(".aar", ignoreCase = true),
        )
    }

    private fun resolveBytes(
        bytes: ByteArray,
        coordinate: Coordinate,
        version: String,
        source: String,
        isAar: Boolean,
    ): ResolvedBytes {
        require(bytes.size <= MAX_ARTIFACT_BYTES) { "Compose inspector artifact is too large" }
        val jar = if (isAar) extractInspector(bytes) else bytes
        require(jar.isNotEmpty()) { "Compose inspector JAR is empty" }
        return ResolvedBytes(coordinate, version, source, jar)
    }

    private fun gradleArtifacts(coordinate: Coordinate, version: String): List<Path> {
        val directory = gradleUserHome.resolve("caches/modules-2/files-2.1")
            .resolve(coordinate.group).resolve(coordinate.artifact).resolve(version)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".aar") }.toList()
        }
    }

    private fun extractInspector(bytes: ByteArray): ByteArray =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "inspector.jar") {
                    return@use zip.readNBytes(MAX_INSPECTOR_BYTES + 1).also {
                        require(it.size <= MAX_INSPECTOR_BYTES) { "Compose inspector JAR is too large" }
                    }
                }
            }
            throw IllegalArgumentException("AAR does not contain inspector.jar")
        }

    private data class ResolvedBytes(
        val coordinate: Coordinate,
        val version: String,
        val source: String,
        val jar: ByteArray,
    )

    companion object {
        private const val MAX_ARTIFACT_BYTES = 128 * 1024 * 1024
        private const val MAX_INSPECTOR_BYTES = 64 * 1024 * 1024

        fun composeCoordinate(version: String): Coordinate {
            val match = Regex("^(\\d+)\\.(\\d+)(?:\\..+)?$").matchEntire(version)
                ?: throw IllegalArgumentException("Invalid Compose version: $version")
            val major = match.groupValues[1].toInt()
            val minor = match.groupValues[2].toInt()
            val artifact = if (major > 1 || major == 1 && minor >= 5) "ui-android" else "ui"
            return Coordinate("androidx.compose.ui", artifact)
        }

        private fun mavenArtifact(root: Path, coordinate: Coordinate, version: String): Path =
            root.resolve(mavenRelativePath(coordinate, version))

        private fun mavenRelativePath(coordinate: Coordinate, version: String): String =
            "${coordinate.group.replace('.', '/')}/${coordinate.artifact}/$version/" +
                "${coordinate.artifact}-$version.aar"

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun download(uri: URI): ByteArray {
            require(uri.scheme == "https") { "Compose inspector downloads require HTTPS" }
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() == 200) { "Compose inspector download failed: HTTP ${response.statusCode()}" }
            return response.body().use { input ->
                input.readNBytes(MAX_ARTIFACT_BYTES + 1).also { bytes ->
                    require(bytes.size <= MAX_ARTIFACT_BYTES) { "Compose inspector artifact is too large" }
                }
            }
        }
    }
}

data class Coordinate(val group: String, val artifact: String)
