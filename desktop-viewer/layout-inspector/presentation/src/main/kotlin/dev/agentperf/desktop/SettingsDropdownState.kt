package dev.agentperf.desktop

internal data class SettingsDropdownState(
    val expanded: Boolean = false,
) {
    fun toggle(): SettingsDropdownState = copy(expanded = !expanded)

    fun dismiss(): SettingsDropdownState = copy(expanded = false)
}
