package dev.agentperf.desktop

import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal interface CaptureArchiveFileChooser {
    fun chooseImport(title: String): Path?

    fun chooseScreenshotImport(title: String): Path?

    fun chooseExport(
        title: String,
        initialFileName: String,
    ): Path?
}

internal class SwingCaptureArchiveFileChooser : CaptureArchiveFileChooser {
    override fun chooseImport(title: String): Path? {
        val chooser = archiveChooser(title)
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath()
        } else {
            null
        }
    }

    override fun chooseScreenshotImport(title: String): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = pngFilter
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath()
        } else {
            null
        }
    }

    override fun chooseExport(
        title: String,
        initialFileName: String,
    ): Path? {
        val chooser = archiveChooser(title).apply {
            selectedFile = Path.of(initialFileName).toFile()
            fileFilter = apinspectFilter
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val extension = when (chooser.fileFilter) {
            zipFilter -> ZIP_EXTENSION
            else -> APINSPECT_EXTENSION
        }
        return normalizeCaptureArchiveExportPath(
            selected = chooser.selectedFile.toPath(),
            defaultExtension = extension,
        )
    }

    private fun archiveChooser(title: String): JFileChooser =
        JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            addChoosableFileFilter(apinspectFilter)
            addChoosableFileFilter(zipFilter)
        }

    private companion object {
        val apinspectFilter = FileNameExtensionFilter(
            "AgentPerf Inspector Capture (*.apinspect)",
            APINSPECT_EXTENSION,
        )
        val zipFilter = FileNameExtensionFilter(
            "ZIP Archive (*.zip)",
            ZIP_EXTENSION,
        )
        val pngFilter = FileNameExtensionFilter(
            "PNG Screenshot (*.png)",
            PNG_EXTENSION,
        )
    }
}

internal fun normalizeCaptureArchiveExportPath(
    selected: Path,
    defaultExtension: String,
): Path {
    require(defaultExtension == APINSPECT_EXTENSION || defaultExtension == ZIP_EXTENSION) {
        "Unsupported capture archive extension: $defaultExtension"
    }
    val fileName = selected.fileName.toString()
    if (SUPPORTED_ARCHIVE_SUFFIXES.any { fileName.endsWith(it, ignoreCase = true) }) {
        return selected
    }
    return selected.resolveSibling("$fileName.$defaultExtension")
}

private const val APINSPECT_EXTENSION = "apinspect"
private const val ZIP_EXTENSION = "zip"
private const val PNG_EXTENSION = "png"
private val SUPPORTED_ARCHIVE_SUFFIXES = listOf(
    ".$APINSPECT_EXTENSION",
    ".$ZIP_EXTENSION",
)
