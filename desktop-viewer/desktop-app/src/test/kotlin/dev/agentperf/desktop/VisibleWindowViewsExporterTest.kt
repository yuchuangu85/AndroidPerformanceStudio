package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VisibleWindowViewsExporterTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `export writes raw zip and rendered text`() {
        val zip = byteArrayOf(1, 2, 3)
        val exporter = VisibleWindowViewsExporter(
            captureDump = { zip },
            renderText = { "decoded" },
        )

        val result = exporter.export(tempDir)

        assertEquals(tempDir.resolve("visible-window-views.zip"), result.zipPath)
        assertEquals(tempDir.resolve("visible-window-views.txt"), result.textPath)
        assertArrayEquals(zip, Files.readAllBytes(result.zipPath))
        assertEquals("decoded", Files.readString(result.textPath))
    }

    @Test
    fun `export replaces both existing files`() {
        Files.writeString(tempDir.resolve("visible-window-views.zip"), "old zip")
        Files.writeString(tempDir.resolve("visible-window-views.txt"), "old txt")
        val exporter = VisibleWindowViewsExporter(
            captureDump = { byteArrayOf(9, 8, 7) },
            renderText = { "new text" },
        )

        exporter.export(tempDir)

        assertArrayEquals(
            byteArrayOf(9, 8, 7),
            Files.readAllBytes(tempDir.resolve("visible-window-views.zip")),
        )
        assertEquals(
            "new text",
            Files.readString(tempDir.resolve("visible-window-views.txt")),
        )
    }

    @Test
    fun `render failure leaves existing final files unchanged and removes temps`() {
        Files.writeString(tempDir.resolve("visible-window-views.zip"), "old zip")
        Files.writeString(tempDir.resolve("visible-window-views.txt"), "old txt")
        val exporter = VisibleWindowViewsExporter(
            captureDump = { byteArrayOf(1) },
            renderText = { error("decode failed") },
        )

        assertThrows(IllegalStateException::class.java) {
            exporter.export(tempDir)
        }

        assertEquals(
            "old zip",
            Files.readString(tempDir.resolve("visible-window-views.zip")),
        )
        assertEquals(
            "old txt",
            Files.readString(tempDir.resolve("visible-window-views.txt")),
        )
        assertEquals(
            setOf("visible-window-views.zip", "visible-window-views.txt"),
            Files.list(tempDir).use { paths ->
                paths.map { it.fileName.toString() }.toList().toSet()
            },
        )
    }

    @Test
    fun `export rejects a destination that is not a directory`() {
        val file = tempDir.resolve("output-file")
        Files.writeString(file, "content")
        val exporter = VisibleWindowViewsExporter(
            captureDump = { byteArrayOf(1) },
            renderText = { "decoded" },
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            exporter.export(file)
        }

        assertEquals("Export destination is not a directory: $file", error.message)
    }

    @Test
    fun `directory chooser cancellation returns no destination`() {
        val chooser = ExportDirectoryChooser { null }

        assertNull(chooser.chooseDirectory("Choose export directory"))
    }
}
