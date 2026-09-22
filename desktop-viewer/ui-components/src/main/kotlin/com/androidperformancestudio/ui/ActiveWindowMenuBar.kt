package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBarScope
import androidx.compose.ui.window.MenuComposable
import androidx.compose.ui.window.setContent
import javax.swing.JMenu
import javax.swing.JMenuBar

private const val PRELOADED_MENU_MARKER = "android-performance-studio.preloaded-menu"

public val LocalWindowMenuBarActive = staticCompositionLocalOf { true }

/**
 * Installs a native menu bar before the active feature page is composed.
 *
 * The feature menu is filled later by [ActiveWindowMenuBar], but the placeholder keeps the
 * platform menu bar visible while a profiler initializes its controller and content.
 */
@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
public fun FrameWindowScope.WindowMenuBarHost(
    preloadedMenuTitles: List<String> = emptyList(),
) {
    val menuBar = remember { JMenuBar() }
    if (preloadedMenuTitles.isEmpty()) {
        if (window.jMenuBar === menuBar) {
            window.jMenuBar = null
            refreshNativeMenuBar()
        }
    } else if (window.jMenuBar !== menuBar) {
        menuBar.updatePreloadedMenus(preloadedMenuTitles)
        window.jMenuBar = menuBar
        refreshNativeMenuBar()
    } else {
        menuBar.updatePreloadedMenus(preloadedMenuTitles)
    }
    DisposableEffect(menuBar) {
        onDispose {
            if (window.jMenuBar === menuBar) {
                window.jMenuBar = null
                refreshNativeMenuBar()
            }
        }
    }
    SideEffect(::refreshNativeMenuBar)
}

@Composable
@ComposableOpenTarget(-1)
@Suppress("FunctionName", "ktlint:standard:function-naming")
public fun FrameWindowScope.ActiveWindowMenuBar(
    content: @Composable @MenuComposable MenuBarScope.() -> Unit,
) {
    if (LocalWindowMenuBarActive.current) {
        val parentComposition = rememberCompositionContext()
        val ownsMenuBar = remember { window.jMenuBar == null }
        val menuBar = remember { window.jMenuBar ?: JMenuBar() }
        if (window.jMenuBar !== menuBar) {
            window.jMenuBar = menuBar
            refreshNativeMenuBar()
        }
        DisposableEffect(menuBar) {
            val composition = menuBar.setContent(parentComposition, content)
            removePreloadedMenus(menuBar)
            onDispose {
                composition.dispose()
                removeFeatureMenus(menuBar)
                if (ownsMenuBar && window.jMenuBar === menuBar) {
                    window.jMenuBar = null
                    refreshNativeMenuBar()
                }
            }
        }
        SideEffect(::refreshNativeMenuBar)
    }
}

private fun JMenuBar.updatePreloadedMenus(titles: List<String>) {
    components
        .filterIsInstance<JMenu>()
        .filter { it.getClientProperty(PRELOADED_MENU_MARKER) == true }
        .forEach(::remove)
    titles.asReversed().forEach { title ->
        add(
            JMenu(title).apply {
                putClientProperty(PRELOADED_MENU_MARKER, true)
            },
            0,
        )
    }
}

private fun removePreloadedMenus(menuBar: JMenuBar) {
    menuBar.components
        .filterIsInstance<JMenu>()
        .filter { it.getClientProperty(PRELOADED_MENU_MARKER) == true }
        .forEach(menuBar::remove)
}

private fun removeFeatureMenus(menuBar: JMenuBar) {
    menuBar.components
        .filterIsInstance<JMenu>()
        .filter { it.getClientProperty(PRELOADED_MENU_MARKER) != true }
        .forEach(menuBar::remove)
}

private fun FrameWindowScope.refreshNativeMenuBar() {
    window.rootPane.revalidate()
    window.rootPane.repaint()
}
