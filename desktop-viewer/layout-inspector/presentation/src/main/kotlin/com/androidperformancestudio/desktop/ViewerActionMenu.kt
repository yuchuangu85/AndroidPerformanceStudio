package com.androidperformancestudio.desktop

import androidx.compose.ui.input.key.Key
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.auto_scan
import com.androidperformancestudio.presentation.generated.resources.next_node
import com.androidperformancestudio.presentation.generated.resources.previous_node
import com.androidperformancestudio.presentation.generated.resources.settings
import com.androidperformancestudio.presentation.generated.resources.toggle_details
import com.androidperformancestudio.presentation.generated.resources.toggle_findings
import com.androidperformancestudio.presentation.generated.resources.toggle_hierarchy
import com.androidperformancestudio.presentation.generated.resources.toggle_hierarchy_ids
import com.androidperformancestudio.presentation.generated.resources.toggle_selected_node
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal enum class ViewerAction {
    TOGGLE_AUTO_SCAN,
    PREVIOUS_NODE,
    NEXT_NODE,
    TOGGLE_SELECTED_NODE,
    TOGGLE_HIERARCHY,
    TOGGLE_FINDINGS,
    TOGGLE_DETAILS,
    TOGGLE_HIERARCHY_IDS,
    OPEN_SETTINGS,
}

internal data class ViewerActionItem(
    val action: ViewerAction,
    val label: String,
    val shortcutLabel: String,
    val group: Int,
)

internal object ViewerActionMenu {
    fun items(language: UiLanguage): List<ViewerActionItem> = listOf(
        ViewerActionItem(
            ViewerAction.TOGGLE_AUTO_SCAN,
            localizedStringResource(Res.string.auto_scan, language),
            "⌘R / Ctrl+R",
            group = 0,
        ),
        ViewerActionItem(
            ViewerAction.PREVIOUS_NODE,
            localizedStringResource(Res.string.previous_node, language),
            "↑",
            group = 1,
        ),
        ViewerActionItem(
            ViewerAction.NEXT_NODE,
            localizedStringResource(Res.string.next_node, language),
            "↓",
            group = 1,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_SELECTED_NODE,
            localizedStringResource(Res.string.toggle_selected_node, language),
            "Enter",
            group = 1,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_HIERARCHY,
            localizedStringResource(Res.string.toggle_hierarchy, language),
            "⌘1 / Ctrl+1",
            group = 2,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_FINDINGS,
            localizedStringResource(Res.string.toggle_findings, language),
            "⌘2 / Ctrl+2",
            group = 2,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_DETAILS,
            localizedStringResource(Res.string.toggle_details, language),
            "⌘3 / Ctrl+3",
            group = 2,
        ),
        ViewerActionItem(
            ViewerAction.TOGGLE_HIERARCHY_IDS,
            localizedStringResource(Res.string.toggle_hierarchy_ids, language),
            "",
            group = 3,
        ),
        ViewerActionItem(
            ViewerAction.OPEN_SETTINGS,
            localizedStringResource(Res.string.settings, language),
            "⌘, / Ctrl+,",
            group = 4,
        ),
    )

    fun commandAction(
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
}
