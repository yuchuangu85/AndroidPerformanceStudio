package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuBarScope
import androidx.compose.ui.window.MenuComposable

public val LocalWindowMenuBarActive = staticCompositionLocalOf { true }

@Composable
@ComposableOpenTarget(-1)
@Suppress("FunctionName", "ktlint:standard:function-naming")
public fun FrameWindowScope.ActiveWindowMenuBar(
    content: @Composable @MenuComposable MenuBarScope.() -> Unit,
) {
    if (LocalWindowMenuBarActive.current) {
        MenuBar(content)
    }
}
