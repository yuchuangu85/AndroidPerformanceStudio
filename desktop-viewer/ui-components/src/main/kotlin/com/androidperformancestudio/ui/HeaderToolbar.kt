package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.button.SettingsButton
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.back_to_home
import com.androidperformancestudio.ui_components.generated.resources.settings

@Composable
fun HeaderToolbar(
    modifier: Modifier = Modifier,
    language: UiLanguage,
    onNavigateHome: (() -> Unit)?,
    onNavigateSettings: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.height(HEADER_TOOL_BAR_HEIGHT).fillMaxWidth()
            .background(LocalViewerColors.current.panel)
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onNavigateHome != null) {
            HomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, language),
                onClick = onNavigateHome,
            )
            HeaderSpacer()
            HeaderDivider()
            HeaderSpacer()
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
        if (onNavigateSettings != null) {
            HeaderSpacer()
            HeaderDivider()
            HeaderSpacer()
            Box(Modifier.fillMaxHeight().wrapContentWidth()) {
                SettingsButton(
                    modifier = Modifier.align(Alignment.Center),
                    contentDescription = localizedStringResource(Res.string.settings, language),
                ) { onNavigateSettings() }
            }
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
