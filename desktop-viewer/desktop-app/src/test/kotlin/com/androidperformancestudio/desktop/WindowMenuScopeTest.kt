package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowMenuScopeTest {
    @Test
    fun `cross-profiler file shortcuts are absent from every non-Home page`() {
        AppDestination.entries.filter { it != AppDestination.HOME }.forEach { destination ->
            assertTrue(
                homeArtifactMenuCommands(destination, UiLanguage.ENGLISH, {}, {}).isEmpty(),
                "$destination must use only its own file actions",
            )
        }
    }

    @Test
    fun `Home shortcuts are localized and invoke the matching profiler action`() {
        val actions = mutableListOf<String>()
        val commands =
            homeArtifactMenuCommands(
                AppDestination.HOME,
                UiLanguage.SIMPLIFIED_CHINESE,
                onOpenTraceFile = { actions += "trace" },
                onAnalyzeHprof = { actions += "hprof" },
            )

        assertEquals(listOf("打开 Trace 文件", "分析 HPROF"), commands.map { it.label })
        assertTrue(commands.all { it.enabled })
        commands[0].onClick()
        assertEquals(listOf("trace"), actions)
        commands[1].onClick()
        assertEquals(listOf("trace", "hprof"), actions)
    }
}
