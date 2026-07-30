@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.frame.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.androidperformancestudio.frame.frame_app.generated.resources.Res
import com.androidperformancestudio.frame.frame_app.generated.resources.export
import com.androidperformancestudio.frame.frame_app.generated.resources.export_csv
import com.androidperformancestudio.frame.frame_app.generated.resources.export_json
import com.androidperformancestudio.frame.frame_app.generated.resources.file
import com.androidperformancestudio.frame.frame_app.generated.resources.import_framestats
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal data class FrameProfilerExportMenuModel(
    val title: String,
    val csvLabel: String,
    val jsonLabel: String,
    val enabled: Boolean,
)

internal data class FrameProfilerFileMenuModel(
    val fileTitle: String,
    val importFrameStatsLabel: String,
    val importEnabled: Boolean,
    val exportMenu: FrameProfilerExportMenuModel,
)

internal fun frameProfilerFileMenuModel(
    language: UiLanguage,
    importEnabled: Boolean,
    exportEnabled: Boolean,
): FrameProfilerFileMenuModel =
    FrameProfilerFileMenuModel(
        fileTitle = localizedStringResource(Res.string.file, language),
        importFrameStatsLabel = localizedStringResource(Res.string.import_framestats, language),
        importEnabled = importEnabled,
        exportMenu =
            FrameProfilerExportMenuModel(
                title = localizedStringResource(Res.string.export, language),
                csvLabel = localizedStringResource(Res.string.export_csv, language),
                jsonLabel = localizedStringResource(Res.string.export_json, language),
                enabled = exportEnabled,
            ),
    )

@Composable
internal fun FrameWindowScope.FrameProfilerFileMenuBar(
    model: FrameProfilerFileMenuModel,
    onImportFrameStats: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    MenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.importFrameStatsLabel,
                enabled = model.importEnabled,
                onClick = onImportFrameStats,
            )
            Menu(model.exportMenu.title) {
                Item(
                    text = model.exportMenu.csvLabel,
                    enabled = model.exportMenu.enabled,
                    onClick = onExportCsv,
                )
                Item(
                    text = model.exportMenu.jsonLabel,
                    enabled = model.exportMenu.enabled,
                    onClick = onExportJson,
                )
            }
        }
    }
}
