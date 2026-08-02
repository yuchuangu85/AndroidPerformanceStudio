package com.androidperformancestudio.ui.radiobutton

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.DISABLED_CHIP_ALPHA
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions


@Composable
fun MacOSChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    style: ViewerColors,
    onClick: () -> Unit,
) {
    val background = if (selected) style.accent else style.field
    val content = if (selected) style.accentText else style.text
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 0.dp, end = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.height(14.dp).width(14.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            Modifier
                .height(
                    20.dp,
                ).background(
                    background,
                    RoundedCornerShape(6.dp),
                ).border(
                    ViewerDimensions.hairline,
                    if (selected) style.accent else style.strongBorder,
                    RoundedCornerShape(6.dp),
                ).clickable(enabled = enabled, onClick = onClick)
                .padding(start = 6.dp, top = 0.dp, end = 6.dp, bottom = 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = content.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}
