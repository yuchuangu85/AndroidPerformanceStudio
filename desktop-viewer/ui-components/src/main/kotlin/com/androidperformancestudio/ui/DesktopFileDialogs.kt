@file:Suppress("FunctionName")

package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.AwtWindow
import java.awt.Component
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

public fun chooseSaveFile(
    parent: Component?,
    title: String,
    defaultName: String,
): File? =
    JFileChooser().run {
        dialogTitle = title
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

public fun chooseOpenFile(
    parent: Component?,
    title: String,
    filterDescription: String,
    vararg extensions: String,
    acceptAllFiles: Boolean = true,
): File? =
    JFileChooser().run {
        dialogTitle = title
        fileFilter = FileNameExtensionFilter(filterDescription, *extensions)
        isAcceptAllFileFilterUsed = acceptAllFiles
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

@Composable
public fun DesktopOpenFileDialog(
    parent: Frame,
    title: String,
    acceptFileName: (String) -> Boolean,
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(parent, title, FileDialog.LOAD) {
                init {
                    isMultipleMode = false
                    filenameFilter = FilenameFilter { _, name -> acceptFileName(name) }
                }

                override fun setVisible(value: Boolean) {
                    super.setVisible(value)
                    if (value) onCloseRequest(files.firstOrNull())
                }
            }
        },
        dispose = FileDialog::dispose,
    )
}
