@file:Suppress("FunctionName", "MatchingDeclarationName", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.export
import com.androidperformancestudio.memory.memory_app.generated.resources.export_bitmap_comparison
import com.androidperformancestudio.memory.memory_app.generated.resources.export_bitmap_dump
import com.androidperformancestudio.memory.memory_app.generated.resources.export_csv
import com.androidperformancestudio.memory.memory_app.generated.resources.export_raw_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.export_standard_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.file
import com.androidperformancestudio.memory.memory_app.generated.resources.import_hprof_menu
import com.androidperformancestudio.memory.memory_app.generated.resources.no_recent_sessions
import com.androidperformancestudio.memory.memory_app.generated.resources.recent_sessions
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class MemoryProfilerFileMenuModel(
    val fileTitle: String,
    val importLabel: String,
    val exportTitle: String,
    val exportRawHprofLabel: String,
    val exportStandardHprofLabel: String,
    val exportCsvLabel: String,
    val importEnabled: Boolean,
    val rawHprofExportEnabled: Boolean,
    val standardHprofExportEnabled: Boolean,
    val csvExportEnabled: Boolean,
    val exportBitmapDumpLabel: String = "Export Bitmap Dump ZIP",
    val exportBitmapComparisonLabel: String = "Export Bitmap Comparison",
    val bitmapDumpExportEnabled: Boolean = false,
    val bitmapComparisonExportEnabled: Boolean = false,
    val recentSessionsTitle: String = "Recent Sessions",
    val noRecentSessionsLabel: String = "No recent sessions",
    val recentSessions: List<MemorySessionMetadata> = emptyList(),
    val recentSessionsEnabled: Boolean = false,
)

@Suppress("LongParameterList")
internal fun memoryProfilerFileMenuModel(
    language: UiLanguage,
    importEnabled: Boolean,
    rawHprofExportEnabled: Boolean,
    standardHprofExportEnabled: Boolean,
    csvExportEnabled: Boolean,
    bitmapDumpExportEnabled: Boolean = false,
    bitmapComparisonExportEnabled: Boolean = false,
    recentSessions: List<MemorySessionMetadata> = emptyList(),
): MemoryProfilerFileMenuModel =
    MemoryProfilerFileMenuModel(
        fileTitle = localizedStringResource(Res.string.file, language),
        importLabel = localizedStringResource(Res.string.import_hprof_menu, language),
        exportTitle = localizedStringResource(Res.string.export, language),
        exportRawHprofLabel = localizedStringResource(Res.string.export_raw_hprof, language),
        exportStandardHprofLabel = localizedStringResource(Res.string.export_standard_hprof, language),
        exportCsvLabel = localizedStringResource(Res.string.export_csv, language),
        importEnabled = importEnabled,
        rawHprofExportEnabled = rawHprofExportEnabled,
        standardHprofExportEnabled = standardHprofExportEnabled,
        csvExportEnabled = csvExportEnabled,
        exportBitmapDumpLabel = localizedStringResource(Res.string.export_bitmap_dump, language),
        exportBitmapComparisonLabel = localizedStringResource(Res.string.export_bitmap_comparison, language),
        bitmapDumpExportEnabled = bitmapDumpExportEnabled,
        bitmapComparisonExportEnabled = bitmapComparisonExportEnabled,
        recentSessionsTitle = localizedStringResource(Res.string.recent_sessions, language),
        noRecentSessionsLabel = localizedStringResource(Res.string.no_recent_sessions, language),
        recentSessions = recentSessions,
        recentSessionsEnabled = importEnabled && recentSessions.isNotEmpty(),
    )

@Composable
@Suppress("LongParameterList")
internal fun FrameWindowScope.MemoryProfilerFileMenuBar(
    model: MemoryProfilerFileMenuModel,
    onImportHprof: () -> Unit,
    onExportRawHprof: () -> Unit,
    onExportStandardHprof: () -> Unit,
    onExportCsv: () -> Unit,
    onExportBitmapDump: () -> Unit = {},
    onExportBitmapComparison: () -> Unit = {},
    onLoadSession: (MemorySessionMetadata) -> Unit = {},
) {
    MenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.importLabel,
                enabled = model.importEnabled,
                onClick = onImportHprof,
            )
            Menu(model.recentSessionsTitle, enabled = model.recentSessionsEnabled) {
                if (model.recentSessions.isEmpty()) {
                    Item(text = model.noRecentSessionsLabel, enabled = false, onClick = {})
                } else {
                    model.recentSessions.forEach { session ->
                        Item(
                            text = recentSessionLabel(session),
                            onClick = { onLoadSession(session) },
                        )
                    }
                }
            }
            Menu(model.exportTitle) {
                Item(
                    text = model.exportRawHprofLabel,
                    enabled = model.rawHprofExportEnabled,
                    onClick = onExportRawHprof,
                )
                Item(
                    text = model.exportStandardHprofLabel,
                    enabled = model.standardHprofExportEnabled,
                    onClick = onExportStandardHprof,
                )
                Item(
                    text = model.exportCsvLabel,
                    enabled = model.csvExportEnabled,
                    onClick = onExportCsv,
                )
                Item(
                    text = model.exportBitmapDumpLabel,
                    enabled = model.bitmapDumpExportEnabled,
                    onClick = onExportBitmapDump,
                )
                Item(
                    text = model.exportBitmapComparisonLabel,
                    enabled = model.bitmapComparisonExportEnabled,
                    onClick = onExportBitmapComparison,
                )
            }
        }
    }
}

private fun recentSessionLabel(session: MemorySessionMetadata): String =
    buildString {
        append(session.packageName.ifBlank { session.rawHprofFile.fileName.toString() })
        append(" · ")
        append(SESSION_TIME_FORMAT.format(session.capturedAt.atZone(ZoneId.systemDefault())))
        append(" · ")
        append(session.objectCount)
    }

private val SESSION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
