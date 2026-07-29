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

    @Test
    fun `shared controls lock the approved compact dimensions`() {
        assertTrue(source.contains("PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP: Int = 40"))
        assertTrue(source.contains("PROFILER_SECONDARY_TOOLBAR_HEIGHT_DP: Int = 28"))
        assertTrue(source.contains(".height(24.dp)"))
        assertTrue(source.contains("RoundedCornerShape(4.dp)"))
        assertTrue(source.contains(".border(1.dp, MaterialTheme.colorScheme.outline"))
        assertTrue(source.contains("fontSize = 11.sp"))
    }

    @Test
    fun `shared controls expose all workspace primitives`() {
        assertTrue(source.contains("public fun ProfilerMacOsToolbar("))
        assertTrue(source.contains("public fun ProfilerMacOsSecondaryToolbar("))
        assertTrue(source.contains("public fun ProfilerCompactButton("))
        assertTrue(source.contains("public fun ProfilerCompactSelector("))
        assertTrue(source.contains("public fun ProfilerCompactTextField("))
        assertTrue(source.contains("public fun ProfilerToolbarStatus("))
    }

    @Test
    fun `selector gates an expanded menu and callbacks on current availability`() {
        val selector =
            source
                .substringAfter("public fun ProfilerCompactSelector(")
                .substringBefore("/** Compact single-line input")

        assertTrue(selector.contains("val available = enabled && options.isNotEmpty()"))
        assertTrue(selector.contains("LaunchedEffect(available)"))
        assertTrue(selector.contains("expanded = expanded && available"))
        assertTrue(selector.contains("enabled = available"))
        assertTrue(selector.contains("if (available) {\n                            onSelected(value)\n"))
    }
}
