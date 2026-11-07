package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
@Suppress("UNUSED_PARAMETER")
fun HeaderToolbar(
    modifier: Modifier = Modifier,
    language: UiLanguage,
    onNavigateHome: (() -> Unit)?,
    onNavigateSettings: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier = modifier.height(HEADER_TOOL_BAR_HEIGHT).fillMaxWidth()
            .background(colors.panel)
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
fun HeaderDivider() {
    Box(Modifier.width(1.dp).height(14.dp).background(LocalViewerColors.current.border))
}

@Composable
fun HeaderSpacer() {
    Spacer(Modifier.width(8.dp))
}
