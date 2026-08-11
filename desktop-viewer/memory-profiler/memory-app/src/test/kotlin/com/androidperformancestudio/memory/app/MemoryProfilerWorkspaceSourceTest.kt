package com.androidperformancestudio.memory.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryProfilerWorkspaceSourceTest {
    private val workspace =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerMainPage.kt"),
        )
    private val backend =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/memory/app/DesktopMemoryProfilerBackend.kt"),
        )
    private val desktopFileDialogs =
        Files.readString(
            Path.of("../../ui-components/src/main/kotlin/com/androidperformancestudio/ui/DesktopFileDialogs.kt"),
        )

    @Test
    fun `import uses a composition owned native dialog and backend owns io dispatch`() {
        val requestDialog = workspace.indexOf("showHprofFileDialog = true")
        val nativeDialog = workspace.indexOf("DesktopOpenFileDialog(")
        val launchParser = workspace.indexOf("controller.importHprof(selectedFile.toPath())")

        assertTrue(requestDialog >= 0)
        assertTrue(nativeDialog > requestDialog)
        assertTrue(launchParser > requestDialog)
        assertFalse(workspace.contains("Dispatchers.IO"))
        assertFalse(workspace.contains("chooseHprofFile"))
        assertTrue(desktopFileDialogs.contains("AwtWindow("))
        assertTrue(desktopFileDialogs.contains("onCloseRequest(files.firstOrNull())"))
        assertTrue(backend.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun `workspace toolbar uses shared compact chrome`() {
        assertTrue(workspace.contains("HeaderToolbar("))
        assertFalse(workspace.contains("ProfilerMacOsToolbar"))
        assertTrue(workspace.contains("ProfilerCompactButton"))
        assertFalse(workspace.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(workspace.contains("import androidx.compose.material3.Button"))
        assertTrue(workspace.contains("MemoryProfilerScreen("))
    }

    @Test
    fun `device and process selectors share the top toolbar row with home`() {
        val toolbar =
            workspace
                .substringAfter("HeaderToolbar(")
                .substringBefore("        }\n        HorizontalDivider")

        val home = toolbar.indexOf("onNavigateHome = onBack")
        val selectors = toolbar.indexOf("MemoryProfilerToolbarSelectors(")
        val refresh = toolbar.indexOf("ProfilerCompactButton(")
        val spacer = toolbar.indexOf("Spacer(Modifier.weight(1f))")
        val dumpHeap = toolbar.indexOf("MemoryProfilerDumpHeapButton(")

        assertTrue(home >= 0)
        assertTrue(selectors > home)
        assertTrue(refresh > selectors)
        assertTrue(spacer > refresh)
        assertTrue(dumpHeap > spacer)
        assertTrue(toolbar.contains("onSelectDevice = { serial ->"))
        assertTrue(toolbar.contains("onSelectProcess = controller::selectProcess"))
        assertTrue(toolbar.contains("onDumpHeap = { scope.launch { controller.dumpHeap() } }"))
    }

    @Test
    fun `workspace moves import and export actions into the file menu`() {
        assertTrue(workspace.contains("MemoryProfilerFileMenuBar("))
        assertTrue(workspace.contains("onImportHprof = { showHprofFileDialog = true }"))
        assertTrue(workspace.contains("onExportRawHprof ="))
        assertTrue(workspace.contains("onExportStandardHprof ="))
        assertTrue(workspace.contains("onExportCsv ="))
        assertFalse(workspace.contains("text = localizedStringResource(Res.string.export_raw_hprof"))
        assertFalse(workspace.contains("text = localizedStringResource(Res.string.export_standard_hprof"))
        assertFalse(workspace.contains("text = localizedStringResource(Res.string.export_csv"))
    }
}
