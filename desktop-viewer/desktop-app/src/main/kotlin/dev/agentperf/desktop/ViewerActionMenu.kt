package dev.agentperf.desktop

import androidx.compose.ui.input.key.Key

internal enum class ViewerAction {
    TOGGLE_AUTO_SCAN,
    PREVIOUS_NODE,
    NEXT_NODE,
    TOGGLE_SELECTED_NODE,
    TOGGLE_HIERARCHY,
    TOGGLE_FINDINGS,
    TOGGLE_DETAILS,
    OPEN_SETTINGS,
}

internal data class ViewerActionItem(
    val action: ViewerAction,
    val label: String,
    val shortcutLabel: String,
    val group: Int,
)

internal object ViewerActionMenu {
    fun items(strings: ViewerStrings): List<ViewerActionItem> = listOf(
        ViewerActionItem(
            ViewerAction.TOGGLE_AUTO_SCAN,
            strings.actionLabel(ViewerAction.TOGGLE_AUTO_SCAN),
            "⌘R / Ctrl+R",
            group = 0,
        ),
        ViewerActionItem(
            ViewerAction.PREVIOUS_NODE,
            strings.actionLabel(ViewerAction.PREVIOUS_NODE),
            "↑",
            group = 1,
        ),
        ViewerActionItem(
            ViewerAction.NEXT_NODE,
            strings.actionLabel(ViewerAction.NEXT_NODE),
            "↓",
            group = 1,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_SELECTED_NODE,
            strings.actionLabel(ViewerAction.TOGGLE_SELECTED_NODE),
            "Enter",
            group = 1,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_HIERARCHY,
            strings.actionLabel(ViewerAction.TOGGLE_HIERARCHY),
            "⌘1 / Ctrl+1",
            group = 2,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_FINDINGS,
            strings.actionLabel(ViewerAction.TOGGLE_FINDINGS),
            "⌘2 / Ctrl+2",
            group = 2,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_DETAILS,
            strings.actionLabel(ViewerAction.TOGGLE_DETAILS),
            "⌘3 / Ctrl+3",
            group = 2,
        ),
        ViewerActionItem(
            ViewerAction.OPEN_SETTINGS,
            strings.actionLabel(ViewerAction.OPEN_SETTINGS),
            "⌘, / Ctrl+,",
            group = 3,
        ),
    )
}

internal fun viewerCommandAction(
    key: Key,
    commandPressed: Boolean,
): ViewerAction? {
    if (!commandPressed) return null
    return when (key) {
        Key.R -> ViewerAction.TOGGLE_AUTO_SCAN
        Key.One -> ViewerAction.TOGGLE_HIERARCHY
        Key.Two -> ViewerAction.TOGGLE_FINDINGS
        Key.Three -> ViewerAction.TOGGLE_DETAILS
        Key.Comma -> ViewerAction.OPEN_SETTINGS
        else -> null
    }
}
