package com.androidperformancestudio.desktop

import com.androidperformancestudio.source.SourceFile
import com.androidperformancestudio.source.SourceLanguage
import com.androidperformancestudio.source.SourceSnapshotId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceFileTreeTest {
    @Test
    fun `source tree row geometry matches the inspector hierarchy`() {
        assertEquals(20, SourceFileTreeRowLayout.HEIGHT_DP)
        assertEquals(14, SourceFileTreeRowLayout.INDENT_DP)
    }

    @Test
    fun `source file rows build an alphabetical directory-first tree`() {
        val rows = SourceFileTree.rows(
            listOf(
                sourceFile("README.md"),
                sourceFile("app/src/main/res/layout/screen.xml", SourceLanguage.XML),
                sourceFile("app/src/main/kotlin/Beta.kt"),
                sourceFile("app/src/main/kotlin/Alpha.kt"),
            ),
        )

        assertEquals(
            listOf(
                "directory:app",
                "directory:app/src",
                "directory:app/src/main",
                "directory:app/src/main/kotlin",
                "file:app/src/main/kotlin/Alpha.kt",
                "file:app/src/main/kotlin/Beta.kt",
                "directory:app/src/main/res",
                "directory:app/src/main/res/layout",
                "file:app/src/main/res/layout/screen.xml",
                "file:README.md",
            ),
            rows.map { it.key },
        )
        assertEquals(
            listOf(0, 1, 2, 3, 4, 4, 3, 4, 5, 0),
            rows.map { it.depth },
        )
    }

    @Test
    fun `collapsing a directory hides only its descendants`() {
        val rows = SourceFileTree.rows(
            files = listOf(
                sourceFile("app/src/main/kotlin/Alpha.kt"),
                sourceFile("app/src/main/res/layout/screen.xml", SourceLanguage.XML),
            ),
            collapsedDirectories = setOf("app/src/main/kotlin"),
        )

        assertTrue(rows.any { it.key == "directory:app/src/main/kotlin" })
        assertFalse(rows.any { it.key == "file:app/src/main/kotlin/Alpha.kt" })
        assertTrue(rows.any { it.key == "file:app/src/main/res/layout/screen.xml" })
        assertFalse((rows.first { it.key == "directory:app/src/main/kotlin" } as SourceFileTreeRow.Directory).expanded)
    }

    @Test
    fun `selected file ancestors expand using normalized directory paths`() {
        assertEquals(
            setOf("app", "app/src", "app/src/main"),
            SourceFileTree.ancestorDirectories("app\\src/main/Main.kt"),
        )
    }

    private fun sourceFile(
        relativePath: String,
        language: SourceLanguage = SourceLanguage.KOTLIN,
    ): SourceFile =
        SourceFile(
            snapshotId = SourceSnapshotId("snapshot"),
            relativePath = relativePath,
            language = language,
            contentHash = "hash",
            sizeBytes = 1,
        )
}
