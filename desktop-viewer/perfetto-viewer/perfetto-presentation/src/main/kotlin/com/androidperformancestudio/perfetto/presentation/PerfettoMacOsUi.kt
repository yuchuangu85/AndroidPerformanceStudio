package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
@Suppress("ktlint:standard:function-naming")
fun PerfettoWorkspacePanel(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    ).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        content()
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
fun PerfettoCompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    height: Dp = 24.dp,
) {
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .height(height)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(horizontal = 7.dp, vertical = if (singleLine) 4.dp else 6.dp),
        enabled = enabled,
        singleLine = singleLine,
        textStyle =
            MaterialTheme.typography.bodySmall.copy(
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                fontSize = 11.sp,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box(contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
fun PerfettoCompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(4.dp)
    val background =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .height(24.dp)
                .clip(shape)
                .background(background)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color =
                when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
fun PerfettoStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
    )
}
