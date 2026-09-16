package com.androidperformancestudio.desktop

/** Mutable widths for the first two panes; the source pane consumes the protected remainder. */
internal data class SourceWorkspacePaneWidths(
    val workspaces: Float = 360f,
    val files: Float = 320f,
)

internal object SourceWorkspacePaneLayout {
    const val WORKSPACES_MIN_WIDTH_DP = 220f
    const val FILES_MIN_WIDTH_DP = 220f
    const val SOURCE_MIN_WIDTH_DP = 360f
    const val SPLITTER_WIDTH_DP = 7f
    private const val SPLITTER_COUNT = 2

    fun fit(
        widths: SourceWorkspacePaneWidths,
        availableWidthDp: Float,
    ): SourceWorkspacePaneWidths {
        val sidePaneBudget = availableWidthDp - SOURCE_MIN_WIDTH_DP - SPLITTER_WIDTH_DP * SPLITTER_COUNT
        val workspacesMaximum = maxOf(WORKSPACES_MIN_WIDTH_DP, sidePaneBudget - FILES_MIN_WIDTH_DP)
        val workspaces = widths.workspaces.coerceIn(WORKSPACES_MIN_WIDTH_DP, workspacesMaximum)
        val filesMaximum = maxOf(FILES_MIN_WIDTH_DP, sidePaneBudget - workspaces)
        val files = widths.files.coerceIn(FILES_MIN_WIDTH_DP, filesMaximum)
        return SourceWorkspacePaneWidths(workspaces = workspaces, files = files)
    }

    fun dragWorkspaces(
        widths: SourceWorkspacePaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
    ): SourceWorkspacePaneWidths {
        val maximumWidth = maxOf(
            WORKSPACES_MIN_WIDTH_DP,
            availableWidthDp - widths.files - SOURCE_MIN_WIDTH_DP - SPLITTER_WIDTH_DP * SPLITTER_COUNT,
        )
        return widths.copy(
            workspaces = (widths.workspaces + deltaDp).coerceIn(WORKSPACES_MIN_WIDTH_DP, maximumWidth),
        )
    }

    fun dragFiles(
        widths: SourceWorkspacePaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
    ): SourceWorkspacePaneWidths {
        val maximumWidth = maxOf(
            FILES_MIN_WIDTH_DP,
            availableWidthDp - widths.workspaces - SOURCE_MIN_WIDTH_DP - SPLITTER_WIDTH_DP * SPLITTER_COUNT,
        )
        return widths.copy(
            files = (widths.files + deltaDp).coerceIn(FILES_MIN_WIDTH_DP, maximumWidth),
        )
    }
}
