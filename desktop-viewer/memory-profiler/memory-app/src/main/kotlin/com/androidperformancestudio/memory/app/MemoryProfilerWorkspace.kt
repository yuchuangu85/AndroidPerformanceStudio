@file:Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.AwtWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.memory.presentation.MemoryProfilerActions
import com.androidperformancestudio.memory.presentation.MemoryProfilerScreen
import com.androidperformancestudio.ui.ProfilerHomeButton
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

@Composable
public fun FrameWindowScope.MemoryProfilerWorkspace(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
    highlightClassName: String? = null,
) {
    val controller = remember { MemoryProfilerController(DesktopMemoryProfilerBackend()) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val loaded = controller.loadedHeap
    var showHprofFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(highlightClassName) {
        highlightClassName?.let(controller::highlightClass)
    }

    Column(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(MEMORY_WORKSPACE_TOOLBAR_HEIGHT_DP.dp)
                        .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfilerHomeButton(
                    contentDescription = if (chinese) "返回主页" else "Back to home",
                    onClick = onBack,
                )
                OutlinedButton(
                    onClick = { scope.launch { controller.refreshDevices() } },
                    modifier = Modifier.height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp),
                    shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
                    contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
                ) {
                    Text(if (chinese) "刷新设备" else "Refresh Devices", fontSize = 11.sp)
                }
                Button(
                    enabled = loaded?.heapDump?.rawHprofFile != null,
                    onClick = { chooseSaveFile(window, "heap-raw.hprof")?.let(controller::exportRaw) },
                    modifier = Modifier.height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp),
                    shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
                    contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
                ) { Text(if (chinese) "导出原始 HPROF" else "Export Raw HPROF", fontSize = 11.sp) }
                Button(
                    enabled = loaded?.heapDump?.convertedHprofFile != null,
                    onClick = { chooseSaveFile(window, "heap-standard.hprof")?.let(controller::exportConverted) },
                    modifier = Modifier.height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp),
                    shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
                    contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
                ) { Text(if (chinese) "导出标准 HPROF" else "Export Standard HPROF", fontSize = 11.sp) }
                Button(
                    enabled = loaded != null,
                    onClick = { chooseSaveFile(window, "class-histogram.csv")?.let(controller::exportHistogram) },
                    modifier = Modifier.height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp),
                    shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
                    contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
                ) { Text(if (chinese) "导出 CSV" else "Export CSV", fontSize = 11.sp) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        MemoryProfilerScreen(
            state = state,
            actions =
                MemoryProfilerActions(
                    onSelectDevice = { serial -> scope.launch { controller.selectDevice(serial) } },
                    onSelectProcess = controller::selectProcess,
                    onDumpHeap = { scope.launch { controller.dumpHeap() } },
                    onImportHprof = { showHprofFileDialog = true },
                    onSortHistogram = controller::sort,
                    onRetry = { scope.launch { controller.refreshDevices() } },
                    onHighlightClass = controller::highlightClass,
                ),
            modifier = Modifier.weight(1f),
        )
    }

    if (showHprofFileDialog) {
        HprofOpenFileDialog(
            parent = window,
            onCloseRequest = { selectedFile ->
                showHprofFileDialog = false
                if (selectedFile != null) {
                    scope.launch {
                        controller.importHprof(selectedFile.toPath())
                    }
                }
            },
        )
    }
}

private const val MEMORY_WORKSPACE_TOOLBAR_HEIGHT_DP = 29
private const val MEMORY_TOOLBAR_BUTTON_HEIGHT_DP = 22
private const val MEMORY_TOOLBAR_BUTTON_RADIUS_DP = 7
private val MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 8.dp)

@Composable
private fun HprofOpenFileDialog(
    parent: Frame,
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(parent, "Import HPROF", FileDialog.LOAD) {
                init {
                    isMultipleMode = false
                    filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".hprof", ignoreCase = true) }
                }

                override fun setVisible(value: Boolean) {
                    super.setVisible(value)
                    if (value) {
                        onCloseRequest(files.firstOrNull())
                    }
                }
            }
        },
        dispose = FileDialog::dispose,
    )
}

private fun chooseSaveFile(
    parent: Component,
    defaultName: String,
): java.nio.file.Path? =
    JFileChooser().run {
        dialogTitle = "Export Memory Profiler Data"
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile.toPath() else null
    }
