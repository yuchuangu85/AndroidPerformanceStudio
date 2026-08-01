@file:Suppress("ReturnCount")

package com.androidperformancestudio.source

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

public class LocalSourceProvider : SourceProvider {
    override val kind: SourceProviderKind = SourceProviderKind.LOCAL

    override suspend fun resolveRevision(config: SourceProviderConfig): String {
        val local = config.requireLocal()
        require(Files.isDirectory(local.root)) { "Local source root is not a directory: ${local.root}" }
        val commit = git(local.root, "rev-parse", "HEAD")?.takeIf(String::isNotBlank) ?: "unversioned"
        val hasDirtyFiles = git(local.root, "status", "--porcelain")?.isNotBlank() ?: (commit == "unversioned")
        val dirty = if (hasDirtyFiles) contentDigest(local.root) else null
        return if (dirty == null) commit else "$commit-dirty-$dirty"
    }

    override suspend fun listFiles(
        config: SourceProviderConfig,
        revision: String,
    ): List<ProviderSourceFile> {
        val root = config.requireLocal().root.toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Local source root is not a directory: $root" }
        return Files.walk(root).use { paths ->
            paths
                .filter { path -> isIndexableFile(root, path) }
                .map { path ->
                    ProviderSourceFile(
                        relativePath = root.relativize(path).invariantSeparatorsPathString,
                        sizeBytes = Files.size(path),
                        contentHash = null,
                    )
                }.sorted(compareBy(ProviderSourceFile::relativePath))
                .toList()
        }
    }

    override suspend fun readFile(
        config: SourceProviderConfig,
        revision: String,
        relativePath: String,
    ): ByteArray {
        val root = config.requireLocal().root.toAbsolutePath().normalize()
        val file = root.resolve(relativePath).normalize()
        require(file.startsWith(root)) { "Source path escapes workspace: $relativePath" }
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Source file is unavailable: $relativePath" }
        require(Files.size(file) <= MAX_SOURCE_FILE_BYTES) { "Source file exceeds size limit: $relativePath" }
        return Files.readAllBytes(file)
    }

    private fun isIndexableFile(
        root: Path,
        path: Path,
    ): Boolean {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.size(path) > MAX_SOURCE_FILE_BYTES) return false
        val relative = root.relativize(path)
        if (relative.any { segment -> segment.toString() in ignoredDirectoryNames }) return false
        return path.fileName.toString().substringAfterLast('.', "").lowercase() in sourceExtensions
    }

    private fun git(
        root: Path,
        vararg arguments: String,
    ): String? =
        runCatching {
            val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() == 0) output else null
        }.getOrNull()

    private fun contentDigest(root: Path): String {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val manifest = Files.walk(normalizedRoot).use { paths ->
            paths.filter { path -> isIndexableFile(normalizedRoot, path) }
                .sorted()
                .map { path ->
                    val relative = normalizedRoot.relativize(path).invariantSeparatorsPathString
                    "$relative:${Files.readAllBytes(path).sha256()}"
                }.toList()
        }
        return manifest.joinToString("\n").sha256()
    }

    private companion object {
        const val MAX_SOURCE_FILE_BYTES: Long = 5L * 1024L * 1024L
        val sourceExtensions: Set<String> = setOf("kt", "kts", "java", "xml", "c", "cc", "cpp", "cxx", "h", "hh", "hpp")
        val ignoredDirectoryNames: Set<String> = setOf(".git", ".gradle", ".idea", "build", "out", "node_modules")
    }
}

private fun SourceProviderConfig.requireLocal(): SourceProviderConfig.Local =
    requireNotNull(this as? SourceProviderConfig.Local) { "LocalSourceProvider requires Local config" }
