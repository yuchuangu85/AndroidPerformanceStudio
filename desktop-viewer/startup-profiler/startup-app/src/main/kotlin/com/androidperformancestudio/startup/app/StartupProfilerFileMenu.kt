@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.startup.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.startup.startup_app.generated.resources.Res
import com.androidperformancestudio.startup.startup_app.generated.resources.export_csv
import com.androidperformancestudio.startup.startup_app.generated.resources.export_json
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.MenuCommand
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal data class StartupProfilerExportMenuModel(
    val csvLabel: String,
    val jsonLabel: String,
    val enabled: Boolean,
)

internal data class StartupProfilerFileMenuModel(
    val importEnabled: Boolean,
    val exportMenu: StartupProfilerExportMenuModel,
)

internal fun startupProfilerFileMenuModel(
    language: UiLanguage,
    importEnabled: Boolean,
    exportEnabled: Boolean,
): StartupProfilerFileMenuModel =
    StartupProfilerFileMenuModel(
        importEnabled = importEnabled,
        exportMenu =
            StartupProfilerExportMenuModel(
                csvLabel = localizedStringResource(Res.string.export_csv, language),
                jsonLabel = localizedStringResource(Res.string.export_json, language),
                enabled = exportEnabled,
            ),
    )

@Composable
internal fun FrameWindowScope.StartupProfilerFileMenuBar(
    model: StartupProfilerFileMenuModel,
    onImport: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    ActivePageMenu(
        PageMenu(
            onOpen = onImport,
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
