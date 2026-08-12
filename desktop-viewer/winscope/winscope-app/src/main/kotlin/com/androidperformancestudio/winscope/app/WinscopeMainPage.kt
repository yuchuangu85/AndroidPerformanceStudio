@file:Suppress("FunctionName", "MaxLineLength", "ktlint:standard:function-naming", "ktlint:standard:max-line-length")

package com.androidperformancestudio.winscope.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.ui.ActiveWindowMenuBar
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HEADER_TOOL_BAR_HEIGHT
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.MacOSInlineTextField
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.winscope.analysis.WinscopeAnalyzer
import com.androidperformancestudio.winscope.capture.WinscopeCapabilityDetector
import com.androidperformancestudio.winscope.capture.WinscopeCaptureController
import com.androidperformancestudio.winscope.model.ProtoLogLevel
import com.androidperformancestudio.winscope.model.WinscopeAnnotation
import com.androidperformancestudio.winscope.model.WinscopeCapabilities
import com.androidperformancestudio.winscope.model.WinscopeCaptureConfig
import com.androidperformancestudio.winscope.model.WinscopeCapturePreset
import com.androidperformancestudio.winscope.model.WinscopeNode
import com.androidperformancestudio.winscope.model.WinscopePhase
import com.androidperformancestudio.winscope.model.WinscopeQueryResult
import com.androidperformancestudio.winscope.model.WinscopeSession
import com.androidperformancestudio.winscope.model.WinscopeSource
import com.androidperformancestudio.winscope.model.WinscopeState
import com.androidperformancestudio.winscope.model.WinscopeTimeline
import com.androidperformancestudio.winscope.storage.WinscopeSessionFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun FrameWindowScope.WinscopeMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onNavigateHome: (() -> Unit)? = null,
    initialTraceFile: Path? = null,
    onOpenPerfetto: (Path, Long) -> Unit = { _, _ -> },
    onOpenSource: (String, Int) -> Boolean = { _, _ -> false },
) {
    val scope = rememberCoroutineScope()
    val detector = remember { WinscopeCapabilityDetector() }
    val capture = remember { WinscopeCaptureController() }
    val sessionFiles = remember { WinscopeSessionFiles() }
    val captureState by capture.state.collectAsState()
    var adbPath by remember { mutableStateOf("adb") }
    var devices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    var capabilities by remember { mutableStateOf<WinscopeCapabilities?>(null) }
    var config by remember { mutableStateOf(WinscopeCaptureConfig()) }
    var activeSession by remember { mutableStateOf<WinscopeSession?>(null) }
    var analyzer by remember { mutableStateOf<WinscopeAnalyzer?>(null) }
    var timeline by remember { mutableStateOf<WinscopeTimeline?>(null) }
    var timestamp by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var fileDialogOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<WinscopeSession, Path>?>(null) }
    var recentSessions by remember { mutableStateOf<List<WinscopeSession>>(emptyList()) }
    val annotations = remember { mutableStateListOf<WinscopeAnnotation>() }

    fun openSession(session: WinscopeSession) {
        scope.launch {
            analyzer?.close()
            analyzer = null
            timeline = null
            activeSession = session
            annotations.clear()
            annotations.addAll(session.annotations)
            when (val opened = withContext(Dispatchers.IO) { WinscopeAnalyzer.open(session) }) {
                is StudioResult.Failure -> error = opened.error.message
                is StudioResult.Success -> {
                    analyzer = opened.value
                    when (val loaded = withContext(Dispatchers.IO) { opened.value.timeline() }) {
                        is StudioResult.Failure -> error = loaded.error.message
                        is StudioResult.Success -> {
                            timeline = loaded.value
                            timestamp = loaded.value.bounds.startNanos
                            recentSessions =
                                (listOf(session) + recentSessions)
                                    .distinctBy { it.traceFile.toAbsolutePath().normalize() }
                                    .take(10)
                            error = null
                        }
                    }
                }
            }
        }
    }

    fun refreshDevices() {
        scope.launch {
            devices =
                withContext(Dispatchers.IO) {
                    runCatching {
                        DefaultAdbClient(Path.of(adbPath))
                            .listDevices()
                            .filter { it.state == AdbDeviceState.ONLINE }
                            .map { it.serial to (it.model?.replace('_', ' ') ?: it.serial) }
                    }.getOrDefault(emptyList())
                }
            selectedSerial = selectedSerial?.takeIf { selected -> devices.any { it.first == selected } } ?: devices.singleOrNull()?.first
        }
    }

    fun importFile(path: Path) {
        scope.launch {
            when (val imported = withContext(Dispatchers.IO) { sessionFiles.import(path) }) {
                is StudioResult.Failure -> error = imported.error.message
                is StudioResult.Success -> openSession(imported.value)
            }
        }
    }

    fun chooseImportFile() {
        if (fileDialogOpen) return
        fileDialogOpen = true
        scope.launch {
            val chosen =
                try {
                    chooseOpenFile(
                        null,
                        s(language, "Import Winscope evidence", "导入 Winscope 证据"),
                        "Perfetto / Winscope ZIP",
                        "perfetto-trace",
                        "pftrace",
                        "zip",
                    )
                } finally {
                    fileDialogOpen = false
                }
            chosen?.toPath()?.let(::importFile)
        }
    }

    fun exportSession() {
        val session = activeSession ?: return
        if (fileDialogOpen) return
        fileDialogOpen = true
        scope.launch {
            val destination =
                try {
                    chooseSaveFile(null, "Export Winscope ZIP", "winscope-${session.id.take(8)}.zip")?.toPath()
                } finally {
                    fileDialogOpen = false
                } ?: return@launch
            if (session.sensitive) {
                pendingExport = session to destination
            } else {
                when (
                    val result =
                        withContext(Dispatchers.IO) {
                            sessionFiles.export(session.copy(annotations = annotations.toList()), destination, false)
                        }
                ) {
                    is StudioResult.Failure -> error = result.error.message
                    is StudioResult.Success -> error = null
                }
            }
        }
    }

    fun toggleCapture() {
        if (captureState.phase == WinscopePhase.RECORDING) {
            scope.launch { (capture.stop() as? StudioResult.Failure)?.let { error = it.error.message } }
        } else {
            val caps = capabilities ?: return
            scope.launch {
                (capture.start(Path.of(adbPath), caps, config) as? StudioResult.Failure)?.let { error = it.error.message }
            }
        }
    }

    fun takeSnapshot() {
        val caps = capabilities ?: return
        scope.launch { (capture.snapshot(Path.of(adbPath), caps) as? StudioResult.Failure)?.let { error = it.error.message } }
    }

    LaunchedEffect(Unit) {
        refreshDevices()
        initialTraceFile?.let(::importFile)
    }
    LaunchedEffect(selectedSerial, devices) {
        capabilities = null
        val serial = selectedSerial ?: return@LaunchedEffect
        capabilities =
            when (val detected = withContext(Dispatchers.IO) { detector.detect(Path.of(adbPath), serial) }) {
                is StudioResult.Success -> detected.value
                is StudioResult.Failure -> {
                    error = detected.error.message
                    null
                }
            }
    }
    LaunchedEffect(captureState.session) { captureState.session?.let(::openSession) }
    DisposableEffect(Unit) {
        onDispose {
            analyzer?.close()
            capture.close()
        }
    }

    val isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    ActiveWindowMenuBar {
        Menu(s(language, "File", "文件")) {
            Item(
                s(language, "Import…", "导入…"),
                enabled = !fileDialogOpen,
                shortcut = KeyShortcut(Key.O, ctrl = !isMacOs, meta = isMacOs),
                onClick = ::chooseImportFile,
            )
            Menu(s(language, "Export", "导出")) {
                Item(
                    s(language, "Winscope package (.zip)…", "Winscope 压缩包（.zip）…"),
                    enabled = activeSession != null && !fileDialogOpen,
                    onClick = ::exportSession,
                )
            }
            Menu(s(language, "Open Recent", "最近打开")) {
                if (recentSessions.isEmpty()) {
                    Item(s(language, "No recent files", "没有最近文件"), enabled = false, onClick = {})
                } else {
                    recentSessions.forEach { session ->
                        Item(
                            session.traceFile.fileName?.toString() ?: session.traceFile.toString(),
                            onClick = { openSession(session) },
                        )
                    }
                    Separator()
                    Item(
                        s(language, "Clear Menu", "清除菜单"),
                        onClick = { recentSessions = emptyList() },
                    )
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().onWinscopeKeys(timeline, timestamp, { timestamp = it })) {
        HeaderToolbar(language = language, onNavigateHome = onNavigateHome, onNavigateSettings = null) {
            Text("Winscope", fontWeight = FontWeight.SemiBold)
            HeaderSpacer()
            val selectedDevice = devices.firstOrNull { it.first == selectedSerial }
            DropdownSelector(
                items = devices,
                selectedItem = selectedDevice,
                onItemSelected = { selectedSerial = it.first },
                itemLabel = { "${it.second} · ${it.first}" },
                selectedItemLabel = { it.second },
                placeholder = s(language, "Select device", "选择设备"),
                modifier = Modifier.width(190.dp),
                selectorDescription = s(language, "Winscope device", "Winscope 设备"),
                fillWidth = true,
            )
            HeaderSpacer()
            MacOSTextButton(s(language, "Refresh", "刷新"), onClick = ::refreshDevices)
            HeaderSpacer()
            val recording = captureState.phase == WinscopePhase.RECORDING
            MacOSTextButton(
                if (recording) s(language, "Stop", "停止") else s(language, "Start", "开始"),
                onClick = ::toggleCapture,
                enabled = capabilities?.liveCaptureSupported == true,
                primary = true,
            )
            HeaderSpacer()
            MacOSTextButton(
                s(language, "Snapshot", "快照"),
                onClick = ::takeSnapshot,
                enabled = capabilities?.liveCaptureSupported == true && !recording,
            )
            Spacer(Modifier.weight(1f))
            activeSession?.let { session ->
                Spacer(Modifier.width(6.dp))
                MacOSTextButton(
                    s(language, "Open in Perfetto", "在 Perfetto 中打开"),
                    onClick = { onOpenPerfetto(session.traceFile, timestamp) },
                )
            }
        }
        error?.let { ErrorBanner(it) { error = null } }
        Row(Modifier.fillMaxSize()) {
            CapturePanel(
                language = language,
                adbPath = adbPath,
                onAdbPath = { adbPath = it },
                capabilities = capabilities,
                config = config,
                onConfig = { config = it },
                captureState = captureState.message,
                onRoot = {
                    val serial = selectedSerial ?: return@CapturePanel
                    scope.launch {
                        when (val rooted = withContext(Dispatchers.IO) { detector.restartAsRoot(Path.of(adbPath), serial) }) {
                            is StudioResult.Failure -> error = rooted.error.message
                            is StudioResult.Success ->
                                capabilities =
                                    (detector.detect(Path.of(adbPath), serial) as? StudioResult.Success)?.value
                        }
                    }
                },
            )
            if (activeSession == null) {
                EmptyWorkspace(language)
            } else {
                ViewerWorkspace(
                    language,
                    activeSession!!,
                    analyzer,
                    timeline,
                    timestamp,
                    { timestamp = it },
                    annotations,
                    onOpenSource,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }

    pendingExport?.let { (session, destination) ->
        AlertDialog(
            onDismissRequest = { pendingExport = null },
            title = { Text(s(language, "Sensitive evidence", "敏感证据")) },
            text = {
                Text(
                    s(
                        language,
                        "This package contains Input, screen media, or ProtoLog stack evidence. Export the raw evidence?",
                        "该压缩包包含 Input、屏幕媒体或 ProtoLog 堆栈证据。是否导出原始证据？",
                    ),
                )
            },
            confirmButton = {
                MacOSTextButton(
                    s(language, "Export", "导出"),
                    onClick = {
                        pendingExport = null
                        scope.launch {
                            when (
                                val result =
                                    withContext(Dispatchers.IO) {
                                        sessionFiles.export(session.copy(annotations = annotations.toList()), destination, true)
                                    }
                            ) {
                                is StudioResult.Failure -> error = result.error.message
                                is StudioResult.Success -> error = null
                            }
                        }
                    },
                    primary = true,
                )
            },
            dismissButton = {
                MacOSTextButton(s(language, "Cancel", "取消"), onClick = { pendingExport = null })
            },
        )
    }
}

@Composable
private fun CapturePanel(
    language: UiLanguage,
    adbPath: String,
    onAdbPath: (String) -> Unit,
    capabilities: WinscopeCapabilities?,
    config: WinscopeCaptureConfig,
    onConfig: (WinscopeCaptureConfig) -> Unit,
    captureState: String,
    onRoot: () -> Unit,
) {
    Column(
        Modifier
            .width(
                320.dp,
            ).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(s(language, "Live capture · Android 15+", "实时采集 · Android 15+"), fontWeight = FontWeight.SemiBold)
        MacOSInlineTextField("ADB", adbPath, onAdbPath, modifier = Modifier.fillMaxWidth())
        capabilities?.let { caps ->
            val root =
                when {
                    caps.device.rootActive -> "ACTIVE"
                    caps.device.rootAvailable -> "AVAILABLE"
                    else -> "UNAVAILABLE"
                }
            Text("Android ${caps.device.androidSdk} · ${caps.device.buildType} · root $root", style = MaterialTheme.typography.bodySmall)
            if (caps.device.rootAvailable) MacOSTextButton("adb root", onClick = onRoot)
            caps.limitations.take(4).forEach {
                Text("• ${it.message}", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text(s(language, "Capture preset", "采集预设"), fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WinscopeCapturePreset.entries.forEach { preset ->
                FilterChip(config.preset == preset, { onConfig(config.copy(preset = preset)) }, {
                    Text(
                        if (preset ==
                            WinscopeCapturePreset.BALANCED
                        ) {
                            s(language, "Balanced", "均衡")
                        } else {
                            s(language, "Full detail", "完整细节")
                        },
                    )
                })
            }
        }
        MacOSInlineTextField(
            s(language, "Duration (1–600 s)", "时长（1–600 秒）"),
            config.durationSeconds.toString(),
            { text -> text.toIntOrNull()?.takeIf { it in 1..600 }?.let { onConfig(config.copy(durationSeconds = it)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s(language, "Sources", "数据源"), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            MacOSTextButton(
                s(language, "Enable all", "全部开启"),
                onClick = {
                    onConfig(
                        config.copy(
                            requestedSources = WinscopeCaptureConfig.ALL_SOURCES,
                            protoLogEnableAll = true,
                            protoLogStacktraces = true,
                            protoLogLevel = ProtoLogLevel.VERBOSE,
                        ),
                    )
                },
            )
            MacOSTextButton(
                s(language, "Default", "默认"),
                onClick = {
                    onConfig(WinscopeCaptureConfig(durationSeconds = config.durationSeconds, preset = config.preset))
                },
            )
        }
        WinscopeSource.entries.filter { it != WinscopeSource.SCREENSHOT }.forEach { source ->
            val checked = source in config.requestedSources
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth().clickable {
                        onConfig(
                            config.copy(
                                requestedSources =
                                    if (checked) {
                                        config.requestedSources -
                                            source
                                    } else {
                                        config.requestedSources + source
                                    },
                            ),
                        )
                    },
            ) {
                Checkbox(checked, null)
                Text(source.displayName, modifier = Modifier.weight(1f))
                if (source in
                    setOf(WinscopeSource.INPUT, WinscopeSource.SCREEN_RECORDING)
                ) {
                    Text(
                        s(language, "sensitive", "敏感"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (WinscopeSource.SCREEN_RECORDING in config.requestedSources && capabilities?.displays?.size.orZero() > 1) {
            var displayMenu by remember { mutableStateOf(false) }
            Box {
                MacOSTextButton(
                    capabilities?.displays?.firstOrNull { it.physicalId == config.selectedDisplayId }?.name
                        ?: s(language, "Active display", "活动显示器"),
                    onClick = { displayMenu = true },
                )
                DropdownMenu(displayMenu, { displayMenu = false }) {
                    DropdownMenuItem({ Text(s(language, "Active display", "活动显示器")) }, {
                        displayMenu = false
                        onConfig(config.copy(selectedDisplayId = null))
                    })
                    capabilities?.displays?.forEach { display ->
                        DropdownMenuItem({ Text("${display.name} · ${display.physicalId}") }, {
                            displayMenu = false
                            onConfig(config.copy(selectedDisplayId = display.physicalId))
                        })
                    }
                }
            }
        }
        if (WinscopeSource.INPUT in
            config.requestedSources
        ) {
            Text(
                s(
                    language,
                    "Input uses TRACE_ALL for this session only and can capture sensitive interaction data.",
                    "Input 仅本次会话使用 TRACE_ALL，可能采集敏感交互数据。",
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (WinscopeSource.PROTO_LOG in config.requestedSources) {
            Text("ProtoLog ${config.protoLogLevel}+")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ProtoLogLevel.entries.forEach { level ->
                    FilterChip(
                        config.protoLogLevel == level,
                        { onConfig(config.copy(protoLogLevel = level)) },
                        { Text(level.name.take(1)) },
                    )
                }
            }
            ToggleRow(
                s(language, "All groups / levels", "全部组/级别"),
                config.protoLogEnableAll,
            ) { onConfig(config.copy(protoLogEnableAll = it)) }
            ToggleRow(
                s(language, "Stack traces (sensitive)", "堆栈（敏感）"),
                config.protoLogStacktraces,
            ) { onConfig(config.copy(protoLogStacktraces = it)) }
        }
        if (captureState.isNotBlank()) Text(captureState, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ViewerWorkspace(
    language: UiLanguage,
    session: WinscopeSession,
    analyzer: WinscopeAnalyzer?,
    timeline: WinscopeTimeline?,
    timestamp: Long,
    onTimestamp: (Long) -> Unit,
    annotations: MutableList<WinscopeAnnotation>,
    onOpenSource: (String, Int) -> Boolean,
    modifier: Modifier,
) {
    var tab by remember { mutableStateOf(WinscopeSource.WINDOW_MANAGER) }
    val tabs =
        remember(session, timeline) {
            val found = timeline?.entries?.keys.orEmpty() + session.availableSources
            (
                listOf(
                    WinscopeSource.WINDOW_MANAGER,
                    WinscopeSource.SURFACE_FLINGER,
                    WinscopeSource.TRANSACTIONS,
                    WinscopeSource.TRANSITIONS,
                    WinscopeSource.EVENT_LOG,
                    WinscopeSource.INPUT,
                    WinscopeSource.IME,
                    WinscopeSource.PROTO_LOG,
                    WinscopeSource.VIEW_CAPTURE,
                ) +
                    found
            ).distinct()
        }
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        SessionBanner(session)
        TimelinePanel(timeline, timestamp, onTimestamp, annotations)
        SecondaryTabRow(tabs.indexOf(tab).coerceAtLeast(0), modifier = Modifier.height(HEADER_TOOL_BAR_HEIGHT)) {
            tabs.forEach { source -> Tab(tab == source, { tab = source }, text = { Text(source.displayName, maxLines = 1) }) }
        }
        if (analyzer ==
            null
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(s(language, "Opening trace…", "正在打开轨迹…")) }
        } else {
            when (tab) {
                WinscopeSource.WINDOW_MANAGER, WinscopeSource.SURFACE_FLINGER, WinscopeSource.VIEW_CAPTURE ->
                    StateWorkspace(
                        analyzer,
                        tab,
                        timestamp,
                        session,
                    )
                WinscopeSource.PROTO_LOG, WinscopeSource.IME, WinscopeSource.INPUT, WinscopeSource.EVENT_LOG ->
                    LogWorkspace(
                        analyzer,
                        tab,
                        timeline,
                        onTimestamp,
                        onOpenSource,
                    )
                else -> SearchWorkspace(analyzer, onTimestamp)
            }
        }
    }
}

@Composable
private fun TimelinePanel(
    timeline: WinscopeTimeline?,
    timestamp: Long,
    onTimestamp: (Long) -> Unit,
    annotations: MutableList<WinscopeAnnotation>,
) {
    var expanded by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var jump by remember(timestamp) { mutableStateOf(timestamp.toString()) }
    val bounds = timeline?.bounds
    LaunchedEffect(playing, speed, bounds) {
        while (playing && bounds != null) {
            delay(33)
            val next = timestamp + (33_000_000L * speed).toLong()
            onTimestamp(if (next > bounds.endNanos) bounds.startNanos else next)
        }
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DropdownSelector(
                items = emptyList<Boolean>(),
                selectedItem = expanded,
                onItemSelected = {},
                itemLabel = { "Timeline" },
                placeholder = "Timeline",
                modifier = Modifier.width(90.dp),
                selectorDescription = "Timeline",
                fillWidth = true,
                onControlClick = { expanded = !expanded },
            )
            MacOSTextButton("◀", onClick = { bounds?.let { onTimestamp(max(it.startNanos, timestamp - 1_000_000L)) } })
            MacOSTextButton(if (playing) "Ⅱ" else "▶", onClick = { playing = !playing }, primary = true, modifier = Modifier.width(32.dp))
            MacOSTextButton("▶|", onClick = { bounds?.let { onTimestamp(min(it.endNanos, timestamp + 1_000_000L)) } })
            listOf(0.25f, 0.5f, 1f, 2f, 4f).forEach { value -> FilterChip(speed == value, { speed = value }, { Text("$value×") }) }
            MacOSInlineTextField("timestamp ns", jump, { jump = it }, modifier = Modifier.width(210.dp))
            MacOSTextButton("Go", onClick = { jump.toLongOrNull()?.let(onTimestamp) })
            MacOSTextButton("☆", onClick = { annotations += WinscopeAnnotation(timestamp, "Bookmark ${annotations.size + 1}") })
        }
        if (expanded && bounds != null) {
            val fraction =
                if (bounds.endNanos ==
                    bounds.startNanos
                ) {
                    0f
                } else {
                    ((timestamp - bounds.startNanos).toDouble() / (bounds.endNanos - bounds.startNanos)).toFloat().coerceIn(0f, 1f)
                }
            Slider(fraction, { onTimestamp(bounds.startNanos + ((bounds.endNanos - bounds.startNanos) * it).toLong()) })
            val cursorColor = MaterialTheme.colorScheme.error
            Canvas(
                Modifier.fillMaxWidth().height(56.dp).pointerInput(bounds) {
                    detectTapGestures { point ->
                        onTimestamp(
                            bounds.startNanos + ((bounds.endNanos - bounds.startNanos) * point.x / size.width).toLong(),
                        )
                    }
                },
            ) {
                timeline.entries.entries.forEachIndexed { row, (_, entries) ->
                    entries.forEach { entry ->
                        val x =
                            (
                                (entry.timestampNanos - bounds.startNanos).toDouble() / max(1, bounds.endNanos - bounds.startNanos) *
                                    size.width
                            ).toFloat()
                        drawLine(Color(0xff6c8cff), Offset(x, row * 6f), Offset(x, row * 6f + 5f), 2f)
                    }
                }
                annotations.forEach { mark ->
                    val x =
                        (
                            (mark.timestampNanos - bounds.startNanos).toDouble() /
                                max(
                                    1,
                                    bounds.endNanos - bounds.startNanos,
                                ) * size.width
                        ).toFloat()
                    drawLine(Color(0xffffb74d), Offset(x, 0f), Offset(x, size.height), 2f)
                }
                drawLine(cursorColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), 2f)
            }
        }
    }
}

@Composable
private fun StateWorkspace(
    analyzer: WinscopeAnalyzer,
    source: WinscopeSource,
    timestamp: Long,
    session: WinscopeSession,
) {
    var state by remember { mutableStateOf<WinscopeState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<WinscopeNode?>(null) }
    var search by remember { mutableStateOf("") }
    var visibleOnly by remember { mutableStateOf(false) }
    var changedOnly by remember { mutableStateOf(false) }
    var flat by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var mode3d by remember { mutableStateOf(false) }
    LaunchedEffect(analyzer, source, timestamp) {
        when (val loaded = withContext(Dispatchers.IO) { analyzer.state(source, timestamp) }) {
            is StudioResult.Failure -> {
                state = null
                error = loaded.error.message
            }
            is StudioResult.Success -> {
                state = loaded.value
                error = null
                selected =
                    selected?.let { old -> loaded.value.nodes.firstOrNull { it.id == old.id } }
            }
        }
    }
    val nodes =
        remember(state, search, visibleOnly, changedOnly, regex) {
            state?.nodes.orEmpty().filter { node ->
                (!visibleOnly || node.visible == true) && (!changedOnly || node.change.name != "NONE") && matches(node, search, regex)
            }
        }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MacOSInlineTextField(
                "name / property",
                search,
                { search = it },
                modifier = Modifier.width(240.dp),
            )
            ToggleChip("Regex", regex) { regex = it }
            ToggleChip("Visible only", visibleOnly) { visibleOnly = it }
            ToggleChip("Changed only", changedOnly) { changedOnly = it }
            ToggleChip("Flat", flat) { flat = it }
            ToggleChip("3D stack", mode3d) { mode3d = it }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(10.dp)) }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(310.dp).fillMaxHeight().border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                items(nodes, key = { it.id }) { node ->
                    val depth = if (flat) 0 else hierarchyDepth(node, state?.nodes.orEmpty())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = node }
                            .background(
                                if (selected?.id ==
                                    node.id
                                ) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                            ).padding(start = (8 + depth * 12).dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    ) {
                        Text(if (node.visible == false) "○ " else "● ", modifier = Modifier.alpha(if (node.visible == false) 0.45f else 1f))
                        Text(node.name, maxLines = 1, modifier = Modifier.alpha(if (node.visible == false) 0.55f else 1f))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (mode3d) {
                    StackCanvas(nodes, selected?.id, Modifier.weight(1f).fillMaxWidth())
                } else {
                    RectCanvas(nodes, selected?.id, { selected = it }, Modifier.weight(1f).fillMaxWidth())
                }
                MediaPanel(session)
            }
            PropertiesPanel(selected, Modifier.width(330.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun RectCanvas(
    nodes: List<WinscopeNode>,
    selectedId: String?,
    onSelect: (WinscopeNode) -> Unit,
    modifier: Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.background(Color(0xff12151b)).pointerInput(nodes) {
            detectTransformGestures { _, delta, zoom, _ ->
                scale =
                    (scale * zoom).coerceIn(0.1f, 10f)
                ; pan += delta
            }
        },
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(nodes, scale, pan) {
                detectTapGestures { tap ->
                    nodes
                        .asReversed()
                        .firstOrNull { node ->
                            node.bounds?.let {
                                tap.x in
                                    (it.left * scale + pan.x)..(it.right * scale + pan.x) &&
                                    tap.y in (it.top * scale + pan.y)..(it.bottom * scale + pan.y)
                            } ==
                                true
                        }?.let(onSelect)
                }
            },
        ) {
            nodes.sortedBy(WinscopeNode::z).forEach { node ->
                node.bounds?.let { rect ->
                    val color = layerColor(node.z)
                    drawRect(
                        color.copy(
                            alpha =
                                if (node.visible ==
                                    false
                                ) {
                                    0.12f
                                } else {
                                    0.35f
                                },
                        ),
                        Offset(rect.left * scale + pan.x, rect.top * scale + pan.y),
                        Size(
                            rect.width * scale,
                            rect.height * scale,
                        ),
                    )
                    drawRect(
                        if (selectedId ==
                            node.id
                        ) {
                            Color.White
                        } else {
                            color
                        },
                        Offset(rect.left * scale + pan.x, rect.top * scale + pan.y),
                        Size(
                            rect.width * scale,
                            rect.height * scale,
                        ),
                        style = Stroke(if (selectedId == node.id) 3f else 1f),
                    )
                }
            }
        }
        MacOSTextButton(
            "Reset",
            onClick = {
                scale = 1f
                pan = Offset.Zero
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
    }
}

@Composable
private fun StackCanvas(
    nodes: List<WinscopeNode>,
    selectedId: String?,
    modifier: Modifier,
) {
    var scale by remember { mutableFloatStateOf(0.75f) }
    var pan by remember { mutableStateOf(Offset(80f, 60f)) }
    var rotation by remember { mutableFloatStateOf(22f) }
    var spacing by remember { mutableFloatStateOf(9f) }
    var opacity by remember { mutableFloatStateOf(0.35f) }
    var wireframe by remember { mutableStateOf(false) }
    Column(modifier.background(Color(0xff111319))) {
        Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rotation ${rotation.toInt()}°", color = Color.LightGray)
            Slider(rotation, { rotation = it }, valueRange = 0f..45f, modifier = Modifier.width(120.dp))
            Text("Spacing", color = Color.LightGray)
            Slider(spacing, { spacing = it }, valueRange = 1f..30f, modifier = Modifier.width(100.dp))
            Text("Opacity", color = Color.LightGray)
            Slider(opacity, { opacity = it }, modifier = Modifier.width(100.dp))
            ToggleChip("Wireframe", wireframe) { wireframe = it }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(nodes) {
                    detectTransformGestures { _, delta, zoom, _ ->
                        scale =
                            (scale * zoom).coerceIn(0.1f, 5f)
                        ; pan += delta
                    }
                }.pointerInput(nodes) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        pan +=
                            drag
                    }
                },
        ) {
            val radians = Math.toRadians(rotation.toDouble())
            nodes.sortedBy(WinscopeNode::z).forEachIndexed { index, node ->
                node.bounds?.let { rect ->
                    val lift = index * spacing
                    val x = rect.left * scale + pan.x + (cos(radians) * lift).toFloat()
                    val y = rect.top * scale + pan.y - (sin(radians) * lift).toFloat()
                    val color = layerColor(index.toFloat())
                    if (!wireframe) drawRect(color.copy(alpha = opacity), Offset(x, y), Size(rect.width * scale, rect.height * scale))
                    drawRect(
                        if (selectedId ==
                            node.id
                        ) {
                            Color.White
                        } else {
                            color
                        },
                        Offset(x, y),
                        Size(rect.width * scale, rect.height * scale),
                        style =
                            Stroke(
                                if (selectedId ==
                                    node.id
                                ) {
                                    3f
                                } else {
                                    1f
                                },
                                pathEffect =
                                    if (node.visible ==
                                        false
                                    ) {
                                        PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                                    } else {
                                        null
                                    },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PropertiesPanel(
    node: WinscopeNode?,
    modifier: Modifier,
) {
    var search by remember { mutableStateOf("") }
    Column(modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(8.dp)) {
        Text("Properties", fontWeight = FontWeight.SemiBold)
        if (node == null) {
            Text("Select a node", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(node.name, fontWeight = FontWeight.Medium)
            Text("id: ${node.id}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("visible: ${node.visible?.toString() ?: "unrecorded"}")
            Text("bounds: ${node.bounds ?: "unrecorded"}")
            Text("z: ${node.z}")
            MacOSInlineTextField(
                "Filter properties",
                search,
                { search = it },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(node.properties.filter { search.isBlank() || it.path.contains(search, true) }) { property ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (property.changed) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
                            ).padding(vertical = 4.dp),
                    ) {
                        Text(property.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(if (property.recorded) property.value ?: "null" else "unrecorded")
                        property.previousValue?.let { Text("previous: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogWorkspace(
    analyzer: WinscopeAnalyzer,
    source: WinscopeSource,
    timeline: WinscopeTimeline?,
    onTimestamp: (Long) -> Unit,
    onOpenSource: (String, Int) -> Boolean,
) {
    var rows by remember { mutableStateOf(emptyList<com.androidperformancestudio.winscope.model.WinscopeLogRow>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val bounds = timeline?.bounds
    LaunchedEffect(analyzer, source, bounds) {
        if (bounds != null) {
            when (val loaded = withContext(Dispatchers.IO) { analyzer.logs(source, bounds.startNanos, bounds.endNanos) }) {
                is StudioResult.Success -> rows = loaded.value
                is StudioResult.Failure -> error = loaded.error.message
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                Column(Modifier.fillMaxWidth().clickable { onTimestamp(row.timestampNanos) }.padding(7.dp)) {
                    Text(
                        "${row.timestampNanos}  ${row.columns["level"].orEmpty()} ${row.columns["tag"].orEmpty()}",
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(row.columns["message"] ?: row.columns["detail"].orEmpty())
                    row.columns["location"]?.takeIf(String::isNotBlank)?.let { location ->
                        MacOSTextButton(
                            location,
                            onClick = {
                                val line =
                                    location.substringAfterLast(':').toIntOrNull() ?: 1
                                if (!onOpenSource(
                                        location.substringBeforeLast(':'),
                                        line,
                                    )
                                ) {
                                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                        java.awt.datatransfer.StringSelection(location),
                                        null,
                                    )
                                }
                            },
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SearchWorkspace(
    analyzer: WinscopeAnalyzer,
    onTimestamp: (Long) -> Unit,
) {
    var sql by remember { mutableStateOf("SELECT ts, name FROM slice ORDER BY ts LIMIT 100") }
    var result by remember { mutableStateOf<WinscopeQueryResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        MacOSInlineTextField(
            "Read-only Trace SQL · SELECT / WITH",
            sql,
            { sql = it },
            modifier = Modifier.fillMaxWidth(),
        )
        MacOSTextButton(
            "Run",
            onClick = {
                scope.launch {
                    when (val queried = withContext(Dispatchers.IO) { analyzer.runReadOnlySql(sql) }) {
                        is StudioResult.Success -> {
                            result =
                                queried.value
                            error = null
                        }
                        is StudioResult.Failure -> error = queried.error.message
                    }
                }
            },
            primary = true,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { table ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                table.columns.forEach { Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.width(180.dp)) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(table.rows.take(10_000)) { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val index = table.columns.indexOf("ts")
                                row.getOrNull(index)?.toLongOrNull()?.let(onTimestamp)
                            }.padding(vertical = 3.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        row.forEach {
                            Text(
                                it ?: "NULL",
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(180.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPanel(session: WinscopeSession) {
    val screenshot =
        remember(session.screenshotFile) {
            session.screenshotFile?.takeIf(Files::isRegularFile)?.let {
                runCatching { SkiaImage.makeFromEncoded(Files.readAllBytes(it)).toComposeImageBitmap() }.getOrNull()
            }
        }
    if (screenshot != null) Image(screenshot, "Captured screen", Modifier.fillMaxWidth().height(160.dp).background(Color.Black))
    session.recordingFile?.takeIf(Files::isRegularFile)?.let { recording ->
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Screen recording · ${recording.fileName}", modifier = Modifier.weight(1f))
            MacOSTextButton("Open", onClick = { runCatching { Desktop.getDesktop().open(recording.toFile()) } })
        }
    }
}

@Composable private fun EmptyWorkspace(language: UiLanguage) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Winscope Workspace", style = MaterialTheme.typography.headlineMedium)
            Text(
                s(
                    language,
                    "Capture Android 15+ window state or import a Perfetto trace / ZIP.",
                    "采集 Android 15+ 窗口状态，或导入 Perfetto 轨迹 / ZIP。",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

@Composable private fun SessionBanner(session: WinscopeSession) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (session.completeness.name ==
                    "PARTIAL"
                ) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ).padding(5.dp),
    ) {
        Text("${session.traceFile.fileName} · ${session.completeness}", modifier = Modifier.weight(1f))
        if (session.sensitive) Text("Sensitive evidence", color = MaterialTheme.colorScheme.error)
        session.limitations.take(1).forEach { Text(" · ${it.message}") }
    }
}

@Composable private fun ErrorBanner(
    message: String,
    dismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        MacOSTextButton("×", onClick = dismiss)
    }
}

@Composable private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(label)
    }
}

@Composable private fun ToggleChip(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) = FilterChip(checked, {
    onChecked(!checked)
}, { Text(label) })

private fun s(
    language: UiLanguage,
    english: String,
    chinese: String,
): String =
    if (language ==
        UiLanguage.SIMPLIFIED_CHINESE
    ) {
        chinese
    } else {
        english
    }

private fun layerColor(z: Float): Color {
    val palette = listOf(Color(0xff5c6bc0), Color(0xff26a69a), Color(0xffff7043), Color(0xffab47bc), Color(0xffffca28))
    return palette[
        (
            kotlin.math.abs(z.toInt()) %
                palette.size
        ),
    ]
}

private fun Int?.orZero(): Int = this ?: 0

private fun hierarchyDepth(
    node: WinscopeNode,
    all: List<WinscopeNode>,
): Int {
    val byId = all.associateBy(WinscopeNode::id)
    var parent = node.parentId
    var depth = 0
    while (parent !=
        null &&
        depth < 24
    ) {
        depth++
        parent = byId[parent]?.parentId
    }
    return depth
}

private fun matches(
    node: WinscopeNode,
    query: String,
    regex: Boolean,
): Boolean {
    if (query.isBlank()) return true
    val haystack =
        node.name + "\n" + node.properties.joinToString("\n") { "${it.path}=${it.value}" }
    return if (regex) {
        runCatching {
            Regex(query, RegexOption.IGNORE_CASE).containsMatchIn(haystack)
        }.getOrDefault(false)
    } else {
        haystack.contains(query, true)
    }
}

private fun Modifier.onWinscopeKeys(
    timeline: WinscopeTimeline?,
    timestamp: Long,
    onTimestamp: (Long) -> Unit,
): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type !=
            KeyEventType.KeyDown ||
            timeline == null
        ) {
            false
        } else {
            when (event.key) {
                Key.DirectionLeft -> {
                    onTimestamp(
                        max(
                            timeline.bounds.startNanos,
                            timestamp - 1_000_000L,
                        ),
                    )
                    true
                }
                Key.DirectionRight -> {
                    onTimestamp(min(timeline.bounds.endNanos, timestamp + 1_000_000L))
                    true
                }
                else -> false
            }
        }
    }
