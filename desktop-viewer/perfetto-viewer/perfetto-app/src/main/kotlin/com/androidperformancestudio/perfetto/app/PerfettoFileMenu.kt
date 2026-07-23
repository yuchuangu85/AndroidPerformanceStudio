package com.androidperformancestudio.perfetto.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import java.nio.file.Path

@Composable
internal fun FrameWindowScope.PerfettoFileMenuBar(
    canExport: Boolean,
    recentFiles: List<Path>,
    onOpen: () -> Unit,
    onExportSession: () -> Unit,
    onExportRawTrace: () -> Unit,
    onOpenRecent: (Path) -> Unit,
    onClearRecent: () -> Unit,
) {
    val isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val openShortcut = KeyShortcut(Key.O, ctrl = !isMacOs, meta = isMacOs)

    MenuBar {
        Menu("File") {
            Item("Open\u2026", shortcut = openShortcut, onClick = onOpen)
            Menu("Export") {
                Item("Session Package (.zip)", enabled = canExport, onClick = onExportSession)
                Item("Raw Trace (.pftrace)", enabled = canExport, onClick = onExportRawTrace)
            }
            Menu("Open Recent") {
                if (recentFiles.isEmpty()) {
                    Item("No Recent Files", enabled = false, onClick = {})
                } else {
                    recentFiles.forEach { path ->
                        Item(path.fileName?.toString() ?: path.toString(), onClick = { onOpenRecent(path) })
                    }
                    Separator()
                    Item("Clear Menu", onClick = onClearRecent)
                }
            }
        }
    }
}
