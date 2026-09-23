package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.icon_expand
import com.androidperformancestudio.ui_components.generated.resources.no_matching_options
import com.androidperformancestudio.ui_components.generated.resources.search_options
import org.jetbrains.compose.resources.painterResource
import kotlin.plus

/**
 * Generic compact dropdown shared by profiler and inspector toolbars.
 * Set [searchable] for app/process lists; [itemSearchText] may include hidden identifiers such as a package name.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
public fun <T> DropdownSelector(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    placeholder: String,
    selectedItemLabel: (T) -> String = itemLabel,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectorDescription: String? = null,
    colors: ViewerColors = LocalViewerColors.current,
    onPlaceholderSelected: (() -> Unit)? = null,
    itemSecondary: @Composable (T) -> String? = { null },
    itemEnabled: (T) -> Boolean = { true },
    fillWidth: Boolean = false,
    menuModifier: Modifier = Modifier,
    menuItemHeight: Dp = 32.dp,
    controlFontSize: TextUnit? = null,
    menuFontSize: TextUnit? = null,
    onControlClick: (() -> Unit)? = null,
    searchable: Boolean = false,
    searchLanguage: UiLanguage = UiLanguage.ENGLISH,
    itemSearchText: (T) -> String = itemLabel,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredItems = filterDropdownItems(items, searchQuery.takeIf { searchable }.orEmpty(), itemSearchText)
    val closeMenu = {
        expanded = false
        searchQuery = ""
    }
    val displayText = selectedItem?.let(selectedItemLabel) ?: placeholder
    val controlTextSize = controlFontSize ?: ViewerTypography.secondary.fontSize
    val menuTextSize = menuFontSize ?: ViewerTypography.bodyCompact.fontSize
    val canExpand = enabled && (items.isNotEmpty() || onPlaceholderSelected != null || onControlClick != null)
    val shape = RoundedCornerShape(4.dp)
    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .background(colors.sectionBackground, shape)
                    .border(1.dp, colors.accent, shape)
                    .semantics {
                        selectorDescription?.let { contentDescription = it }
                        stateDescription = displayText
                    }.clickable(enabled = canExpand) {
                        if (onControlClick == null) {
                            searchQuery = ""
                            expanded = true
                        } else {
                            onControlClick()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = displayText,
                color = colors.accent,
                fontSize = controlTextSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            )
            Icon(
                painter = painterResource(Res.drawable.icon_expand),
                contentDescription = null,
                modifier =
                    Modifier
                        .size(12.dp)
                        .testTag("dropdown-selector-expand-icon"),
                tint = colors.accent,
            )
        }
        DropdownMenu(
            expanded = expanded && canExpand && onControlClick == null,
            onDismissRequest = closeMenu,
            modifier =
                menuModifier
                    .then(if (searchable) Modifier.heightIn(max = 360.dp) else Modifier)
                    .background(colors.panel),
        ) {
            if (searchable) {
                DropdownSelectorSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = localizedStringResource(Res.string.search_options, searchLanguage),
                    colors = colors,
                )
            }
            if (searchQuery.isBlank()) {
                onPlaceholderSelected?.let { selectPlaceholder ->
                    DropdownSelectorMenuItem(
                        label = placeholder,
                        secondary = null,
                        selected = selectedItem == null,
                        enabled = enabled,
                        onClick = {
                            closeMenu()
                            selectPlaceholder()
                        },
                        colors = colors,
                        itemHeight = menuItemHeight,
                        fontSize = menuTextSize,
                    )
                }
            }
            if (searchable && filteredItems.isEmpty()) {
                Text(
                    text = localizedStringResource(Res.string.no_matching_options, searchLanguage),
                    color = colors.secondaryText,
                    fontSize = menuTextSize,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            filteredItems.forEach { item ->
                DropdownSelectorMenuItem(
                    label = itemLabel(item),
                    secondary = itemSecondary(item),
                    selected = item == selectedItem,
                    enabled = enabled && itemEnabled(item),
                    onClick = {
                        closeMenu()
                        onItemSelected(item)
                    },
                    colors = colors,
                    itemHeight = menuItemHeight,
                    fontSize = menuTextSize,
                )
            }
        }
    }
}

/** Case-insensitive substring matching for optional dropdown search; preserves item order. */
internal fun <T> filterDropdownItems(
    items: List<T>,
    query: String,
    searchText: (T) -> String,
): List<T> {
    val term = query.trim()
    return if (term.isEmpty()) items else items.filter { searchText(it).contains(term, ignoreCase = true) }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DropdownSelectorSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: ViewerColors,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .border(1.dp, colors.accent, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .focusRequester(focusRequester)
                .testTag("dropdown-selector-search")
                .semantics { contentDescription = placeholder },
        singleLine = true,
        textStyle = ViewerTypography.bodyCompact.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, color = colors.secondaryText, style = ViewerTypography.bodyCompact)
                }
                innerTextField()
            }
        },
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun DropdownSelectorMenuItem(
    label: String,
    secondary: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    colors: ViewerColors,
    itemHeight: Dp,
    fontSize: TextUnit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = label,
                    color = colors.text,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                secondary?.let {
                    Text(
                        text = it,
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.dense.fontSize,
                        lineHeight = ViewerTypography.dense.lineHeight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .height(if (secondary == null) itemHeight else 42.dp)
                .semantics { this.selected = selected },
    )
}
