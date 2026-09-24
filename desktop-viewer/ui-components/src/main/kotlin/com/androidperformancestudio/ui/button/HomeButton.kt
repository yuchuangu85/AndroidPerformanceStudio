@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.ui.button

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.ic_home
import org.jetbrains.compose.resources.painterResource

/** Compact macOS-style home navigation control shared by desktop profiler toolbars. */
@Composable
fun HomeButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ViewerColors? = null,
) {
    val shape = RoundedCornerShape(ViewerDimensions.controlRadius)
    val background = colors?.panel ?: MaterialTheme.colorScheme.surface
    val accent = colors?.accent ?: LocalViewerColors.current.accent
    Box(
        modifier =
            modifier
                .width(28.dp)
                .height(ViewerDimensions.buttonHeight)
                .clip(shape)
                .background(background)
                .border(ViewerDimensions.hairline, accent, shape)
                .semantics { this.contentDescription = contentDescription }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_home),
            contentDescription = contentDescription,
            tint = accent,
        )
    }
}
