package com.androidperformancestudio.presentation

import androidx.compose.runtime.Composable
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.viewerColors

/**
 * Resolves the CPU Profile palette from the enclosing desktop theme while preserving the requested
 * light or dark surface palette for standalone use.
 */
@Composable
internal fun currentSimpleperfViewerColors(darkTheme: Boolean): ViewerColors {
    val activeColors = LocalViewerColors.current
    return if (activeColors.isDark == darkTheme) {
        activeColors
    } else {
        viewerColors(darkTheme = darkTheme, accentColor = activeColors.accent)
    }
}
