@file:Suppress("FunctionNaming")

package com.androidperformancestudio.ui.studio

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.viewerOutlinedTextFieldColors

@Composable
public fun StudioSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
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
