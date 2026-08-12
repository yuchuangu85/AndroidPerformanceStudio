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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.icon_expand
import org.jetbrains.compose.resources.painterResource
import kotlin.plus

/** Generic compact dropdown shared by profiler and inspector toolbars. */
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
    controlFontSize: TextUnit = 11.sp,
    menuFontSize: TextUnit = 12.sp,
    onControlClick: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = selectedItem?.let(selectedItemLabel) ?: placeholder
    val canExpand = enabled && (items.isNotEmpty() || onPlaceholderSelected != null || onControlClick != null)
    val shape = RoundedCornerShape(4.dp)
    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .background(colors.sectionBackground, shape)
                    .border(1.dp, colors.border, shape)
                    .semantics {
                        selectorDescription?.let { contentDescription = it }
                        stateDescription = displayText
                    }.clickable(enabled = canExpand) {
                        if (onControlClick == null) expanded = true else onControlClick()
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = displayText,
                color = colors.secondaryText,
                fontSize = controlFontSize,
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
                tint = colors.secondaryText,
            )
        }
        DropdownMenu(
            expanded = expanded && canExpand && onControlClick == null,
            onDismissRequest = { expanded = false },
            modifier = menuModifier.background(colors.panel),
        ) {
            onPlaceholderSelected?.let { selectPlaceholder ->
                DropdownSelectorMenuItem(
                    label = placeholder,
                    secondary = null,
                    selected = selectedItem == null,
                    enabled = enabled,
                    onClick = {
                        expanded = false
                        selectPlaceholder()
                    },
                    colors = colors,
                    itemHeight = menuItemHeight,
                    fontSize = menuFontSize,
                )
            }
            items.forEach { item ->
                DropdownSelectorMenuItem(
                    label = itemLabel(item),
                    secondary = itemSecondary(item),
                    selected = item == selectedItem,
                    enabled = enabled && itemEnabled(item),
                    onClick = {
                        expanded = false
                        onItemSelected(item)
                    },
                    colors = colors,
                    itemHeight = menuItemHeight,
                    fontSize = menuFontSize,
                )
            }
        }
    }
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
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
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
