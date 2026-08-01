package com.androidperformancestudio.source

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

public class ContentAddressedSourceCache(
    private val root: Path,
) {
    public fun put(content: ByteArray): String {
        val hash = content.sha256()
        val target = pathFor(hash)
        if (!Files.exists(target)) {
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, "source-", ".tmp")
            Files.write(temporary, content)
            runCatching {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        }
        return hash
    }

    public fun read(hash: String): ByteArray {
        require(hash.matches(HASH_PATTERN)) { "Invalid cache hash" }
        val path = pathFor(hash)
        require(Files.isRegularFile(path)) { "Cached source is unavailable: $hash" }
        val content = Files.readAllBytes(path)
        check(content.sha256() == hash) { "Cached source hash mismatch: $hash" }
        return content
    }

    public fun contains(hash: String): Boolean = hash.matches(HASH_PATTERN) && Files.isRegularFile(pathFor(hash))

    private fun pathFor(hash: String): Path = root.resolve(hash.take(2)).resolve(hash.drop(2))

    private companion object {
        val HASH_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}
