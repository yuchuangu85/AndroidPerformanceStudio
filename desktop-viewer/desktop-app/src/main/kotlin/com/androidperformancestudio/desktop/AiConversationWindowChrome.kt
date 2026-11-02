package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.ui.ViewerColors

@Composable
internal fun FrameWindowScope.AiConversationWindowChrome(
    title: String,
    colors: ViewerColors,
    darkTheme: Boolean,
) {
    SideEffect { MacOsWindowChrome.install(window) }
    WindowsWindowChromeEffect(colors, darkTheme)
    MacOsTitleBar(
        title = title,
        background = colors.toolbar,
        contentColor = colors.primaryText,
        dividerColor = colors.border,
    )
}
