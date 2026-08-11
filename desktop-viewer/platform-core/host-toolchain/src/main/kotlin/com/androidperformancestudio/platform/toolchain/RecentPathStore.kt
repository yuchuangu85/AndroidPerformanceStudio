package com.androidperformancestudio.platform.toolchain

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class RecentPathStore(
    private val storageFile: Path,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    private val temporaryFilePrefix: String = DEFAULT_TEMPORARY_FILE_PREFIX,
) {
    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
        require(temporaryFilePrefix.length >= 3) { "temporaryFilePrefix must contain at least three characters" }
    }

    @Synchronized
    fun load(): List<Path> =
        runCatching {
            if (!Files.isRegularFile(storageFile)) return emptyList()
            Files.readAllLines(storageFile, StandardCharsets.UTF_8)
                .asSequence()
                .filter(String::isNotBlank)
                .mapNotNull { value -> runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull() }
                .distinct()
                .take(maximumEntries)
                .toList()
        }.getOrDefault(emptyList())

    @Synchronized
    fun record(path: Path): List<Path> {
        val normalized = path.toAbsolutePath().normalize()
        val updated = (listOf(normalized) + load().filterNot { it == normalized }).take(maximumEntries)
        write(updated)
        return updated
    }

    @Synchronized
    fun clear() {
        runCatching { Files.deleteIfExists(storageFile) }
    }

    private fun write(entries: List<Path>) {
        runCatching {
            val parent = storageFile.parent ?: return
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, temporaryFilePrefix, ".tmp")
            try {
                Files.write(temporary, entries.map(Path::toString), StandardCharsets.UTF_8)
                runCatching {
                    Files.move(
                        temporary,
                        storageFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    companion object {
        fun desktop(
            fileName: String,
            temporaryFilePrefix: String = DEFAULT_TEMPORARY_FILE_PREFIX,
        ): RecentPathStore =
            RecentPathStore(
                Path.of(System.getProperty("user.home"), APP_DIRECTORY, fileName),
                temporaryFilePrefix = temporaryFilePrefix,
            )
    }
}

private const val DEFAULT_MAXIMUM_ENTRIES = 10
private const val DEFAULT_TEMPORARY_FILE_PREFIX = "recent-paths-"
private const val APP_DIRECTORY = ".android-performance-studio"
