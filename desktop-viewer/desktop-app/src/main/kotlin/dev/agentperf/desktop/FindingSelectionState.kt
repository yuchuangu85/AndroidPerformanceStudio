package dev.agentperf.desktop

internal data class FindingSelectionState(
    private val selectedKey: String? = null,
) {
    fun select(key: String): FindingSelectionState = copy(selectedKey = key)

    fun isSelected(key: String): Boolean = key == selectedKey
}
