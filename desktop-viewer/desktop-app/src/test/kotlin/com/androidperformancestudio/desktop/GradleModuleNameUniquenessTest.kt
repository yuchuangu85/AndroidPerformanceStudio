package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleModuleNameUniquenessTest {
    private val root = findGradleRoot()

    @Test
    fun `Gradle module names are unique across composite builds`() {
        val modulesByName =
            settingsFiles()
                .flatMap { settings ->
                    modulePathPattern
                        .findAll(Files.readString(settings))
                        .map { match -> settings.parent.name to match.groupValues[1] }
                        .toList()
                }
                .distinct()
                .groupBy(
                    keySelector = { (_, path) -> path.substringAfterLast(':') },
                    valueTransform = { (build, path) -> "$build$path" },
                )
        val duplicates = modulesByName.filterValues { it.size > 1 }

        assertTrue(
            duplicates.isEmpty(),
            "Gradle module names must be feature-qualified and unique: $duplicates",
        )
    }

    private fun settingsFiles(): List<Path> =
        buildList {
            add(root.resolve("settings.gradle.kts"))
            Files.list(root).use { entries ->
                entries
                    .filter(Files::isDirectory)
                    .map { it.resolve("settings.gradle.kts") }
                    .filter(Files::isRegularFile)
                    .forEach(::add)
            }
        }

    private fun findGradleRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private companion object {
        val modulePathPattern = Regex("""["'](:[a-zA-Z0-9][a-zA-Z0-9:_-]*)["']""")
    }
}
