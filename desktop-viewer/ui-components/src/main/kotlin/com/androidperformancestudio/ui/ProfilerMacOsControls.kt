@file:Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val DISABLED_CONTENT_ALPHA: Float = 0.38f

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

/** The 20dp inline input used by CPU Profiler filter toolbars. */
@Composable
public fun MacOSInlineTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ViewerColors = LocalViewerColors.current,
    fieldWidth: Dp? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = style.secondaryText, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = style.text, fontSize = 11.sp, lineHeight = 14.sp),
            cursorBrush = SolidColor(style.accent),
            modifier =
                (if (fieldWidth == null) Modifier.weight(1f) else Modifier.requiredWidth(fieldWidth))
                    .height(20.dp)
                    .background(style.field, RoundedCornerShape(ViewerDimensions.controlRadius))
                    .border(
                        ViewerDimensions.hairline,
                        style.strongBorder,
                        RoundedCornerShape(ViewerDimensions.controlRadius),
                    ).semantics { contentDescription = label }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
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
