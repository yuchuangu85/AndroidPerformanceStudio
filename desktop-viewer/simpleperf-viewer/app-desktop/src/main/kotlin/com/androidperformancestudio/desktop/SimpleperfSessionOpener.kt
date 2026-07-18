package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.SimpleperfEngine
import java.nio.file.Path

internal class SimpleperfSessionOpener(
    private val selectedEngine: () -> SimpleperfEngine,
    private val openLocal: suspend (Path) -> Unit,
    private val openLocalFirefoxProfiler: suspend (Path) -> Unit,
    private val openOfficialFirefoxProfiler: suspend (Path) -> Unit,
) {
    suspend fun open(sessionDirectory: Path) {
        when (selectedEngine()) {
            SimpleperfEngine.LOCAL -> openLocal(sessionDirectory)
            SimpleperfEngine.FIREFOX_PROFILER_LOCAL -> openLocalFirefoxProfiler(sessionDirectory)
            SimpleperfEngine.FIREFOX_PROFILER -> openOfficialFirefoxProfiler(sessionDirectory)
        }
    }
}
