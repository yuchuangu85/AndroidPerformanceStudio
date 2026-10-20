package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuBarScope
import androidx.compose.ui.window.MenuComposable
import androidx.compose.ui.window.MenuScope

private const val MENU_BAR_READY_CALLBACK = "android-performance-studio.menu-bar-ready"
private const val MENU_CONTENT_PROPERTY = "android-performance-studio.menu-content"

/** Menu content published by the active feature page. */
private class WindowMenuBarContent {
    var content: (@Composable WindowMenuBarScope.() -> Unit)? by mutableStateOf(null)
}

/** One top-level menu declared by a page; the host renders it into a reused `JMenu`. */
internal class WindowMenuSpec(
    val text: String,
    val mnemonic: Char?,
    val enabled: Boolean,
    val content: @Composable @MenuComposable MenuScope.() -> Unit,
)

/**
 * Collects a page's top-level menus instead of emitting them.
 *
 * Compose identifies a `Menu` node by its call site, and every page declares its menus in its own
 * file, so emitting them directly made each navigation delete every top-level `JMenu` and insert new
 * ones. The macOS screen menu bar mirrors that asynchronously, so fast switching showed an empty bar.
 * Collecting specs lets the host keep the same top-level menus and only update titles and items.
 */
public class WindowMenuBarScope internal constructor() {
    internal val menus = mutableListOf<WindowMenuSpec>()

    /** Same contract as [MenuBarScope.Menu]. */
    @Suppress("FunctionName", "ktlint:standard:function-naming")
    public fun Menu(
        text: String,
        mnemonic: Char? = null,
        enabled: Boolean = true,
        content: @Composable @MenuComposable MenuScope.() -> Unit,
    ) {
        menus += WindowMenuSpec(text, mnemonic, enabled, content)
    }
}

/** Renders a spec into the `JMenu` at this position, reusing it across page switches. */
@Composable
@MenuComposable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MenuBarScope.StableTopLevelMenu(spec: WindowMenuSpec) {
    // The JMenu's item composition captures its content once, so route it through state.
    val currentContent = rememberUpdatedState(spec.content)
    Menu(text = spec.text, mnemonic = spec.mnemonic, enabled = spec.enabled) {
        currentContent.value.invoke(this)
    }
}

/** Tracks which destination already reported menu readiness. */
internal class MenuBarHostState {
    private var reportedKey: Any? = UnsetPreloadedKey

    /** True exactly once per key, so readiness is reported when the destination changes. */
    fun reportReadinessOnce(key: Any?): Boolean {
        if (reportedKey === key) return false
        reportedKey = key
        return true
    }
}

private val UnsetPreloadedKey = Any()

public val LocalWindowMenuBarActive = staticCompositionLocalOf { true }

/**
 * Hosts the window's native menu bar using Compose's own [MenuBar] control.
 *
 * [MenuBar] creates the `JMenuBar` once for this composition and only clears it when the window is
 * disposed (see `Window.desktop.kt`). Its content reads the holder below, so switching destinations
 * swaps the menus inside that single composition instead of disposing it. Disposing a menu
 * composition runs `SwingApplier.onClear()`, which calls `JMenuBar.removeAll()`; composing one menu
 * bar per page therefore wiped the whole bar on every navigation, which is what made the macOS
 * screen menu bar flash or stay empty while switching quickly.
 *
 * Inside that composition the top-level menus are positional (see [WindowMenuBarScope]): the Nth
 * menu stays the same `JMenu` across pages, so a switch never passes through a zero-menu bar and
 * the macOS screen menu bar never has to delete and re-add its native menus.
 */
@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
public fun FrameWindowScope.WindowMenuBarHost(
    preloadedMenuTitles: List<String> = emptyList(),
    preloadedMenuKey: Any? = preloadedMenuTitles,
    onMenuBarReady: () -> Unit = {},
    fallbackMenu: (@Composable WindowMenuBarScope.() -> Unit)? = null,
) {
    // Published before any feature page composes so every page finds it in the same frame.
    val content =
        remember {
            WindowMenuBarContent().also { holder ->
                window.rootPane.putClientProperty(MENU_CONTENT_PROPERTY, holder)
            }
        }
    val hostState = remember { MenuBarHostState() }
    val currentOnReady = rememberUpdatedState(onMenuBarReady)

    MenuBar {
        val scope = WindowMenuBarScope()
        val pageContent = content.content
        if (pageContent == null) {
            // Never leave the native bar with zero menus: on macOS that reads as a missing menu bar
            // and it flashes every time a page without a feature menu is visited.
            fallbackMenu?.invoke(scope)
        } else {
            pageContent.invoke(scope)
        }
        // Positional slots: the Nth top-level menu stays the same JMenu whichever page declared it.
        scope.menus.forEach { spec -> StableTopLevelMenu(spec) }
    }

    SideEffect {
        window.rootPane.putClientProperty(
            MENU_BAR_READY_CALLBACK,
            Runnable { currentOnReady.value() },
        )
        // Destinations without a feature menu have no page that can report readiness.
        if (hostState.reportReadinessOnce(preloadedMenuKey) && preloadedMenuTitles.isEmpty()) {
            currentOnReady.value()
        }
    }

    DisposableEffect(Unit) {
        onDispose { window.rootPane.putClientProperty(MENU_CONTENT_PROPERTY, null) }
    }
}

/**
 * Publishes the calling page's menus while it is the active destination.
 *
 * The content is composed by [WindowMenuBarHost], so deactivating a page only swaps the published
 * content; it never disposes the menu composition or clears the window's menu bar.
 */
@Composable
@ComposableOpenTarget(-1)
@Suppress("FunctionName", "ktlint:standard:function-naming")
public fun FrameWindowScope.ActiveWindowMenuBar(
    content: @Composable WindowMenuBarScope.() -> Unit,
) {
    if (!LocalWindowMenuBarActive.current) return
    val holder =
        window.rootPane.getClientProperty(MENU_CONTENT_PROPERTY) as? WindowMenuBarContent ?: return
    val currentContent = rememberUpdatedState(content)
    SideEffect {
        if (holder.content !== currentContent.value) {
            holder.content = currentContent.value
        }
        // The window title is only updated once the active page has published its menus.
        notifyNativeMenuBarReady()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (holder.content === currentContent.value) {
                holder.content = null
            }
        }
    }
}

private fun FrameWindowScope.notifyNativeMenuBarReady() {
    (window.rootPane.getClientProperty(MENU_BAR_READY_CALLBACK) as? Runnable)?.run()
}
