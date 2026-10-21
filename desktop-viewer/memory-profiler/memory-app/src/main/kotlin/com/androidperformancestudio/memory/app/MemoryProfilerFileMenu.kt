@file:Suppress("FunctionName", "LongMethod", "MatchingDeclarationName", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.export_bitmap_comparison
import com.androidperformancestudio.memory.memory_app.generated.resources.export_bitmap_dump
import com.androidperformancestudio.memory.memory_app.generated.resources.export_class_instances
import com.androidperformancestudio.memory.memory_app.generated.resources.export_csv
import com.androidperformancestudio.memory.memory_app.generated.resources.export_diff
import com.androidperformancestudio.memory.memory_app.generated.resources.export_investigation_report
import com.androidperformancestudio.memory.memory_app.generated.resources.export_native_heap_trace
import com.androidperformancestudio.memory.memory_app.generated.resources.export_object_investigation
import com.androidperformancestudio.memory.memory_app.generated.resources.export_raw_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.export_snapshot_json
import com.androidperformancestudio.memory.memory_app.generated.resources.export_standard_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.menu_import_java_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.menu_import_mapping
import com.androidperformancestudio.memory.memory_app.generated.resources.menu_import_native_heap
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.MenuCommand
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.RecentMenu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_RECENT_SESSIONS_IN_MENU = 12

internal data class MemoryProfilerFileMenuModel(
    val importMappingLabel: String = "ProGuard/R8 Mapping…",
    val importMappingEnabled: Boolean = false,
    val importNativeHeapLabel: String = "Native Heap Trace…",
    val importNativeHeapEnabled: Boolean = false,
    val importJavaHeapLabel: String = "Java Heap…",
    val importJavaHeapEnabled: Boolean = false,
    val exportRawHprofLabel: String,
    val exportStandardHprofLabel: String,
    val exportCsvLabel: String,
    val exportClassInstancesLabel: String = "Export Selected Class Instances CSV",
    val exportClassInstancesEnabled: Boolean = false,
    val exportObjectInvestigationLabel: String = "Export Object Investigation JSON",
    val exportObjectInvestigationEnabled: Boolean = false,
    val exportDiffLabel: String = "Export Class Diff CSV",
    val exportSnapshotJsonLabel: String = "Export Snapshot JSON",
    val exportInvestigationReportLabel: String = "Export Investigation Report",
    val exportDiffEnabled: Boolean = false,
    val exportSnapshotJsonEnabled: Boolean = false,
    val exportInvestigationReportEnabled: Boolean = false,
    val importEnabled: Boolean,
    val rawHprofExportEnabled: Boolean,
    val standardHprofExportEnabled: Boolean,
    val csvExportEnabled: Boolean,
    val exportBitmapDumpLabel: String = "Export Bitmap Dump ZIP",
    val exportBitmapComparisonLabel: String = "Export Bitmap Comparison",
    val bitmapDumpExportEnabled: Boolean = false,
    val bitmapComparisonExportEnabled: Boolean = false,
    val exportNativeHeapLabel: String = "Export Native Heap trace",
    val exportNativeHeapEnabled: Boolean = false,
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
    exportClassInstancesEnabled: Boolean = false,
    exportObjectInvestigationEnabled: Boolean = false,
    exportDiffEnabled: Boolean = false,
    exportSnapshotJsonEnabled: Boolean = false,
    exportInvestigationReportEnabled: Boolean = false,
    bitmapDumpExportEnabled: Boolean = false,
    bitmapComparisonExportEnabled: Boolean = false,
    recentSessions: List<MemorySessionMetadata> = emptyList(),
    importMappingEnabled: Boolean = false,
    importNativeHeapEnabled: Boolean = false,
    importJavaHeapEnabled: Boolean = false,
    exportNativeHeapEnabled: Boolean = false,
): MemoryProfilerFileMenuModel =
    MemoryProfilerFileMenuModel(
        importMappingLabel = localizedStringResource(Res.string.menu_import_mapping, language),
        importMappingEnabled = importMappingEnabled,
        importNativeHeapLabel = localizedStringResource(Res.string.menu_import_native_heap, language),
        importNativeHeapEnabled = importNativeHeapEnabled,
        importJavaHeapLabel = localizedStringResource(Res.string.menu_import_java_heap, language),
        importJavaHeapEnabled = importJavaHeapEnabled,
        exportNativeHeapLabel = localizedStringResource(Res.string.export_native_heap_trace, language),
        exportNativeHeapEnabled = exportNativeHeapEnabled,
        exportRawHprofLabel = localizedStringResource(Res.string.export_raw_hprof, language),
        exportStandardHprofLabel = localizedStringResource(Res.string.export_standard_hprof, language),
        exportCsvLabel = localizedStringResource(Res.string.export_csv, language),
        exportClassInstancesLabel = localizedStringResource(Res.string.export_class_instances, language),
        exportClassInstancesEnabled = exportClassInstancesEnabled,
        exportObjectInvestigationLabel = localizedStringResource(Res.string.export_object_investigation, language),
        exportObjectInvestigationEnabled = exportObjectInvestigationEnabled,
        exportDiffLabel = localizedStringResource(Res.string.export_diff, language),
        exportSnapshotJsonLabel = localizedStringResource(Res.string.export_snapshot_json, language),
        exportInvestigationReportLabel = localizedStringResource(Res.string.export_investigation_report, language),
        exportDiffEnabled = exportDiffEnabled,
        exportSnapshotJsonEnabled = exportSnapshotJsonEnabled,
        exportInvestigationReportEnabled = exportInvestigationReportEnabled,
        importEnabled = importEnabled,
        rawHprofExportEnabled = rawHprofExportEnabled,
        standardHprofExportEnabled = standardHprofExportEnabled,
        csvExportEnabled = csvExportEnabled,
        exportBitmapDumpLabel = localizedStringResource(Res.string.export_bitmap_dump, language),
        exportBitmapComparisonLabel = localizedStringResource(Res.string.export_bitmap_comparison, language),
        bitmapDumpExportEnabled = bitmapDumpExportEnabled,
        bitmapComparisonExportEnabled = bitmapComparisonExportEnabled,
        // Native desktop menus become noticeably slower when a large recent-session list is
        // materialized at once. Keep the store history intact and only render the menu slice.
        recentSessions = recentSessions.take(MAX_RECENT_SESSIONS_IN_MENU),
        recentSessionsEnabled = importEnabled && recentSessions.isNotEmpty(),
    )

@Composable
@Suppress("LongParameterList")
internal fun FrameWindowScope.MemoryProfilerFileMenuBar(
    model: MemoryProfilerFileMenuModel,
    onImportHprof: () -> Unit,
    onImportMapping: () -> Unit = {},
    onImportNativeHeap: () -> Unit = {},
    onImportJavaHeap: () -> Unit = {},
    onExportRawHprof: () -> Unit,
    onExportStandardHprof: () -> Unit,
    onExportCsv: () -> Unit,
    onExportClassInstances: () -> Unit = {},
    onExportObjectInvestigation: () -> Unit = {},
    onExportDiff: () -> Unit = {},
    onExportSnapshotJson: () -> Unit = {},
    onExportInvestigationReport: () -> Unit = {},
    onExportBitmapDump: () -> Unit = {},
    onExportBitmapComparison: () -> Unit = {},
    onExportNativeHeap: () -> Unit = {},
    onLoadSession: (MemorySessionMetadata) -> Unit = {},
) {
    ActivePageMenu(
        PageMenu(
            onOpen = onImportHprof,
            openEnabled = model.importEnabled,
            // Mapping deobfuscates the loaded heap; Java and native heap traces add a dataset to the session.
            imports =
                listOf(
                    MenuCommand(model.importJavaHeapLabel, enabled = model.importJavaHeapEnabled, onClick = onImportJavaHeap),
                    MenuCommand(model.importNativeHeapLabel, enabled = model.importNativeHeapEnabled, onClick = onImportNativeHeap),
                    MenuCommand(model.importMappingLabel, enabled = model.importMappingEnabled, onClick = onImportMapping),
                ),
            recent =
                RecentMenu(
                    items = model.recentSessions.map { session -> MenuCommand(recentSessionLabel(session)) { onLoadSession(session) } },
                    enabled = model.recentSessionsEnabled,
                ),
            exportGroups =
                listOf(
                    listOf(
                        MenuCommand(model.exportRawHprofLabel, enabled = model.rawHprofExportEnabled, onClick = onExportRawHprof),
                        MenuCommand(
                            model.exportStandardHprofLabel,
                            enabled = model.standardHprofExportEnabled,
                            onClick = onExportStandardHprof,
                        ),
                        MenuCommand(model.exportNativeHeapLabel, enabled = model.exportNativeHeapEnabled, onClick = onExportNativeHeap),
                    ),
                    listOf(
                        MenuCommand(model.exportCsvLabel, enabled = model.csvExportEnabled, onClick = onExportCsv),
                        MenuCommand(
                            model.exportClassInstancesLabel,
                            enabled = model.exportClassInstancesEnabled,
                            onClick = onExportClassInstances,
                        ),
                        MenuCommand(
                            model.exportObjectInvestigationLabel,
                            enabled = model.exportObjectInvestigationEnabled,
                            onClick = onExportObjectInvestigation,
                        ),
                        MenuCommand(model.exportDiffLabel, enabled = model.exportDiffEnabled, onClick = onExportDiff),
                        MenuCommand(model.exportSnapshotJsonLabel, enabled = model.exportSnapshotJsonEnabled, onClick = onExportSnapshotJson),
                        MenuCommand(
                            model.exportInvestigationReportLabel,
                            enabled = model.exportInvestigationReportEnabled,
                            onClick = onExportInvestigationReport,
                        ),
                    ),
                    listOf(
                        MenuCommand(model.exportBitmapDumpLabel, enabled = model.bitmapDumpExportEnabled, onClick = onExportBitmapDump),
                        MenuCommand(
                            model.exportBitmapComparisonLabel,
                            enabled = model.bitmapComparisonExportEnabled,
                            onClick = onExportBitmapComparison,
                        ),
                    ),
                ),
        ),
    )
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
