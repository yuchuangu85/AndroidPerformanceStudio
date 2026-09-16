package com.androidperformancestudio.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTypography

/**
 * A compact, single-line search input for dense tree and source panels.
 *
 * The default dimensions and colors intentionally align with the Layout Inspector hierarchy tree.
 */
@Suppress("FunctionNaming")
@Composable
public fun CompactSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Box(
        modifier =
            modifier
                .height(22.dp)
                .border(1.dp, colors.accent, RoundedCornerShape(3.dp))
                .background(colors.sectionBackground.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = if (value.isNotEmpty()) 14.dp else 0.dp)
                    .semantics { contentDescription = placeholder },
            textStyle =
                TextStyle(
                    color = colors.primaryText,
                    fontSize = ViewerTypography.label.fontSize,
                    fontFamily = FontFamily.Monospace,
                ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = colors.mutedText,
                fontSize = ViewerTypography.label.fontSize,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        } else {
            Text(
                text = "✕",
                color = colors.mutedText,
                fontSize = ViewerTypography.label.fontSize,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterEnd).clickable { onValueChange("") },
            )
        }
    }
}

/** Compact previous/next search control styled to pair with [CompactSearchField]. */
@Suppress("FunctionNaming")
@Composable
public fun CompactSearchNavigationButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Box(
        modifier =
            Modifier
                .width(20.dp)
                .height(20.dp)
                .background(
                    color = if (enabled) colors.accent.copy(alpha = 0.10f) else colors.transparent,
                    shape = RoundedCornerShape(3.dp),
                )
                .let { base -> if (enabled) base.clickable(onClick = onClick) else base }
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) colors.accent else colors.mutedText.copy(alpha = 0.4f),
            fontSize = ViewerTypography.dense.fontSize,
            maxLines = 1,
        )
    }
}
