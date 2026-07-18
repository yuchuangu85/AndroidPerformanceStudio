package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.OfflineImportRequest
import com.androidperformancestudio.application.OfflineProfileFormat
import com.androidperformancestudio.application.OfflineProfileImporter
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.export.ExternalValidationResult
import com.androidperformancestudio.export.GeckoProfileExportService
import com.androidperformancestudio.export.ReportExportService
import com.androidperformancestudio.export.ReportHtmlAdapter
import com.androidperformancestudio.export.SessionPackageService
import com.androidperformancestudio.export.SimpleperfReportAdapter
import com.androidperformancestudio.export.externalOpenInstructions
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.presentation.ReportActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.JOptionPane

@Suppress("TooManyFunctions", "LongParameterList")
internal class DesktopReportActionFactory(
    private val controller: ReportController,
    private val sessionPackages: SessionPackageService,
    private val reportExports: ReportExportService,
    private val offlineImporterFactory: (Path?) -> OfflineProfileImporter,
    private val sessionOpener: suspend (Path) -> Unit,
    private val scope: CoroutineScope,
    private val window: Window,
) {
    private val geckoProfiles = GeckoProfileExportService()

    fun create(state: ReportState): ReportActions =
        ReportActions(
            onOpenSession = ::openSession,
            onCloseSession = controller::closeSession,
            onSelectTab = controller::selectTab,
            onTimeRange = { start, end -> scope.launch { controller.commitRange(start, end) } },
            onThreads = { threads -> scope.launch { controller.updateThreads(threads) } },
            onEvents = { events -> scope.launch { controller.updateEvents(events) } },
            onTopFunctionSort = { sort, descending ->
                scope.launch { controller.updateTopFunctionSort(sort, descending) }
            },
            onCallTreeDirection = { direction -> scope.launch { controller.updateCallTreeDirection(direction) } },
            onFlamePreviewRange = controller::previewRange,
            onCancelFlamePreview = { scope.launch { controller.cancelPreview() } },
            onFlameSearch = { search -> scope.launch { controller.updateFlameSearch(search) } },
            onFlameImplementation = { filter -> scope.launch { controller.updateImplementationFilter(filter) } },
            onApplyFlameTransform = { transform -> scope.launch { controller.applyTransform(transform) } },
            onUndoFlameTransform = { scope.launch { controller.undoLastTransform() } },
            onClearFlameTransforms = { scope.launch { controller.clearTransforms() } },
            onRetryFlameProjection = { scope.launch { controller.retryProjection() } },
            onSelectCallNode = controller::selectCallNode,
            onHoverFlameNode = controller::hoverCallNode,
            onOpenFlameContext = controller::openCallNodeContext,
            onOpenFlameDetails = controller::openFrameDetails,
            onCloseFlameDetails = controller::closeFrameDetails,
            onCopyFlameFunction = ::copyFlameFunction,
            onNavigateFlameNode = controller::navigateCallNode,
            onFocusCallTreeFunction = controller::focusCallTreeFunction,
            onFocusFunction = controller::focusFunction,
            onExportSession = { exportSession(state) },
            onExportReport = { exportReport(state) },
            onExportRawProtobuf = { exportRawProtobuf(state) },
            onExportScreenshot = ::exportScreenshot,
            onGenerateSimpleperfReport = { generateSimpleperfReport(state) },
            onGenerateHtmlReport = { generateHtmlReport(state) },
            onExportExternalGuide = { exportExternalGuide(state) },
            onExportGeckoProfile = { exportGeckoProfile(state) },
            onDetailsVisible = controller::setDetailsVisible,
            onTimelineHeightDp = controller::setTimelineHeightDp,
            onSelectOverviewFinding = controller::selectOverviewFinding,
            onSelectTopFunction = controller::selectTopFunction,
            onSelectStackChartBlock = controller::selectStackChartBlock,
            onSelectMarker = controller::selectMarker,
            onMarkerSearch = { search -> scope.launch { controller.updateMarkerSearch(search) } },
        )

    private fun copyFlameFunction(functionName: String) {
        scope.launch(Dispatchers.Default) {
            runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(functionName), null)
            }
        }
    }

    @Suppress("LongMethod")
    private fun openSession() {
        chooseSessionPath()?.let { selected ->
            val format = detectDesktopProfileFormat(selected)
            val configuredSimpleperf =
                if (format == DesktopProfileFormat.PERF_DATA) chooseFile("Select host simpleperf") ?: return else null
            val supplementalInputs =
                if (format == DesktopProfileFormat.PERF_DATA || format == DesktopProfileFormat.SIMPLEPERF_PROTOBUF) {
                    chooseSupplementalInputs()
                } else {
                    SupplementalInputs()
                }
            scope.launch {
                try {
                    when (format) {
                        DesktopProfileFormat.SESSION_DIRECTORY -> sessionOpener(selected)
                        DesktopProfileFormat.SESSION_PACKAGE -> {
                            val directory =
                                withContext(Dispatchers.IO) {
                                    sessionPackages.import(selected, importedSessionRoot()).sessionDirectory
                                }
                            sessionOpener(directory)
                        }
                        DesktopProfileFormat.PERF_DATA ->
                            importOffline(
                                selected,
                                OfflineProfileFormat.PERF_DATA,
                                configuredSimpleperf,
                                supplementalInputs,
                            )
                        DesktopProfileFormat.SIMPLEPERF_PROTOBUF ->
                            importOffline(selected, OfflineProfileFormat.SIMPLEPERF_PROTOBUF, null, supplementalInputs)
                        DesktopProfileFormat.GECKO_PROFILE_JSON_GZIP ->
                            importOffline(
                                selected,
                                OfflineProfileFormat.GECKO_PROFILE_JSON_GZIP,
                                null,
                                SupplementalInputs(),
                            )
                        DesktopProfileFormat.UNSUPPORTED ->
                            controller.showFailure(
                                selected,
                                StudioError(
                                    ErrorCategory.DATA_VALIDATION,
                                    "UNSUPPORTED_PROFILE_FORMAT",
                                    "Select a session directory, .apsession.zip, perf.data, " +
                                        "Simpleperf protobuf, or Gecko .json.gz",
                                ),
                            )
                    }
                } catch (exception: IllegalArgumentException) {
                    controller.showFailure(
                        selected,
                        StudioError(
                            ErrorCategory.DATA_VALIDATION,
                            "SESSION_OPEN_FAILED",
                            exception.message ?: "Failed to open profiling session",
                            exception,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun importOffline(
        selected: Path,
        format: OfflineProfileFormat,
        configuredSimpleperf: Path?,
        supplementalInputs: SupplementalInputs,
    ) {
        val request =
            OfflineImportRequest(
                sessionId = "import-${UUID.randomUUID()}",
                sessionRoot = importedSessionRoot(),
                input = selected,
                format = format,
                symbolDirectory = supplementalInputs.symbolDirectory,
                proguardMapping = supplementalInputs.proguardMapping,
            )
        when (val result = offlineImporterFactory(configuredSimpleperf).import(request)) {
            is StudioResult.Success -> sessionOpener(result.value.sessionDirectory)
            is StudioResult.Failure ->
                controller.showFailure(
                    importedSessionRoot().resolve(request.sessionId),
                    result.error,
                )
        }
    }

    private fun exportSession(state: ReportState) {
        readyReportDirectory(state)?.let { session ->
            chooseSavePath("Export session package", "${session.fileName}.apsession.zip")?.let { output ->
                scope.launch(Dispatchers.IO) { sessionPackages.export(session, output) }
            }
        }
    }

    private fun exportReport(state: ReportState) {
        readyReport(state)?.let { report ->
            chooseDirectory("Export JSON and CSV")?.let { output ->
                scope.launch(Dispatchers.IO) { exportReportFiles(report, output) }
            }
        }
    }

    private fun exportReportFiles(
        report: ReportData,
        outputDirectory: Path,
    ) {
        reportExports.exportJson(report.topFunctions, report.callTree, outputDirectory.resolve("report.json"))
        reportExports.exportTopFunctionsCsv(report.topFunctions, outputDirectory.resolve("top-functions.csv"))
        reportExports.exportCallTreeCsv(report.callTree, outputDirectory.resolve("call-tree.csv"))
    }

    private fun exportRawProtobuf(state: ReportState) {
        readyReportDirectory(state)?.resolve(RAW_PROTOBUF)?.let { source ->
            chooseSavePath("Export raw protobuf", RAW_PROTOBUF)?.let { output ->
                scope.launch(Dispatchers.IO) { reportExports.exportRawProtobuf(source, output) }
            }
        }
    }

    private fun exportGeckoProfile(state: ReportState) {
        readyReportDirectory(state)?.let { session ->
            chooseSavePath("Export Firefox Profiler JSON", "perf_data.json.gz")?.let { output ->
                scope.launch(Dispatchers.IO) { geckoProfiles.export(session, output) }
            }
        }
    }

    private fun exportScreenshot() {
        chooseSavePath("Export screenshot", "report.png")?.let { output ->
            val location = window.locationOnScreen
            val image = Robot().createScreenCapture(Rectangle(location.x, location.y, window.width, window.height))
            scope.launch(Dispatchers.IO) { reportExports.exportScreenshot(image, output) }
        }
    }

    private fun generateSimpleperfReport(state: ReportState) {
        val session = readyReportDirectory(state)
        val executable = chooseFile("Select host simpleperf")
        val output = chooseSavePath("Export simpleperf report", "simpleperf-report.txt")
        if (session != null && executable != null && output != null) {
            scope.launch(Dispatchers.IO) {
                val result = SimpleperfReportAdapter(executable).generate(session.resolve("perf.data"))
                if (result is ExternalValidationResult.Success) {
                    Files.writeString(output, result.output.stdout.text, StandardCharsets.UTF_8)
                }
            }
        }
    }

    private fun generateHtmlReport(state: ReportState) {
        val session = readyReportDirectory(state)
        val python = chooseFile("Select Python executable")
        val script = chooseFile("Select report_html.py")
        val output = chooseSavePath("Export HTML report", "simpleperf-report.html")
        if (listOf(session, python, script, output).all { it != null }) {
            scope.launch(Dispatchers.IO) {
                ReportHtmlAdapter(checkNotNull(python), checkNotNull(script)).generate(
                    checkNotNull(session).resolve("perf.data"),
                    checkNotNull(output),
                )
            }
        }
    }

    private fun exportExternalGuide(state: ReportState) {
        readyReportDirectory(state)?.resolve(RAW_PROTOBUF)?.let { protobuf ->
            chooseSavePath("Export external open guide", "external-open-guide.txt")?.let { output ->
                val guide = externalOpenInstructions(protobuf)
                scope.launch(Dispatchers.IO) {
                    Files.writeString(
                        output,
                        "${guide.androidStudio}\n${guide.perfetto}\n",
                        StandardCharsets.UTF_8,
                    )
                }
            }
        }
    }
}

private data class SupplementalInputs(
    val proguardMapping: Path? = null,
    val symbolDirectory: Path? = null,
)

private fun chooseSupplementalInputs(): SupplementalInputs =
    SupplementalInputs(
        proguardMapping =
            chooseOptionalPath(
                "Add ProGuard mapping.txt for Java/Kotlin symbol recovery?",
                "Select ProGuard mapping.txt",
                JFileChooser.FILES_ONLY,
            ),
        symbolDirectory =
            chooseOptionalPath(
                "Add unstripped libraries or binary_cache for native symbols?",
                "Select symbols or binary_cache directory",
                JFileChooser.DIRECTORIES_ONLY,
            ),
    )

private fun chooseOptionalPath(
    prompt: String,
    chooserTitle: String,
    selectionMode: Int,
): Path? =
    if (
        JOptionPane.showConfirmDialog(null, prompt, "Optional symbols", JOptionPane.YES_NO_OPTION) ==
        JOptionPane.YES_OPTION
    ) {
        choosePath(chooserTitle, selectionMode)
    } else {
        null
    }

private fun chooseSessionPath(): Path? = choosePath(SESSION_DIALOG_TITLE, JFileChooser.FILES_AND_DIRECTORIES)

private fun chooseFile(title: String): Path? = choosePath(title, JFileChooser.FILES_ONLY)

private fun chooseDirectory(title: String): Path? = choosePath(title, JFileChooser.DIRECTORIES_ONLY)

private fun choosePath(
    title: String,
    selectionMode: Int,
): Path? =
    JFileChooser().run {
        dialogTitle = title
        fileSelectionMode = selectionMode
        if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile.toPath() else null
    }

private fun chooseSavePath(
    title: String,
    suggestedName: String,
): Path? =
    JFileChooser().run {
        dialogTitle = title
        selectedFile = java.io.File(suggestedName)
        if (showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile.toPath() else null
    }

private fun readyReport(state: ReportState): ReportData? = (state.loadState as? ReportLoadState.Ready)?.report

private fun readyReportDirectory(state: ReportState): Path? = readyReport(state)?.session?.directory

private fun importedSessionRoot(): Path = Path.of(System.getProperty("user.home"), APP_DIRECTORY, "imports")

private const val RAW_PROTOBUF = "simpleperf.protobuf"
private const val SESSION_DIALOG_TITLE =
    "Open session, .apsession.zip, perf.data, Simpleperf protobuf, or Gecko .json.gz"
private const val APP_DIRECTORY = ".android-performance-studio"
