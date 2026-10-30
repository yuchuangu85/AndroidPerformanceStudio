@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName", "ktlint:standard:function-naming")
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.count_value
import com.androidperformancestudio.memory.presentation.generated.resources.memory_page_next
import com.androidperformancestudio.memory.presentation.generated.resources.memory_page_previous
import com.androidperformancestudio.memory.presentation.generated.resources.memory_page_range
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_collapse
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_expand
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiRes

/** Page bounds shared by the instance, root, and child paging controls in this file. */
internal data class MemoryHeapPage(
    val offset: Int,
    val totalCount: Int,
    val loadedCount: Int,
    val pageSize: Int,
) {
    val start: Int get() = if (loadedCount > 0 && offset in 0 until totalCount) offset + 1 else 0
    val end: Int get() = if (start == 0) 0 else minOf(totalCount.toLong(), offset.toLong() + loadedCount).toInt()
    val isPaged: Boolean get() = offset > 0 || totalCount > loadedCount
    val previousOffset: Int? get() = if (offset > 0) (offset - pageSize.coerceAtLeast(1)).coerceAtLeast(0) else null
    val nextOffset: Int?
        get() =
            if (loadedCount > 0 && pageSize > 0 && end in 1 until totalCount) {
                end
            } else {
                null
            }
}

@Composable
@Suppress("LongParameterList")
internal fun MemoryHeapPageControls(
    page: MemoryHeapPage,
    language: UiLanguage,
    tag: String,
    onLoadPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val range =
        if (page.isPaged) {
            localizedStringResource(Res.string.memory_page_range, language, page.start, page.end, page.totalCount)
        } else {
            localizedStringResource(Res.string.count_value, language, page.totalCount)
        }
    FlowRow(
        modifier.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TooltipArea(tooltip = {
            Surface(shadowElevation = 4.dp) { Text(range, Modifier.padding(8.dp), style = ViewerTypography.secondary) }
        }) {
            Text(
                text = range,
                modifier = Modifier.height(28.dp).wrapContentHeight(Alignment.CenterVertically).testTag("$tag-range"),
                style = ViewerTypography.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (page.isPaged) {
            Row {
                MemoryHeapIconButton(
                    icon = UiRes.drawable.ic_nav_rail_collapse,
                    label = localizedStringResource(Res.string.memory_page_previous, language),
                    tag = "$tag-previous",
                    enabled = enabled && page.previousOffset != null,
                    onClick = { page.previousOffset?.let(onLoadPage) },
                )
                MemoryHeapIconButton(
                    icon = UiRes.drawable.ic_nav_rail_expand,
                    label = localizedStringResource(Res.string.memory_page_next, language),
                    tag = "$tag-next",
                    enabled = enabled && page.nextOffset != null,
                    onClick = { page.nextOffset?.let(onLoadPage) },
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun MemoryHeapIconButton(
    icon: DrawableResource,
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    rotation: Float = 0f,
) {
    TooltipArea(tooltip = {
        Surface(shadowElevation = 4.dp) {
            Text(label, Modifier.padding(8.dp), style = ViewerTypography.secondary)
        }
    }) {
        Box(
            Modifier.size(28.dp).testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(16.dp).rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f),
            )
        }
    }
}
