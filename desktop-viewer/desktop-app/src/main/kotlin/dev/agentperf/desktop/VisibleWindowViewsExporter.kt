package dev.agentperf.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal data class VisibleWindowViewsExportResult(
    val zipPath: Path,
    val textPath: Path,
)

internal class VisibleWindowViewsExporter(
    private val captureDump: () -> ByteArray,
    private val renderText: (ByteArray) -> String,
) {
    fun export(directory: Path): VisibleWindowViewsExportResult {
        require(Files.isDirectory(directory)) {
            "Export destination is not a directory: $directory"
        }
        val zipBytes = captureDump()
        val text = renderText(zipBytes)
        val zipPath = directory.resolve(ZIP_FILE_NAME)
        val textPath = directory.resolve(TEXT_FILE_NAME)
        var zipTemp: Path? = null
        var textTemp: Path? = null
        var zipBackup: Path? = null
        var textBackup: Path? = null
        var zipInstalled = false
        var textInstalled = false
        try {
            zipTemp = Files.createTempFile(directory, TEMP_PREFIX, ".zip.tmp")
            textTemp = Files.createTempFile(directory, TEMP_PREFIX, ".txt.tmp")
            Files.write(zipTemp, zipBytes)
            Files.writeString(textTemp, text, StandardCharsets.UTF_8)
            zipBackup = backupExisting(zipPath)
            textBackup = backupExisting(textPath)
            moveReplacing(zipTemp, zipPath)
            zipInstalled = true
            moveReplacing(textTemp, textPath)
            textInstalled = true
            deleteIfPresent(zipBackup)
            zipBackup = null
            deleteIfPresent(textBackup)
            textBackup = null
            return VisibleWindowViewsExportResult(zipPath, textPath)
        } catch (error: Throwable) {
            if (textInstalled) Files.deleteIfExists(textPath)
            if (zipInstalled) Files.deleteIfExists(zipPath)
            restoreBackup(zipBackup, zipPath)
            zipBackup = null
            restoreBackup(textBackup, textPath)
            textBackup = null
            throw error
        } finally {
            deleteIfPresent(zipTemp)
            deleteIfPresent(textTemp)
            deleteIfPresent(zipBackup)
            deleteIfPresent(textBackup)
        }
    }

    private fun backupExisting(path: Path): Path? {
        if (!Files.exists(path)) return null
        val backup = Files.createTempFile(path.parent, TEMP_PREFIX, ".backup")
        Files.delete(backup)
        moveReplacing(path, backup)
        return backup
    }

    private fun restoreBackup(
        backup: Path?,
        target: Path,
    ) {
        if (backup != null && Files.exists(backup)) {
            moveReplacing(backup, target)
        }
    }

    private fun moveReplacing(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, REPLACE_EXISTING)
        }
    }

    private fun deleteIfPresent(path: Path?) {
        if (path != null) Files.deleteIfExists(path)
    }

    private companion object {
        const val ZIP_FILE_NAME = "visible-window-views.zip"
        const val TEXT_FILE_NAME = "visible-window-views.txt"
        const val TEMP_PREFIX = ".visible-window-views-"
    }
}
