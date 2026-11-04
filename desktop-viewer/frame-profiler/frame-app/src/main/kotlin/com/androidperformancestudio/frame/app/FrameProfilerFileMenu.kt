@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.frame.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.frame.frame_app.generated.resources.Res
import com.androidperformancestudio.frame.frame_app.generated.resources.export_csv
import com.androidperformancestudio.frame.frame_app.generated.resources.export_json
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.MenuCommand
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal data class FrameProfilerExportMenuModel(
    val csvLabel: String,
    val jsonLabel: String,
    val enabled: Boolean,
)

internal data class FrameProfilerFileMenuModel(
    val importEnabled: Boolean,
    val exportMenu: FrameProfilerExportMenuModel,
)

internal fun frameProfilerFileMenuModel(
    language: UiLanguage,
    importEnabled: Boolean,
    exportEnabled: Boolean,
): FrameProfilerFileMenuModel =
    FrameProfilerFileMenuModel(
        importEnabled = importEnabled,
        exportMenu =
            FrameProfilerExportMenuModel(
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
    ActivePageMenu(
        PageMenu(
            onOpen = onImportFrameStats,
            openEnabled = model.importEnabled,
            exportGroups =
                listOf(
                    listOf(
                        MenuCommand(model.exportMenu.csvLabel, enabled = model.exportMenu.enabled, onClick = onExportCsv),
                        MenuCommand(model.exportMenu.jsonLabel, enabled = model.exportMenu.enabled, onClick = onExportJson),
                    ),
                ),
        ),
    )
}
