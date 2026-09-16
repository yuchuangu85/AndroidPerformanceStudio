package com.androidperformancestudio.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfilerMacOsControlsSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControls.kt"),
        )
    private val selectorSource =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/ui/DropdownSelector.kt"),
        )

    @Test
    fun `shared controls lock the approved compact dimensions`() {
        assertTrue(source.contains(".height(24.dp)"))
        assertTrue(source.contains("RoundedCornerShape(4.dp)"))
        assertTrue(source.contains(".border(1.dp, MaterialTheme.colorScheme.primary, shape)"))
        assertTrue(source.contains("fontSize = ViewerTypography.secondary.fontSize"))
    }

    @Test
    fun `compact button colors follow the Material primary theme color`() {
        assertTrue(source.contains("MaterialTheme.colorScheme.primary"))
        assertTrue(source.contains("MaterialTheme.colorScheme.onPrimary"))
        assertTrue(source.contains("""else {
            MaterialTheme.colorScheme.primary
        }"""))
    }

    @Test
    fun `shared controls expose all workspace primitives`() {
        assertTrue(source.contains("public fun ProfilerCompactButton("))
        assertTrue(source.contains("public fun ProfilerCompactTextField("))
        assertTrue(source.contains("public fun ProfilerToolbarStatus("))
    }

    @Test
    fun `selector gates an expanded menu and callbacks on current availability`() {
        assertTrue(selectorSource.contains("public fun <T> DropdownSelector("))
        assertTrue(selectorSource.contains("var expanded by remember { mutableStateOf(false) }"))
        assertTrue(selectorSource.contains("expanded = expanded && canExpand"))
        assertTrue(selectorSource.contains("clickable(enabled = canExpand)"))
        assertTrue(selectorSource.contains("onItemSelected(item)"))
    }
}
