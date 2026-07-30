package com.androidperformancestudio.desktop

internal object DetailSectionHeaderLayout {
    const val HEIGHT_DP = PanelHeaderLayout.HEIGHT_DP
}

internal data class DetailSectionExpansionState(
    private val collapsedTitles: Set<String> = emptySet(),
) {
    fun isExpanded(title: String): Boolean = title !in collapsedTitles

    fun toggle(title: String): DetailSectionExpansionState =
        copy(
            collapsedTitles = if (title in collapsedTitles) {
                collapsedTitles - title
            } else {
                collapsedTitles + title
            },
        )
}
