@file:Suppress("FunctionNaming")

package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.viewerOutlinedTextFieldColors

/**
 * Shared search input.
 *
 * [compact] reuses the 24dp toolbar input treatment so a workspace search field can match the
 * compact text fields above it instead of the default Material outlined field height.
 */
@Composable
public fun StudioSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (compact) {
        CompactStudioSearchField(value, onValueChange, placeholder, modifier)
        return
    }
    val colors = LocalViewerColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = ViewerTypography.body.copy(color = colors.primaryText),
        placeholder = {
            Text(
                text = placeholder,
                color = colors.secondaryText,
                style = ViewerTypography.body,
            )
        },
        colors = viewerOutlinedTextFieldColors(),
    )
}

@Composable
private fun CompactStudioSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .height(COMPACT_SEARCH_FIELD_HEIGHT)
                .background(MaterialTheme.colorScheme.surface, shape)
                .border(1.dp, MaterialTheme.colorScheme.primary, shape)
                .padding(horizontal = 6.dp),
        singleLine = true,
        textStyle =
            TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = ViewerTypography.secondary.fontSize,
                lineHeight = ViewerTypography.secondary.lineHeight,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = ViewerTypography.secondary.fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

private val COMPACT_SEARCH_FIELD_HEIGHT = 24.dp
