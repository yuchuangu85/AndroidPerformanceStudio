package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/** Visual variants for [SegmentedSelector]. */
public enum class SegmentedSelectorStyle {
    /** Reference-style translucent rounded pill. */
    PILL,

    /** Same 24dp height, 4dp rounded rectangle, and accent outline as toolbar action buttons. */
    COMPACT_OUTLINED,
}

/**
 * Single-choice selector for two, three, or more items.
 *
 * Selection is controlled by the caller: update [selectedItem] in [onItemSelected].
 * [onSelectedItemClick] is optional for controls where a selected item also triggers an action.
 * When there are many choices, the row scrolls horizontally.
 */
@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
public fun <T> SegmentedSelector(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemEnabled: (T) -> Boolean = { true },
    selectorDescription: String? = null,
    maxWidth: Dp = ViewerDimensions.segmentedMaxWidth,
    style: SegmentedSelectorStyle = SegmentedSelectorStyle.PILL,
    onSelectedItemClick: ((T) -> Unit)? = null,
    colors: ViewerColors = LocalViewerColors.current,
) {
    if (items.isEmpty()) return

    val compact = style == SegmentedSelectorStyle.COMPACT_OUTLINED
    val controlHeight = if (compact) ViewerDimensions.compactControlHeight else ViewerDimensions.segmentedControlHeight
    val itemHeight = if (compact) ViewerDimensions.compactControlHeight else ViewerDimensions.segmentedItemHeight
    val radius = if (compact) ViewerDimensions.compactControlRadius else ViewerDimensions.segmentedRadius
    val trackShape = RoundedCornerShape(radius)
    val trackColor = if (compact) colors.panel else colors.segmentedTrack
    Row(
        modifier =
            modifier
                .widthIn(max = maxWidth)
                .height(controlHeight)
                .clip(trackShape)
                .background(trackColor)
                .then(if (compact) Modifier.border(ViewerDimensions.hairline, colors.accent, trackShape) else Modifier)
                .then(
                    if (selectorDescription == null) Modifier else Modifier.semantics {
                        contentDescription = selectorDescription
                    },
                )
                .horizontalScroll(rememberScrollState())
                .padding(if (compact) 0.dp else 2.dp)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val selected = item == selectedItem
            val canSelect = enabled && itemEnabled(item)
            Box(
                modifier =
                    Modifier
                        .height(itemHeight)
                        .widthIn(min = ViewerDimensions.segmentedMinItemWidth)
                        .clip(segmentedItemShape(compact, index, items.size, itemHeight))
                        .background(
                            if (selected) {
                                if (compact) colors.accent else colors.segmentedSelected
                            } else {
                                colors.transparent
                            },
                        )
                        .selectable(
                            selected = selected,
                            enabled = canSelect,
                            role = Role.RadioButton,
                            onClick = {
                                if (selected) onSelectedItemClick?.invoke(item) else onItemSelected(item)
                            },
                        )
                        .testTag("segmented-selector-item-$index")
                        .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = itemLabel(item),
                    style = ViewerTypography.secondary,
                    color =
                        (if (compact && selected) colors.accentText else if (compact) colors.accent else colors.primaryText)
                            .copy(alpha = if (canSelect) 1f else DISABLED_CHIP_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (compact && index < items.lastIndex) {
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight().width(ViewerDimensions.hairline),
                    color = colors.accent,
                )
            }
        }
    }
}

private fun segmentedItemShape(
    compact: Boolean,
    index: Int,
    itemCount: Int,
    itemHeight: Dp,
): RoundedCornerShape {
    if (!compact) return RoundedCornerShape(itemHeight / 2)
    val radius = ViewerDimensions.compactControlRadius
    return when {
        itemCount == 1 -> RoundedCornerShape(radius)
        index == 0 -> RoundedCornerShape(topStart = radius, bottomStart = radius)
        index == itemCount - 1 -> RoundedCornerShape(topEnd = radius, bottomEnd = radius)
        else -> RoundedCornerShape(0.dp)
    }
}
