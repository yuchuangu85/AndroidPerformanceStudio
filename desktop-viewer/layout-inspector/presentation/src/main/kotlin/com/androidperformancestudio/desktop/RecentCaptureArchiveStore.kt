package com.androidperformancestudio.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class RecentCaptureArchiveStore(
    private val storageFile: Path,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) {
    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
    }

    @Synchronized
    fun load(): List<Path> =
        runCatching {
            if (!Files.isRegularFile(storageFile)) return emptyList()
            Files
                .readAllLines(storageFile, StandardCharsets.UTF_8)
                .asSequence()
                .filter(String::isNotBlank)
                .mapNotNull { value -> runCatching { normalizedPath(value) }.getOrNull() }
                .distinct()
                .take(maximumEntries)
                .toList()
        }.getOrDefault(emptyList())

    @Synchronized
    fun record(archive: Path): List<Path> {
        val normalized = normalizedPath(archive.toString())
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
            val temporary = Files.createTempFile(parent, "recent-layout-archives-", ".tmp")
            try {
                Files.write(
                    temporary,
                    entries.map(Path::toString),
                    StandardCharsets.UTF_8,
                )
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
        fun desktop(): RecentCaptureArchiveStore =
            RecentCaptureArchiveStore(
                Path.of(
                    System.getProperty("user.home"),
                    APP_DIRECTORY,
                    RECENT_ARCHIVES_FILE,
                ),
            )
    }
}

private fun normalizedPath(value: String): Path = Path.of(value).toAbsolutePath().normalize()

private const val DEFAULT_MAXIMUM_ENTRIES = 10
private const val APP_DIRECTORY = ".android-performance-studio"
private const val RECENT_ARCHIVES_FILE = "recent-layout-inspector-archives.txt"
