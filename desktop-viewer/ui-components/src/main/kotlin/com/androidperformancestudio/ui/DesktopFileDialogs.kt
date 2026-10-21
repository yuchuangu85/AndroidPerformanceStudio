@file:Suppress("FunctionName")

package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.AwtWindow
import java.awt.Component
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * True where choosers use the platform's native panel instead of Swing's [JFileChooser].
 *
 * A [JFileChooser] opens in its own `JDialog`, which has no menu bar, so on macOS the screen menu
 * bar drops to only the Apple and application menus for as long as the chooser is up. The native
 * [FileDialog] is an `NSOpenPanel`/`NSSavePanel` and leaves the window's menu bar in place.
 */
public val usesNativeFileDialogs: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

private const val MAC_DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"

public fun chooseSaveFile(
    parent: Component?,
    title: String,
    defaultName: String,
): File? =
    if (usesNativeFileDialogs) {
        showNativeFileDialog(parent, title, FileDialog.SAVE) { file = defaultName }
    } else {
        JFileChooser().run {
            dialogTitle = title
            selectedFile = File(defaultName)
            if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
        }
    }

public fun chooseDirectory(
    parent: Component?,
    title: String,
    startDirectory: File? = null,
): File? =
    if (usesNativeFileDialogs) {
        val previous = System.getProperty(MAC_DIRECTORY_DIALOG_PROPERTY)
        System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "true")
        try {
            showNativeFileDialog(parent, title, FileDialog.LOAD) {
                startDirectory?.takeIf(File::isDirectory)?.let { directory = it.path }
            }
        } finally {
            if (previous == null) {
                System.clearProperty(MAC_DIRECTORY_DIALOG_PROPERTY)
            } else {
                System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, previous)
            }
        }
    } else {
        JFileChooser().run {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            startDirectory?.takeIf(File::isDirectory)?.let { currentDirectory = it }
            if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
        }
    }

/** Opens any file, without an extension filter. */
public fun chooseOpenFile(
    parent: Component?,
    title: String,
): File? =
    if (usesNativeFileDialogs) {
        showNativeFileDialog(parent, title, FileDialog.LOAD) {}
    } else {
        JFileChooser().run {
            dialogTitle = title
            if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
        }
    }

public fun chooseOpenFile(
    parent: Component?,
    title: String,
    filterDescription: String,
    vararg extensions: String,
    acceptAllFiles: Boolean = true,
): File? =
    if (usesNativeFileDialogs) {
        showNativeFileDialog(parent, title, FileDialog.LOAD) {
            // The native panel cannot switch filters, so only a strict chooser restricts files.
            if (!acceptAllFiles && extensions.isNotEmpty()) {
                filenameFilter = FilenameFilter { _, name -> name.hasExtension(extensions) }
            }
        }
    } else {
        JFileChooser().run {
            dialogTitle = title
            if (extensions.isNotEmpty()) fileFilter = FileNameExtensionFilter(filterDescription, *extensions)
            isAcceptAllFileFilterUsed = acceptAllFiles
            if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
        }
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

/** Shows a modal native panel owned by the caller's window and returns the chosen file. */
private fun showNativeFileDialog(
    parent: Component?,
    title: String,
    mode: Int,
    configure: FileDialog.() -> Unit,
): File? {
    val dialog =
        when (val owner = dialogOwner(parent)) {
            is Frame -> FileDialog(owner, title, mode)
            is Dialog -> FileDialog(owner, title, mode)
            else -> FileDialog(null as Frame?, title, mode)
        }
    return try {
        dialog.isMultipleMode = false
        dialog.configure()
        dialog.isVisible = true
        dialog.file?.let { name -> File(dialog.directory, name) }
    } finally {
        dialog.dispose()
    }
}

private fun dialogOwner(parent: Component?): Window? =
    (parent as? Window)
        ?: parent?.let(SwingUtilities::getWindowAncestor)
        ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        ?: Frame.getFrames().firstOrNull(Frame::isVisible)

internal fun String.hasExtension(extensions: Array<out String>): Boolean =
    extensions.any { extension -> endsWith(".$extension", ignoreCase = true) }
