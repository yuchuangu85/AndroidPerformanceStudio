@file:Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

public const val PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP: Int = 32
public const val PROFILER_SECONDARY_TOOLBAR_HEIGHT_DP: Int = 28

private const val DISABLED_CONTENT_ALPHA: Float = 0.38f

/** Shared 32dp action toolbar for desktop profiler workspaces. */
@Composable
public fun ProfilerMacOsToolbar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Shared 28dp configuration toolbar for desktop profiler workspaces. */
@Composable
public fun ProfilerMacOsSecondaryToolbar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(PROFILER_SECONDARY_TOOLBAR_HEIGHT_DP.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Compact 24dp action used by profiler toolbars. */
@Composable
public fun ProfilerCompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(4.dp)
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val disabledAlpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA

    Box(
        modifier =
            modifier
                .height(24.dp)
                .clip(shape)
                .background(containerColor.copy(alpha = disabledAlpha))
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor.copy(alpha = disabledAlpha),
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact selector backed by a Material dropdown menu. */
@Composable
public fun ProfilerCompactSelector(
    label: String,
    selectedLabel: String?,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val available = enabled && options.isNotEmpty()
    LaunchedEffect(available) {
        if (!available) expanded = false
    }
    Box(modifier = modifier.widthIn(min = 110.dp, max = 280.dp)) {
        ProfilerCompactButton(
            text = selectedLabel ?: label,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = available,
        )
        DropdownMenu(
            expanded = expanded && available,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optionLabel,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (available) {
                            onSelected(value)
                        }
                    },
                    enabled = available,
                )
            }
        }
    }
}

/** Compact single-line input with an inline label. */
@Composable
public fun ProfilerCompactTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
) {
    val shape = RoundedCornerShape(4.dp)
    val contentAlpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA
    Row(
        modifier = modifier.widthIn(min = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            fontSize = 10.sp,
            maxLines = 1,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.surface, shape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    .padding(horizontal = 6.dp),
            enabled = enabled,
            singleLine = true,
            textStyle =
                TextStyle(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/** One-line toolbar status where errors take precedence over operation messages. */
@Composable
public fun ProfilerToolbarStatus(
    message: String?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val status = error ?: message ?: return
    Text(
        text = status,
        modifier = modifier,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
