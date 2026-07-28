package com.androidperformancestudio.perfetto.app

import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.*

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import java.nio.file.Path

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun FrameWindowScope.PerfettoFileMenuBar(
    chinese: Boolean,
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
        Menu(stringResource(Res.string.file)) {
            Item(stringResource(Res.string.open_u2026), shortcut = openShortcut, onClick = onOpen)
            Menu(stringResource(Res.string.export)) {
                Item(stringResource(Res.string.session_package_zip), enabled = canExport, onClick = onExportSession)
                Item(stringResource(Res.string.raw_trace_pftrace), enabled = canExport, onClick = onExportRawTrace)
            }
            Menu(stringResource(Res.string.open_recent)) {
                if (recentFiles.isEmpty()) {
                    Item(stringResource(Res.string.no_recent_files), enabled = false, onClick = {})
                } else {
                    recentFiles.forEach { path ->
                        Item(path.fileName?.toString() ?: path.toString(), onClick = { onOpenRecent(path) })
                    }
                    Separator()
                    Item(stringResource(Res.string.clear_menu), onClick = onClearRecent)
                }
            }
        }
    }
}
