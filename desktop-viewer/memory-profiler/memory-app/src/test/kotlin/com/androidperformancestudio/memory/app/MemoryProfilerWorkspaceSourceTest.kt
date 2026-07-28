package com.androidperformancestudio.memory.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryProfilerWorkspaceSourceTest {
    private val workspace =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerWorkspace.kt"),
        )
    private val backend =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/memory/app/DesktopMemoryProfilerBackend.kt"),
        )

    @Test
    fun `import uses a composition owned native dialog and backend owns io dispatch`() {
        val requestDialog = workspace.indexOf("showHprofFileDialog = true")
        val nativeDialog = workspace.indexOf("AwtWindow(")
        val launchParser = workspace.indexOf("controller.importHprof(selectedFile.toPath())")

        assertTrue(requestDialog >= 0)
        assertTrue(nativeDialog > requestDialog)
        assertTrue(launchParser > requestDialog)
        assertFalse(workspace.contains("Dispatchers.IO"))
        assertFalse(workspace.contains("chooseHprofFile"))
        assertTrue(
            workspace.contains(
                "val importDialogTitle = stringResource(Res.string.import_hprof)",
            ),
        )
        assertTrue(workspace.contains("FileDialog(parent, dialogTitle, FileDialog.LOAD)"))
        assertTrue(workspace.contains("onCloseRequest(files.firstOrNull())"))
        assertTrue(backend.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun `workspace toolbar uses shared compact chrome`() {
        assertTrue(workspace.contains("ProfilerMacOsToolbar"))
        assertTrue(workspace.contains("ProfilerCompactButton"))
        assertFalse(workspace.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(workspace.contains("import androidx.compose.material3.Button"))
        assertTrue(workspace.contains("MemoryProfilerScreen("))
    }
}
