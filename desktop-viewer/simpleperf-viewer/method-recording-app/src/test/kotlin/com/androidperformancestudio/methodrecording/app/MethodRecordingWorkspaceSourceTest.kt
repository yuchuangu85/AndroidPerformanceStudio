package com.androidperformancestudio.methodrecording.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MethodRecordingWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingMainPage.kt"),
        )

    @Test
    fun `workspace uses shared header toolbar and preserves navigation`() {
        assertTrue(source.contains("HeaderToolbar("))
        assertTrue(source.contains("onNavigateHome = onBack"))
        assertTrue(source.contains("DropdownSelector("))
        assertTrue(source.contains("ProfilerCompactButton("))
        assertFalse(source.contains("ProfilerMacOsToolbar"))
        assertFalse(source.contains("HomeButton("))
    }
}
