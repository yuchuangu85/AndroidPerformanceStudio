package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.device_selector_automatic
import com.androidperformancestudio.ui_components.generated.resources.device_selector_description
import com.androidperformancestudio.ui_components.generated.resources.device_selector_placeholder

/** Consistent device chrome; callers retain device identity, availability, and selection policy. */
@Composable
@Suppress("FunctionName", "LongParameterList")
public fun <T> DeviceDropdownSelector(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    selectedItemLabel: (T) -> String = itemLabel,
    enabled: Boolean = true,
    itemEnabled: (T) -> Boolean = { true },
    itemSecondary: @Composable (T) -> String? = { null },
    fillWidth: Boolean = false,
    colors: ViewerColors = LocalViewerColors.current,
    onAutomaticSelection: (() -> Unit)? = null,
    menuModifier: Modifier = Modifier,
) {
    DropdownSelector(
        items = items,
        selectedItem = selectedItem,
        onItemSelected = onItemSelected,
        itemLabel = itemLabel,
        selectedItemLabel = selectedItemLabel,
        placeholder = localizedStringResource(Res.string.device_selector_placeholder, language),
        selectorDescription = localizedStringResource(Res.string.device_selector_description, language),
        modifier = modifier,
        enabled = enabled,
        itemEnabled = itemEnabled,
        itemSecondary = itemSecondary,
        fillWidth = fillWidth,
        colors = colors,
        onPlaceholderSelected = onAutomaticSelection,
        menuModifier = menuModifier,
        placeholderMenuLabel = localizedStringResource(Res.string.device_selector_automatic, language),
    )
}
