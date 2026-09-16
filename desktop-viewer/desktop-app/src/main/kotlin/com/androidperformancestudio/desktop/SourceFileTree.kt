package com.androidperformancestudio.desktop

import com.androidperformancestudio.source.SourceFile
import java.util.Locale

internal sealed interface SourceFileTreeRow {
    val key: String
    val name: String
    val depth: Int

    data class Directory(
        val path: String,
        override val name: String,
        override val depth: Int,
        val expanded: Boolean,
    ) : SourceFileTreeRow {
        override val key: String = "directory:$path"
    }

    data class File(
        val sourceFile: SourceFile,
        override val name: String,
        override val depth: Int,
    ) : SourceFileTreeRow {
        override val key: String = "file:${sourceFile.relativePath}"
    }
}

/** Builds stable, directory-first source-file rows from indexed relative paths. */
internal object SourceFileTree {
    fun rows(
        files: List<SourceFile>,
        collapsedDirectories: Set<String> = emptySet(),
    ): List<SourceFileTreeRow> {
        val root = DirectoryBuilder(name = "", path = "")
        files.forEach { file -> root.add(file) }
        return root.visibleRows(depth = 0, collapsedDirectories = collapsedDirectories)
    }

    fun ancestorDirectories(relativePath: String): Set<String> {
        val segments = pathSegments(relativePath)
        return segments.dropLast(1)
            .runningFold("") { parent, segment ->
                if (parent.isEmpty()) segment else "$parent/$segment"
            }
            .drop(1)
            .toSet()
    }

    private class DirectoryBuilder(
        val name: String,
        val path: String,
        private val directories: MutableMap<String, DirectoryBuilder> = linkedMapOf(),
        private val files: MutableList<SourceFile> = mutableListOf(),
    ) {
        fun add(file: SourceFile) {
            val segments = pathSegments(file.relativePath)
            if (segments.isEmpty()) return

            var directory = this
            segments.dropLast(1).forEach { segment ->
                directory = directory.directories.getOrPut(segment) {
                    DirectoryBuilder(
                        name = segment,
                        path = if (directory.path.isEmpty()) segment else "${directory.path}/$segment",
                    )
                }
            }
            directory.files += file
        }

        fun visibleRows(
            depth: Int,
            collapsedDirectories: Set<String>,
        ): List<SourceFileTreeRow> = buildList {
            directories.values
                .sortedByName { it.name }
                .forEach { directory ->
                    val expanded = directory.path !in collapsedDirectories
                    add(
                        SourceFileTreeRow.Directory(
                            path = directory.path,
                            name = directory.name,
                            depth = depth,
                            expanded = expanded,
                        ),
                    )
                    if (expanded) {
                        addAll(directory.visibleRows(depth + 1, collapsedDirectories))
                    }
                }
            files.sortedByName { fileName(it.relativePath) }
                .forEach { file ->
                    add(
                        SourceFileTreeRow.File(
                            sourceFile = file,
                            name = fileName(file.relativePath),
                            depth = depth,
                        ),
                    )
                }
        }
    }

    private fun pathSegments(relativePath: String): List<String> =
        relativePath
            .split('/', '\\')
            .filter { segment -> segment.isNotBlank() && segment != "." }

    private fun fileName(relativePath: String): String = pathSegments(relativePath).lastOrNull() ?: relativePath

    private fun <T> Iterable<T>.sortedByName(name: (T) -> String): List<T> =
        sortedWith(compareBy<T> { name(it).lowercase(Locale.ROOT) }.thenBy(name))
}
