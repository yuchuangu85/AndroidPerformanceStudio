package com.androidperformancestudio.ui.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic measurements for the dense Studio desktop shell. Colors and typography remain owned by
 * [com.androidperformancestudio.ui.ViewerTheme].
 */
public object StudioTokens {
    public val topBarHeight: Dp = 56.dp
    public val navigationExpandedWidth: Dp = 216.dp
    public val navigationCollapsedWidth: Dp = 64.dp
    public val globalStatusBarHeight: Dp = 28.dp
    public val panelHeaderHeight: Dp = 36.dp
    public val navigationItemHeight: Dp = 36.dp
    public val contentPadding: Dp = 16.dp
    public val compactContentPadding: Dp = 8.dp
    public val panelGap: Dp = 8.dp
    public val sectionGap: Dp = 12.dp
    public val smallRadius: Dp = 4.dp
    public val panelRadius: Dp = 6.dp
    public val cardRadius: Dp = 8.dp
    public val heroCardRadius: Dp = 10.dp
    public val borderWidth: Dp = 1.dp
}

public enum class StudioStatusTone {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

/** Evidence labels deliberately remain textual so the state is never encoded by color alone. */
public enum class StudioEvidence {
    COMPLETE,
    PARTIAL,
    UNKNOWN,
    EXACT,
    DERIVED,
    INFERRED,
    ESTIMATED,
    UNAVAILABLE,
}

@Immutable
public data class StudioTableColumn(
    val label: String,
    val weight: Float = 1f,
)
