package dev.agentperf.desktop

import java.nio.file.Path
import javax.swing.JFileChooser

internal fun interface ExportDirectoryChooser {
    fun chooseDirectory(title: String): Path?
}

internal class SwingExportDirectoryChooser : ExportDirectoryChooser {
    override fun chooseDirectory(title: String): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath()
        } else {
            null
        }
    }
}
